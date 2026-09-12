package org.ncssar.rid2caltopo.video

import org.junit.Assert.*
import org.junit.Test
import org.ncssar.rid2caltopo.video.mapcache.S1mCog
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class S1mCogTest {
    private fun header() = File("../test-fixtures/s1m-public-header.bin").readBytes()
    @Test fun compressedRangesAndCropCoordinatesArePreserved() {
        val original = header(); val b = ByteBuffer.wrap(original).order(ByteOrder.LITTLE_ENDIAN)
        val x = b.getDouble(846); val y = b.getDouble(854)
        val piece = S1mCog(original).pieces(x + 4500, x + 4501, y - 4501, y - 4500).single()
        assertEquals(4, piece.row); assertEquals(4, piece.col)
        assertEquals(9, piece.ranges.size)
        assertEquals(255454748L, piece.ranges[0].offset); assertEquals(888806, piece.ranges[0].length)
        assertEquals(original.size + 72L + 7930305, piece.bytes)
        val crop = ByteBuffer.wrap(piece.header).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(-4096.0, crop.getDouble(822), 0.0)
        assertEquals(-4096.0, crop.getDouble(830), 0.0)
        assertEquals(x, crop.getDouble(846), 0.0); assertEquals(y, crop.getDouble(854), 0.0)
        // Source pixels (4500,4500) now map to (404,404), with exactly the same compressed samples.
        assertEquals(404.0, crop.getDouble(822) + (x + 4500 - crop.getDouble(846)), 0.0)
    }
    @Test fun edgePiecesStayWithinSourceAndOutsideRequestsAreEmpty() {
        val header = header(); val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val x = b.getDouble(846); val y = b.getDouble(854); val cog = S1mCog(header)
        val edge = cog.pieces(x + 9998, x + 9999, y - 9999, y - 9998).single()
        assertEquals(4, edge.ranges.size)
        assertTrue(cog.pieces(x - 100, x - 90, y + 90, y + 100).isEmpty())
        val boundary = cog.pieces(x + 1023, x + 1025, y - 1025, y - 1023)
        assertEquals(4, boundary.size)
    }
    @Test fun malformedMetadataIsRejected() {
        assertThrows(Exception::class.java) { S1mCog(byteArrayOf(73,73,42,0)) }
        val header = header(); val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(194 + 10 * 12 + 8, Int.MAX_VALUE)
        assertThrows(Exception::class.java) { S1mCog(header) }
    }
}
