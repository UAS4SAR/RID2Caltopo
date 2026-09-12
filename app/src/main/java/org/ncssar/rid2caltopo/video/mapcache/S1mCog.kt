package org.ncssar.rid2caltopo.video.mapcache

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.floor

/** Lossless, block-aligned subsets of a classic EPSG:6350 S1M GeoTIFF. */
internal class S1mCog(private val header: ByteArray) {
    data class Range(val offset: Long, val length: Int)
    data class Piece(val row: Int, val col: Int, val header: ByteArray, val ranges: List<Range>) {
        val bytes: Long get() = header.size.toLong() + ranges.sumOf { it.length.toLong() }
        fun name(original: String) = original.substringBeforeLast('.') + "_part_${row}_${col}.tif"
    }
    private val order = when (header.take(2)) {
        listOf(73.toByte(), 73.toByte()) -> ByteOrder.LITTLE_ENDIAN
        listOf(77.toByte(), 77.toByte()) -> ByteOrder.BIG_ENDIAN
        else -> error("Invalid TIFF byte order")
    }
    private val buffer = ByteBuffer.wrap(header).order(order)
    private fun u16(at: Int) = buffer.getShort(at).toInt() and 65535
    private fun u32(at: Int) = buffer.getInt(at).toLong() and 0xffffffffL
    private val ifd = u32(4).toInt()
    private val entries: Map<Int, Int>
    private val width: Int
    private val height: Int
    private val block: Int
    private val offsets: List<Long>
    private val counts: List<Long>
    private fun entry(tag: Int) = entries[tag] ?: error("Missing TIFF tag $tag")
    private fun address(tag: Int): Int {
        val e = entry(tag)
        val size = when (u16(e + 2)) { 1, 2 -> 1; 3 -> 2; 4 -> 4; 12 -> 8; else -> error("Unsupported TIFF field") }
        val bytes = u32(e + 4) * size
        require(bytes in 1..65536)
        val at = if (bytes <= 4) e + 8 else u32(e + 8).toInt()
        require(at >= 0 && at.toLong() + bytes <= header.size)
        return at
    }
    private fun integers(tag: Int): List<Long> {
        val e = entry(tag); val at = address(tag); val n = u32(e + 4).toInt()
        return when (u16(e + 2)) {
            3 -> List(n) { u16(at + it * 2).toLong() }
            4 -> List(n) { u32(at + it * 4) }
            else -> error("Invalid integer field")
        }
    }
    init {
        require(u16(2) == 42 && ifd in 8..(header.size - 6)) { "Unsupported TIFF layout" }
        val n = u16(ifd)
        require(n in 1..100 && ifd + 2 + n * 12 + 4 <= header.size)
        entries = (0 until n).associate { u16(ifd + 2 + it * 12) to (ifd + 2 + it * 12) }
        width = integers(256).single().toInt(); height = integers(257).single().toInt()
        block = integers(322).single().toInt()
        require(width in 1..20000 && height in 1..20000 && block == 512 && integers(323).single() == 512L)
        val keys = integers(34735)
        require(keys.drop(4).chunked(4).any { it.size == 4 && it[0] == 3072L && it[1] == 0L && it[3] == 6350L }) { "Unsupported S1M projection" }
        require(34264 !in entries && u16(entry(33550) + 2) == 12 && u16(entry(33922) + 2) == 12)
        require(u32(entry(33550) + 4) >= 2 && u32(entry(33922) + 4) == 6L)
        offsets = integers(324); counts = integers(325)
        require(offsets.size == ((width + block - 1) / block) * ((height + block - 1) / block) && counts.size == offsets.size)
        require(counts.all { it in 1..4_000_000 } && offsets.all { it >= header.size })
        require(buffer.getDouble(address(33550)) == 1.0 && buffer.getDouble(address(33550) + 8) == 1.0)
    }

    /** Model-coordinate rectangle; two pixels of overlap preserve interpolation across piece edges. */
    fun pieces(minX: Double, maxX: Double, minY: Double, maxY: Double): List<Piece> {
        require(listOf(minX, maxX, minY, maxY).all { it.isFinite() })
        val tie = address(33922)
        val originX = buffer.getDouble(tie + 24) - buffer.getDouble(tie)
        val originY = buffer.getDouble(tie + 32) + buffer.getDouble(tie + 8)
        val left = floor(minX - originX - 2).toInt().coerceAtLeast(0)
        val right = ceil(maxX - originX + 2).toInt().coerceAtMost(width - 1)
        val top = floor(originY - maxY - 2).toInt().coerceAtLeast(0)
        val bottom = ceil(originY - minY + 2).toInt().coerceAtMost(height - 1)
        if (left > right || top > bottom) return emptyList()
        return (top / 1024..bottom / 1024).flatMap { row ->
            (left / 1024..right / 1024).map { col -> piece(row, col) }
        }
    }

    private fun piece(row: Int, col: Int): Piece {
        // Each piece has a 1024 m core plus one shared border block on the right/bottom.
        // The overlap keeps bilinear samples valid without requiring the neighboring file.
        val startX = col * 1024; val startY = row * 1024
        val w = minOf(1536, width - startX); val h = minOf(1536, height - startY)
        val sourceCols = (width + block - 1) / block
        val ids = (0 until (h + block - 1) / block).flatMap { r ->
            (0 until (w + block - 1) / block).map { c -> (row * 2 + r) * sourceCols + col * 2 + c }
        }
        val ranges = ids.map { Range(offsets[it], counts[it].toInt()) }
        val out = header.copyOf(header.size + ids.size * 8)
        val b = ByteBuffer.wrap(out).order(order)
        fun scalar(tag: Int, value: Int) {
            val e = entry(tag); b.putShort(e + 2, 4); b.putInt(e + 4, 1); b.putInt(e + 8, value)
        }
        scalar(256, w); scalar(257, h)
        val tie = address(33922)
        b.putDouble(tie, buffer.getDouble(tie) - startX)
        b.putDouble(tie + 8, buffer.getDouble(tie + 8) - startY)
        var position = out.size
        listOf(324, 325).forEachIndexed { group, tag ->
            val e = entry(tag); b.putShort(e + 2, 4); b.putInt(e + 4, ids.size)
            val at = header.size + group * ids.size * 4
            b.putInt(e + 8, if (ids.size == 1) (if (tag == 324) position else ranges[0].length) else at)
            ranges.forEachIndexed { index, range ->
                b.putInt(at + index * 4, if (tag == 324) position else range.length)
                if (tag == 324) position += range.length
            }
        }
        // Discard overview IFD links; all other metadata and compressed samples are unchanged.
        val kept = entries.values.filter { u16(it) != 330 }.map { out.copyOfRange(it, it + 12) }
        b.putShort(ifd, kept.size.toShort())
        kept.forEachIndexed { index, bytes -> System.arraycopy(bytes, 0, out, ifd + 2 + index * 12, 12) }
        b.putInt(ifd + 2 + kept.size * 12, 0)
        return Piece(row, col, out, ranges)
    }
}
