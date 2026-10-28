package org.ncssar.rid2caltopo.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class H264ArchiveAccessUnitAssemblerTest {
    @Test
    fun seiIsPreservedInThePictureAccessUnitAtTheSharedTimestamp() {
        val assembler = H264ArchiveAccessUnitAssembler()
        val sei = avccNal(type = 6, payload = byteArrayOf(0xf5.toByte(), 0x62, 0x09))
        val picture = avccNal(type = 1, payload = byteArrayOf(0x18, 0x22, 0x33))

        assertTrue(assembler.offer(sample(sei, ptsUs = 16_000L, flags = 0)).isEmpty())
        val output = assembler.offer(sample(picture, ptsUs = 16_000L, flags = 1)).single()

        assertEquals(16_000L, output.presentationTimeUs)
        assertEquals(1, output.flags)
        assertArrayEquals(sei + picture, output.data)
        assertEquals(listOf(6, 1), output.data.avccNalUnitTypes())
        assertEquals(1L, assembler.mergedSeiCount)
        assertEquals(0L, assembler.discardedSeiCount)
    }

    @Test
    fun multipleSeiNalsRemainOrderedBeforeTheirPicture() {
        val assembler = H264ArchiveAccessUnitAssembler()
        val firstSei = avccNal(type = 6, payload = byteArrayOf(1))
        val secondSei = avccNal(type = 9, payload = byteArrayOf(0xf0.toByte())) +
            avccNal(type = 6, payload = byteArrayOf(2))
        val idrPicture = avccNal(type = 5, payload = byteArrayOf(3))

        assembler.offer(sample(firstSei, ptsUs = 20_000L, flags = 0))
        assembler.offer(sample(secondSei, ptsUs = 20_000L, flags = 0))
        val output = assembler.offer(sample(idrPicture, ptsUs = 20_000L, flags = 1)).single()

        assertArrayEquals(firstSei + secondSei + idrPicture, output.data)
        assertEquals(listOf(6, 9, 6, 5), output.data.avccNalUnitTypes())
        assertEquals(2L, assembler.mergedSeiCount)
    }

    @Test
    fun seiIsAttachedToFollowingPictureEvenWhenExtractorTimestampsDiffer() {
        val assembler = H264ArchiveAccessUnitAssembler()
        val sei = avccNal(type = 6, payload = byteArrayOf(1))
        val laterPicture = avccNal(type = 1, payload = byteArrayOf(2))

        assembler.offer(sample(sei, ptsUs = 10_000L, flags = 0))
        val output = assembler.offer(sample(laterPicture, ptsUs = 20_000L, flags = 0))

        assertEquals(1, output.size)
        assertArrayEquals(sei + laterPicture, output.single().data)
        assertEquals(20_000L, output.single().presentationTimeUs)
    }

    @Test
    fun annexBExtractorSamplesJoinSeiToFollowingPicture() {
        val assembler = H264ArchiveAccessUnitAssembler()
        val sei = annexBNal(type = 6, payload = byteArrayOf(0xf5.toByte(), 0x62, 0x09))
        val picture = annexBNal(type = 1, payload = byteArrayOf(0x18, 0x22, 0x33))

        assertTrue(assembler.offer(sample(sei, ptsUs = 16_000L, flags = 0)).isEmpty())
        val output = assembler.offer(sample(picture, ptsUs = 16_000L, flags = 1)).single()

        assertEquals(16_000L, output.presentationTimeUs)
        assertEquals(1, output.flags)
        assertArrayEquals(sei + picture, output.data)
        assertEquals(listOf(6, 1), output.data.annexBNalUnitTypes())
        assertEquals(1L, assembler.mergedSeiCount)
    }

    @Test
    fun trailingSeiIsDiscardedInsteadOfBecomingTimedMp4Sample() {
        val assembler = H264ArchiveAccessUnitAssembler()
        val sei = avccNal(type = 6, payload = byteArrayOf(1))

        assertTrue(assembler.offer(sample(sei, ptsUs = 10_000L, flags = 0)).isEmpty())
        assertTrue(assembler.discardTrailingSei())
        assertTrue(!assembler.discardTrailingSei())
        assertEquals(1L, assembler.discardedSeiCount)
    }

    @Test
    fun malformedAvccSamplePassesThroughWithoutBeingBuffered() {
        val assembler = H264ArchiveAccessUnitAssembler()
        val malformed = byteArrayOf(0, 0, 0, 8, 6, 1)

        val output = assembler.offer(sample(malformed, ptsUs = 1L, flags = 0)).single()

        assertArrayEquals(malformed, output.data)
        assertEquals(null, malformed.avccNalUnitTypes())
    }

    @Test
    fun timingPreservesSixtyFpsAndTransmissionGapInsteadOfStretchingToThirtyFps() {
        val timing = H264ArchiveTiming(60)
        val source = List(120) { index -> 500_000L + index * 1_000_000L / 60 + if (index >= 60) 250_000L else 0L }
        val timestamps = source.map(timing::nextPresentationTimeUs)

        assertEquals(source, timestamps)
        assertEquals(120L, timing.pictureCount)
        assertEquals(2_749_999L, timing.endOffsetUs(segmentOffsetUs = 500_000L))
    }

    @Test
    fun timingKeepsThirtyFpsAndRepairsDuplicatePictureTimestampWithoutRecadencing() {
        val timing = H264ArchiveTiming(30)
        val source = listOf(0L, 33_333L, 33_333L, 100_000L)
        assertEquals(listOf(0L, 33_333L, 33_334L, 100_000L), source.map(timing::nextPresentationTimeUs))
        assertEquals(133_333L, timing.endOffsetUs(0L))
    }

    private fun sample(data: ByteArray, ptsUs: Long, flags: Int) =
        H264ArchiveAccessUnitAssembler.Sample(data, ptsUs, flags)

    private fun avccNal(type: Int, payload: ByteArray): ByteArray {
        val nalSize = payload.size + 1
        return byteArrayOf(
            (nalSize ushr 24).toByte(),
            (nalSize ushr 16).toByte(),
            (nalSize ushr 8).toByte(),
            nalSize.toByte(),
            type.toByte(),
        ) + payload
    }

    private fun annexBNal(type: Int, payload: ByteArray): ByteArray =
        byteArrayOf(0, 0, 0, 1, type.toByte()) + payload
}
