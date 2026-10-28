package org.ncssar.rid2caltopo.data

/**
 * Rejoins H.264 SEI-only samples with the following picture sample.
 *
 * MediaMTX preserves DJI telemetry as separate type-6 NAL units. Android's extractor
 * exposes those NALs as separate samples even though they belong to the following
 * picture access unit. Keeping them separate produces duplicate MP4 timestamps that
 * native players handle poorly. Concatenating the AVCC samples preserves the original
 * SEI bytes immediately before the corresponding picture while producing one timed
 * sample. A trailing SEI without a picture is deliberately discarded: it carries no
 * displayable frame and must not become another timed MP4 sample.
 */
internal class H264ArchiveAccessUnitAssembler {
    data class Sample(
        val data: ByteArray,
        val presentationTimeUs: Long,
        val flags: Int,
    )

    private var pendingSei: Sample? = null
    private var pendingSeiCount = 0L

    var mergedSeiCount: Long = 0L
        private set

    var discardedSeiCount: Long = 0L
        private set

    fun offer(sample: Sample): List<Sample> {
        if (sample.data.isSeiOnlyH264AccessUnit()) {
            val pending = pendingSei
            pendingSei = if (pending == null) sample else pending.combinedWith(sample)
            pendingSeiCount += 1L
            return emptyList()
        }

        val pending = pendingSei ?: return listOf(sample)
        if (!sample.data.hasVclH264NalUnit()) return listOf(sample)
        pendingSei = null
        mergedSeiCount += pendingSeiCount
        pendingSeiCount = 0L
        return listOf(pending.prependedToPicture(sample))
    }

    fun discardTrailingSei(): Boolean = (pendingSei != null).also { discarded ->
        if (discarded) discardedSeiCount += pendingSeiCount
        pendingSei = null
        pendingSeiCount = 0L
    }

    private fun Sample.combinedWith(other: Sample): Sample = Sample(
        data = data + other.data,
        presentationTimeUs = presentationTimeUs,
        flags = flags or other.flags,
    )

    private fun Sample.prependedToPicture(picture: Sample): Sample = Sample(
        data = data + picture.data,
        presentationTimeUs = picture.presentationTimeUs,
        flags = picture.flags,
    )
}

/** Preserve source picture timestamps, including higher frame rates and transmission gaps. */
internal class H264ArchiveTiming(private val framesPerSecond: Int = 30) {
    init {
        require(framesPerSecond > 0)
    }

    var pictureCount: Long = 0L
        private set
    private var lastPresentationTimeUs: Long? = null

    fun nextPresentationTimeUs(sourcePresentationTimeUs: Long): Long {
        val previous = lastPresentationTimeUs
        val timestamp = if (previous == null) sourcePresentationTimeUs else maxOf(sourcePresentationTimeUs, previous + 1L)
        pictureCount += 1L
        lastPresentationTimeUs = timestamp
        return timestamp
    }

    fun endOffsetUs(segmentOffsetUs: Long): Long =
        lastPresentationTimeUs?.plus(MICROSECONDS_PER_SECOND / framesPerSecond) ?: segmentOffsetUs
}

internal fun ByteArray.avccNalUnitTypes(): List<Int>? {
    val types = mutableListOf<Int>()
    var offset = 0
    while (offset < size) {
        if (size - offset < AVCC_LENGTH_BYTES) return null
        var nalSize = 0
        repeat(AVCC_LENGTH_BYTES) { index ->
            nalSize = (nalSize shl 8) or (this[offset + index].toInt() and 0xff)
        }
        offset += AVCC_LENGTH_BYTES
        if (nalSize <= 0 || nalSize > size - offset) return null
        types += this[offset].toInt() and H264_NAL_TYPE_MASK
        offset += nalSize
    }
    return types
}

internal fun ByteArray.annexBNalUnitTypes(): List<Int>? {
    val types = mutableListOf<Int>()
    var offset = 0
    while (offset <= size - 3) {
        val startCodeLength = when {
            offset <= size - 4 &&
                this[offset] == 0.toByte() &&
                this[offset + 1] == 0.toByte() &&
                this[offset + 2] == 0.toByte() &&
                this[offset + 3] == 1.toByte() -> 4
            this[offset] == 0.toByte() &&
                this[offset + 1] == 0.toByte() &&
                this[offset + 2] == 1.toByte() -> 3
            else -> 0
        }
        if (startCodeLength == 0) {
            offset += 1
            continue
        }
        val nalOffset = offset + startCodeLength
        if (nalOffset >= size) return null
        types += this[nalOffset].toInt() and H264_NAL_TYPE_MASK
        offset = nalOffset + 1
    }
    return types.takeIf { it.isNotEmpty() }
}

private fun ByteArray.h264NalUnitTypes(): List<Int>? =
    avccNalUnitTypes() ?: annexBNalUnitTypes()

private fun ByteArray.isSeiOnlyH264AccessUnit(): Boolean {
    val types = h264NalUnitTypes() ?: return false
    return H264_NAL_TYPE_SEI in types &&
        types.none { it in H264_NAL_TYPE_NON_IDR..H264_NAL_TYPE_IDR }
}

internal fun ByteArray.hasVclH264NalUnit(): Boolean =
    h264NalUnitTypes()?.any { it in H264_NAL_TYPE_NON_IDR..H264_NAL_TYPE_IDR } == true

private const val AVCC_LENGTH_BYTES = 4
private const val H264_NAL_TYPE_MASK = 0x1f
private const val H264_NAL_TYPE_NON_IDR = 1
private const val H264_NAL_TYPE_IDR = 5
private const val H264_NAL_TYPE_SEI = 6
private const val MICROSECONDS_PER_SECOND = 1_000_000L
