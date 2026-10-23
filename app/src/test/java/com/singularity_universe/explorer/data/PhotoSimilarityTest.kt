package com.singularity_universe.explorer.data

import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoSimilarityTest {
    /** A "photo": smooth shapes plus fine detail. */
    private fun scene(width: Int, height: Int, seed: Int, shift: Int = 0): IntArray {
        val random = Random(seed)
        val detail = IntArray(width * height) { random.nextInt(-40, 40) }
        return IntArray(width * height) { i ->
            val x = i % width + shift
            val y = i / width
            // The seed changes the horizontal pattern too: a per-row brightness change alone would cancel
            // out in dHash, which compares horizontal neighbours.
            (128 + 60 * sin(x / (8.0 + seed)) + 50 * sin(y / 13.0 + seed) * sin(x / 31.0 + seed) + detail[i]).toInt().coerceIn(0, 255)
        }
    }

    /** Shrinks a picture by averaging, like a thumbnail. */
    private fun shrink(src: IntArray, w: Int, h: Int, tw: Int, th: Int) = IntArray(tw * th) { i ->
        val tx = i % tw
        val ty = i / tw
        var sum = 0
        var n = 0
        for (y in ty * h / th until (ty + 1) * h / th) for (x in tx * w / tw until (tx + 1) * w / tw) {
            sum += src[y * w + x]
            n++
        }
        sum / n.coerceAtLeast(1)
    }

    private fun blur(src: IntArray, w: Int, h: Int, radius: Int) = IntArray(w * h) { i ->
        val x = i % w
        val y = i / w
        var sum = 0
        var n = 0
        for (dy in -radius..radius) for (dx in -radius..radius) {
            val xx = x + dx
            val yy = y + dy
            if (xx in 0 until w && yy in 0 until h) {
                sum += src[yy * w + xx]
                n++
            }
        }
        sum / n
    }

    private fun hash(picture: IntArray) = PhotoSimilarity.dHash(shrink(picture, 256, 192, 9, 8))

    @Test
    fun retakesAreSimilarOtherPhotosAreNot() {
        val photo = scene(256, 192, seed = 1)
        val retake = scene(256, 192, seed = 1, shift = 3) // The camera moved a little.
        val edited = photo.map { (it * 0.9 + 10).toInt() }.toIntArray() // Darker.
        val other = scene(256, 192, seed = 7)
        val toRetake = PhotoSimilarity.distance(hash(photo), hash(retake))
        val toEdited = PhotoSimilarity.distance(hash(photo), hash(edited))
        val toOther = PhotoSimilarity.distance(hash(photo), hash(other))
        assertTrue("retake $toRetake", toRetake <= 6)
        assertTrue("edited $toEdited", toEdited <= 6)
        assertTrue("other $toOther", toOther > 10)

        val groups = PhotoSimilarity.group(listOf("photo" to hash(photo), "retake" to hash(retake), "other" to hash(other), "edited" to hash(edited)))
        assertEquals(listOf(setOf("photo", "retake", "edited")), groups.map { it.toSet() })
    }

    @Test
    fun blurryScoresLowerThanSharp() {
        val sharp = scene(256, 192, seed = 3)
        val soft = blur(sharp, 256, 192, radius = 3)
        val flat = IntArray(256 * 192) { 120 }
        val sharpScore = PhotoSimilarity.sharpness(sharp, 256, 192)
        val softScore = PhotoSimilarity.sharpness(soft, 256, 192)
        assertTrue("sharp $sharpScore vs soft $softScore", sharpScore > 10 * softScore)
        assertTrue("sharp isn't flagged", sharpScore > PhotoSimilarity.BLURRY_BELOW)
        assertTrue("soft is flagged ($softScore)", softScore < PhotoSimilarity.BLURRY_BELOW)
        assertEquals(0.0, PhotoSimilarity.sharpness(flat, 256, 192), 0.0001)
    }
}
