package com.singularity_universe.explorer.data

/**
 * Finding similar and blurry photos from small grayscale versions of them (0..255 per pixel, row
 * by row). Plain Kotlin, so it's unit-tested on the JVM.
 */
object PhotoSimilarity {
    /** dHash input size: 9 × 8 pixels give 8 × 8 = 64 comparisons. */
    const val HASH_WIDTH = 9
    const val HASH_HEIGHT = 8

    /**
     * A 64-bit "difference hash": each bit says whether a pixel is brighter than its right-hand
     * neighbour, in a 9 × 8 picture. Similar photos (bursts, retakes, small crops or edits) get
     * hashes that differ in only a few bits.
     */
    fun dHash(gray: IntArray): Long {
        require(gray.size == HASH_WIDTH * HASH_HEIGHT)
        var hash = 0L
        var bit = 0
        for (y in 0 until HASH_HEIGHT) {
            for (x in 0 until HASH_WIDTH - 1) {
                if (gray[y * HASH_WIDTH + x] > gray[y * HASH_WIDTH + x + 1]) hash = hash or (1L shl bit)
                bit++
            }
        }
        return hash
    }

    fun distance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    /** Groups of keys whose hashes are within [maxDistance] bits of each other (directly or through others). */
    fun <K> group(hashes: List<Pair<K, Long>>, maxDistance: Int = 6): List<List<K>> {
        val parent = IntArray(hashes.size) { it }
        fun root(i: Int): Int {
            var r = i
            while (parent[r] != r) r = parent[r]
            var j = i
            while (parent[j] != r) j = parent[j].also { parent[j] = r }
            return r
        }
        for (i in hashes.indices) for (j in i + 1 until hashes.size) {
            if (distance(hashes[i].second, hashes[j].second) <= maxDistance) parent[root(i)] = root(j)
        }
        return hashes.indices.groupBy(::root).values.filter { it.size > 1 }.map { members -> members.map { hashes[it].first } }
    }

    /**
     * Sharpness: the variance of the Laplacian (how strongly brightness changes between
     * neighbouring pixels). Blurry photos score low; a plain flat picture scores 0.
     */
    fun sharpness(gray: IntArray, width: Int, height: Int): Double {
        if (width < 3 || height < 3) return 0.0
        var sum = 0.0
        var sumSquares = 0.0
        var n = 0
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val i = y * width + x
                val laplacian = (4 * gray[i] - gray[i - 1] - gray[i + 1] - gray[i - width] - gray[i + width]).toDouble()
                sum += laplacian
                sumSquares += laplacian * laplacian
                n++
            }
        }
        val mean = sum / n
        return sumSquares / n - mean * mean
    }

    /** Below this (on a ~256 px version) a photo counts as blurry. Chosen to flag clearly soft shots only. */
    const val BLURRY_BELOW = 40.0
}
