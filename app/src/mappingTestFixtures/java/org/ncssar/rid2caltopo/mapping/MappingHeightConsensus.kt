package org.ncssar.rid2caltopo.mapping

// Historical Android policy for comparison fixtures only. Production uses native/mapping/Reconstruction.cpp.

import kotlin.math.abs

/** One vote per stereo pair per cell; retain competing surfaces before choosing support. */
internal class MappingHeightConsensus(size: Int) {
    private val means = Array(4) { FloatArray(size) }
    private val counts = Array(4) { ShortArray(size) }
    fun add(index: Int, height: Float) {
        if (!height.isFinite()) return
        var selected = -1
        for (bin in 0..3) if (counts[bin][index] > 0 && abs(means[bin][index] - height) < .65 &&
            (selected < 0 || abs(means[bin][index] - height) < abs(means[selected][index] - height))) selected = bin
        if (selected < 0) selected = (0..3).firstOrNull { counts[it][index].toInt() == 0 } ?: -1
        if (selected >= 0) {
            val n = counts[selected][index].toInt()
            means[selected][index] += (height - means[selected][index]) / (n + 1)
            counts[selected][index] = (n + 1).coerceAtMost(30000).toShort()
        }
    }
    fun height(index: Int): Float {
        var best = -1
        for (bin in 0..3) if (counts[bin][index] >= 2 && (best < 0 || counts[bin][index] > counts[best][index])) best = bin
        return if (best >= 0) means[best][index] else Float.NaN
    }
}
