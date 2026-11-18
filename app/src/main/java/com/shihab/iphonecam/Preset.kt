package com.shihab.iphonecam

import android.graphics.Bitmap
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Faithful port of Shihab's photo preset (v6 WHITE) from
 * workspace/photo-presets/shihab-preset.py (v6, grade_white_v6.py).
 *
 * Recipe, in order:
 *  1. Per-channel percentile stretch, 1st-99th percentile (dehaze).
 *  2. S-curve with slightly lifted blacks. LUT points:
 *     (0, 0.03), (0.25, 0.21), (0.5, 0.5), (0.75, 0.79), (1, 0.99).
 *  3. Channel gains: R x0.99, G x1.0, B x1.02 (white feel, NO yellow).
 *  4. Extra saturation only in greens: HSV hue band centred on green
 *     (hue 80 on PIL's 0-255 scale, triangular mask width 35), +8%.
 *  5. Saturation x1.15, brightness x1.07, contrast x1.08 (around the
 *     image mean luminance, like PIL ImageEnhance).
 *  6. Clarity: wide local-contrast pass (box blur radius 8, 2 passes,
 *     ~= PIL UnsharpMask(radius=8)), 30%, threshold 2.
 *  7. Unsharp mask, radius 2, 90%, threshold 2. The blur is a separable
 *     binomial kernel [1,4,6,4,1]/16 applied twice per direction, which
 *     approximates PIL's GaussianBlur(radius=2); the colour steps above
 *     are exact ports, not approximations.
 *  8. Gentle vignette: corners darkened up to 7%.
 *
 * Runs on Dispatchers.Default with row-chunked parallelism. Full
 * resolution is kept throughout.
 */
object Preset {

    private val curveX = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)
    private val curveY = floatArrayOf(0.03f, 0.21f, 0.5f, 0.79f, 0.99f)

    suspend fun applyPreset(src: Bitmap): Bitmap = coroutineScope {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        // 1. Per-channel 1st / 99th percentiles.
        val histR = IntArray(256)
        val histG = IntArray(256)
        val histB = IntArray(256)
        for (px in pixels) {
            histR[(px shr 16) and 0xFF]++
            histG[(px shr 8) and 0xFF]++
            histB[px and 0xFF]++
        }
        val total = pixels.size
        val loR = percentile(histR, total, 1.0)
        val hiR = percentile(histR, total, 99.0)
        val loG = percentile(histG, total, 1.0)
        val hiG = percentile(histG, total, 99.0)
        val loB = percentile(histB, total, 1.0)
        val hiB = percentile(histB, total, 99.0)

        // 2 + 3. One combined LUT per channel: stretch -> S-curve -> gain.
        val lutR = combinedLut(loR, hiR, 0.99f)
        val lutG = combinedLut(loG, hiG, 1.0f)
        val lutB = combinedLut(loB, hiB, 1.02f)

        val chunks = chunkRanges(h)

        // 4 + 5. Fused pass: green HSV boost, saturation, brightness.
        // Also accumulates the post-brightness mean luminance for step 6.
        val lumaSums = chunks.map { (y0, y1) ->
            async {
                var sum = 0.0
                for (y in y0 until y1) {
                    var idx = y * w
                    val end = idx + w
                    while (idx < end) {
                        val px = pixels[idx]
                        var r = lutR[(px shr 16) and 0xFF].toFloat()
                        var g = lutG[(px shr 8) and 0xFF].toFloat()
                        var b = lutB[px and 0xFF].toFloat()

                        // 4. Green-only saturation boost (HSV space).
                        // Multiplying HSV saturation by f equals scaling the
                        // chroma around the channel maximum: c' = max + (c-max)*f.
                        val mx = max(r, max(g, b))
                        val mn = min(r, min(g, b))
                        val delta = mx - mn
                        if (delta > 0f) {
                            val hue: Float = when (mx) {
                                r -> {
                                    var hh = 42.5f * (g - b) / delta
                                    if (hh < 0f) hh += 255f
                                    hh
                                }
                                g -> 85f + 42.5f * (b - r) / delta
                                else -> 170f + 42.5f * (r - g) / delta
                            }
                            val mask = (1f - abs(hue - 80f) / 35f).coerceIn(0f, 1f)
                            if (mask > 0f) {
                                val f = 1f + 0.08f * mask
                                r = (mx + (r - mx) * f).coerceIn(0f, 255f)
                                g = (mx + (g - mx) * f).coerceIn(0f, 255f)
                                b = (mx + (b - mx) * f).coerceIn(0f, 255f)
                            }
                        }

                        // 5a. Saturation x1.15 around luma (PIL ImageEnhance.Color).
                        val luma = 0.299f * r + 0.587f * g + 0.114f * b
                        r = (luma + (r - luma) * 1.15f).coerceIn(0f, 255f)
                        g = (luma + (g - luma) * 1.15f).coerceIn(0f, 255f)
                        b = (luma + (b - luma) * 1.15f).coerceIn(0f, 255f)

                        // 5b. Brightness x1.07 (PIL ImageEnhance.Brightness).
                        r = (r * 1.07f).coerceIn(0f, 255f)
                        g = (g * 1.07f).coerceIn(0f, 255f)
                        b = (b * 1.07f).coerceIn(0f, 255f)

                        sum += 0.299 * r + 0.587 * g + 0.114 * b
                        pixels[idx] = (0xFF shl 24) or
                            (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
                        idx++
                    }
                }
                sum
            }
        }.awaitAll().sum()
        val mean = (lumaSums / total).toFloat()

        // 6. Contrast x1.08 around the mean luminance (PIL ImageEnhance.Contrast).
        chunks.map { (y0, y1) ->
            async {
                for (y in y0 until y1) {
                    var idx = y * w
                    val end = idx + w
                    while (idx < end) {
                        val px = pixels[idx]
                        val r = (((px shr 16) and 0xFF) - mean) * 1.08f + mean
                        val g = (((px shr 8) and 0xFF) - mean) * 1.08f + mean
                        val b = ((px and 0xFF) - mean) * 1.08f + mean
                        pixels[idx] = (0xFF shl 24) or
                            (r.toInt().coerceIn(0, 255) shl 16) or
                            (g.toInt().coerceIn(0, 255) shl 8) or
                            b.toInt().coerceIn(0, 255)
                        idx++
                    }
                }
            }
        }.awaitAll()

        // 6. Clarity (wide local contrast, 30%, threshold 2).
        clarity(pixels, w, h)

        // 7. Unsharp mask (radius 2, 90%, threshold 2).
        unsharp(pixels, w, h, 0.9f)

        // 8. Gentle vignette: corners darkened up to 7%.
        vignette(pixels, w, h)

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        out
    }

    private fun percentile(hist: IntArray, total: Int, p: Double): Float {
        val rank = p / 100.0 * (total - 1)
        var cum = 0L
        for (v in 0..255) {
            val c = hist[v].toLong()
            if (cum + c > rank) return v.toFloat()
            cum += c
        }
        return 255f
    }

    private fun combinedLut(lo: Float, hi: Float, gain: Float): IntArray {
        val lut = IntArray(256)
        val range = (hi - lo).coerceAtLeast(1f)
        for (i in 0..255) {
            val s = ((i - lo) / range).coerceIn(0f, 1f)
            val idx = (s * 255f).toInt().coerceIn(0, 255)
            var v = curve(idx) * gain
            v = v.coerceIn(0f, 1f)
            lut[i] = (v * 255f).toInt().coerceIn(0, 255)
        }
        return lut
    }

    private fun curve(idx: Int): Float {
        val x = idx / 255f
        var k = 0
        while (k < 4 && x > curveX[k + 1]) k++
        val x0 = curveX[k]
        val x1 = curveX[k + 1]
        val y0 = curveY[k]
        val y1 = curveY[k + 1]
        val t = if (x1 > x0) (x - x0) / (x1 - x0) else 0f
        return (y0 + (y1 - y0) * t).coerceIn(0f, 1f)
    }

    private fun chunkRanges(h: Int): List<Pair<Int, Int>> {
        val n = min(8, max(1, Runtime.getRuntime().availableProcessors()))
        val step = (h + n - 1) / n
        val out = ArrayList<Pair<Int, Int>>()
        var y = 0
        while (y < h) {
            val y1 = min(h, y + step)
            out.add(y to y1)
            y = y1
        }
        return out
    }

    private suspend fun unsharp(pixels: IntArray, w: Int, h: Int, percent: Float) = coroutineScope {
        val tmp = IntArray(pixels.size)
        val blur = IntArray(pixels.size)
        // Two separable binomial passes per direction ~= PIL GaussianBlur(radius=2).
        blurPass(pixels, tmp, w, h, horizontal = true)
        blurPass(tmp, blur, w, h, horizontal = false)
        blurPass(blur, tmp, w, h, horizontal = true)
        blurPass(tmp, blur, w, h, horizontal = false)

        chunkRanges(h).map { (y0, y1) ->
            async {
                for (y in y0 until y1) {
                    var idx = y * w
                    val end = idx + w
                    while (idx < end) {
                        val o = pixels[idx]
                        val b = blur[idx]
                        val r = sharpenChannel((o shr 16) and 0xFF, (b shr 16) and 0xFF, percent)
                        val g = sharpenChannel((o shr 8) and 0xFF, (b shr 8) and 0xFF, percent)
                        val bl = sharpenChannel(o and 0xFF, b and 0xFF, percent)
                        pixels[idx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
                        idx++
                    }
                }
            }
        }.awaitAll()
    }

    private fun sharpenChannel(orig: Int, blurred: Int, factor: Float): Int {
        val diff = orig - blurred
        if (abs(diff) <= 2) return orig
        return (orig + diff * factor).toInt().coerceIn(0, 255)
    }

    /**
     * Clarity: PIL UnsharpMask(radius=8, percent=30, threshold=2).
     * A wide blur (separable box blur, radius 8, 2 passes ~= Gaussian
     * sigma 7) gives the local average; boosting the difference adds
     * local contrast, which is what makes a hazy face turn clear.
     */
    private suspend fun clarity(pixels: IntArray, w: Int, h: Int) = coroutineScope {
        val tmp = IntArray(pixels.size)
        val blur = IntArray(pixels.size)
        boxBlur(pixels, tmp, w, h, radius = 8, horizontal = true)
        boxBlur(tmp, blur, w, h, radius = 8, horizontal = false)
        boxBlur(blur, tmp, w, h, radius = 8, horizontal = true)
        boxBlur(tmp, blur, w, h, radius = 8, horizontal = false)

        chunkRanges(h).map { (y0, y1) ->
            async {
                for (y in y0 until y1) {
                    var idx = y * w
                    val end = idx + w
                    while (idx < end) {
                        val o = pixels[idx]
                        val b = blur[idx]
                        val r = sharpenChannel((o shr 16) and 0xFF, (b shr 16) and 0xFF, 0.3f)
                        val g = sharpenChannel((o shr 8) and 0xFF, (b shr 8) and 0xFF, 0.3f)
                        val bl = sharpenChannel(o and 0xFF, b and 0xFF, 0.3f)
                        pixels[idx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
                        idx++
                    }
                }
            }
        }.awaitAll()
    }

    /**
     * One separable box blur pass (radius r, width 2r+1) with a sliding
     * window, so it is O(1) per pixel. Edges are clamped (replicate).
     * The horizontal pass chunks by rows; the vertical pass chunks by
     * columns so the sliding window can run down each column.
     */
    private suspend fun boxBlur(
        src: IntArray,
        dst: IntArray,
        w: Int,
        h: Int,
        radius: Int,
        horizontal: Boolean,
    ) = coroutineScope {
        val width = 2 * radius + 1
        if (horizontal) {
            chunkRanges(h).map { (y0, y1) ->
                async {
                    for (y in y0 until y1) {
                        var sr = 0
                        var sg = 0
                        var sb = 0
                        for (t in -radius..radius) {
                            val px = src[y * w + t.coerceIn(0, w - 1)]
                            sr += (px shr 16) and 0xFF
                            sg += (px shr 8) and 0xFF
                            sb += px and 0xFF
                        }
                        for (x in 0 until w) {
                            dst[y * w + x] = (0xFF shl 24) or
                                ((sr / width) shl 16) or ((sg / width) shl 8) or (sb / width)
                            val outPx = src[y * w + (x - radius).coerceIn(0, w - 1)]
                            val inPx = src[y * w + (x + radius + 1).coerceIn(0, w - 1)]
                            sr += ((inPx shr 16) and 0xFF) - ((outPx shr 16) and 0xFF)
                            sg += ((inPx shr 8) and 0xFF) - ((outPx shr 8) and 0xFF)
                            sb += (inPx and 0xFF) - (outPx and 0xFF)
                        }
                    }
                }
            }.awaitAll()
        } else {
            val n = min(8, max(1, Runtime.getRuntime().availableProcessors()))
            val step = (w + n - 1) / n
            val colRanges = ArrayList<Pair<Int, Int>>()
            var x0 = 0
            while (x0 < w) {
                val x1 = min(w, x0 + step)
                colRanges.add(x0 to x1)
                x0 = x1
            }
            colRanges.map { (cx0, cx1) ->
                async {
                    for (x in cx0 until cx1) {
                        var sr = 0
                        var sg = 0
                        var sb = 0
                        for (t in -radius..radius) {
                            val px = src[t.coerceIn(0, h - 1) * w + x]
                            sr += (px shr 16) and 0xFF
                            sg += (px shr 8) and 0xFF
                            sb += px and 0xFF
                        }
                        for (y in 0 until h) {
                            dst[y * w + x] = (0xFF shl 24) or
                                ((sr / width) shl 16) or ((sg / width) shl 8) or (sb / width)
                            val outPx = src[(y - radius).coerceIn(0, h - 1) * w + x]
                            val inPx = src[(y + radius + 1).coerceIn(0, h - 1) * w + x]
                            sr += ((inPx shr 16) and 0xFF) - ((outPx shr 16) and 0xFF)
                            sg += ((inPx shr 8) and 0xFF) - ((outPx shr 8) and 0xFF)
                            sb += (inPx and 0xFF) - (outPx and 0xFF)
                        }
                    }
                }
            }.awaitAll()
        }
    }

    /** Gentle vignette: corners darkened up to 7%, fading in past 0.55 of the radius. */
    private suspend fun vignette(pixels: IntArray, w: Int, h: Int) = coroutineScope {
        val cx = w / 2f
        val cy = h / 2f
        chunkRanges(h).map { (y0, y1) ->
            async {
                for (y in y0 until y1) {
                    val dy = (y - cy) / cy
                    var idx = y * w
                    val end = idx + w
                    while (idx < end) {
                        val x = idx - y * w
                        val dx = (x - cx) / cx
                        val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                        val f = 1f - 0.07f * (dist - 0.55f).coerceIn(0f, 1f) *
                            (dist - 0.55f).coerceIn(0f, 1f)
                        val px = pixels[idx]
                        val r = (((px shr 16) and 0xFF) * f).toInt().coerceIn(0, 255)
                        val g = (((px shr 8) and 0xFF) * f).toInt().coerceIn(0, 255)
                        val b = ((px and 0xFF) * f).toInt().coerceIn(0, 255)
                        pixels[idx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                        idx++
                    }
                }
            }
        }.awaitAll()
    }

    private suspend fun blurPass(
        src: IntArray,
        dst: IntArray,
        w: Int,
        h: Int,
        horizontal: Boolean,
    ) = coroutineScope {
        val kernel = intArrayOf(1, 4, 6, 4, 1)
        chunkRanges(h).map { (y0, y1) ->
            async {
                for (y in y0 until y1) {
                    for (x in 0 until w) {
                        var sr = 0
                        var sg = 0
                        var sb = 0
                        for (t in -2..2) {
                            val px = if (horizontal) {
                                src[y * w + (x + t).coerceIn(0, w - 1)]
                            } else {
                                src[(y + t).coerceIn(0, h - 1) * w + x]
                            }
                            val kw = kernel[t + 2]
                            sr += ((px shr 16) and 0xFF) * kw
                            sg += ((px shr 8) and 0xFF) * kw
                            sb += (px and 0xFF) * kw
                        }
                        dst[y * w + x] = (0xFF shl 24) or
                            ((sr shr 4) shl 16) or ((sg shr 4) shl 8) or (sb shr 4)
                    }
                }
            }
        }.awaitAll()
    }
}
