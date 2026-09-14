package com.v2ray.ang.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pre-renders a blurred copy of the custom background image into app-private storage.
 * The main UI then just loads the cached file — no realtime RenderEffect.
 */
object BackgroundBlurHelper {

    private const val DIR = "backgrounds"
    private const val ORIGINAL = "custom_bg_original.jpg"
    private const val BLURRED = "custom_bg_blurred.jpg"
    private const val MAX_EDGE = 1600

    fun originalFile(context: Context): File =
        File(File(context.filesDir, DIR).apply { mkdirs() }, ORIGINAL)

    fun blurredFile(context: Context): File =
        File(File(context.filesDir, DIR).apply { mkdirs() }, BLURRED)

    /**
     * Copies the picked image into app storage as the source original.
     * Returns absolute path or null on failure.
     */
    fun importOriginalImage(context: Context, uri: Uri): String? {
        return try {
            val out = originalFile(context)
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(out).use { output -> input.copyTo(output) }
            } ?: return null

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(out.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                out.delete()
                return null
            }

            MmkvManager.encodeSettings(AppConfig.PREF_CUSTOM_BACKGROUND_URI, out.absolutePath)
            // Invalidate previous blur cache
            blurredFile(context).delete()
            MmkvManager.encodeSettings(AppConfig.PREF_CUSTOM_BACKGROUND_BLURRED_PATH, "")
            MmkvManager.encodeSettings(AppConfig.PREF_CUSTOM_BACKGROUND_BLUR_KEY, "")
            out.absolutePath
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to import background image", e)
            null
        }
    }

    /**
     * Builds (or reuses) a blurred JPEG for the current original + blur level.
     * @return absolute path of image to display (blurred or original), or null
     */
    fun ensureDisplayImage(context: Context, blurLevel: Int): String? {
        val level = blurLevel.coerceIn(0, 100)
        val original = originalFile(context)
        val storedPath = MmkvManager.decodeSettingsString(AppConfig.PREF_CUSTOM_BACKGROUND_URI).orEmpty()

        val sourceFile = resolveSourceFile(context, original, storedPath)
            ?: return storedPath.takeIf { it.isNotBlank() }

        if (level <= 0) {
            return sourceFile.absolutePath
        }

        val cacheKey = "${sourceFile.absolutePath}|${sourceFile.lastModified()}|$level"
        val prevKey = MmkvManager.decodeSettingsString(AppConfig.PREF_CUSTOM_BACKGROUND_BLUR_KEY).orEmpty()
        val blurred = blurredFile(context)
        if (prevKey == cacheKey && blurred.exists() && blurred.length() > 0L) {
            return blurred.absolutePath
        }

        return try {
            val bmp = decodeDownsampled(sourceFile) ?: return sourceFile.absolutePath
            val blurredBmp = applyStackBlur(bmp, levelToRadius(level))
            if (blurredBmp !== bmp && !bmp.isRecycled) {
                bmp.recycle()
            }
            FileOutputStream(blurred).use { fos ->
                blurredBmp.compress(Bitmap.CompressFormat.JPEG, 90, fos)
            }
            if (!blurredBmp.isRecycled) blurredBmp.recycle()
            MmkvManager.encodeSettings(AppConfig.PREF_CUSTOM_BACKGROUND_BLURRED_PATH, blurred.absolutePath)
            MmkvManager.encodeSettings(AppConfig.PREF_CUSTOM_BACKGROUND_BLUR_KEY, cacheKey)
            blurred.absolutePath
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to build blurred background", e)
            sourceFile.absolutePath
        }
    }

    private fun resolveSourceFile(context: Context, original: File, storedPath: String): File? {
        if (original.exists() && original.length() > 0L) return original
        if (storedPath.isNotBlank()) {
            val asFile = File(storedPath)
            if (asFile.exists() && asFile.length() > 0L) return asFile
            if (storedPath.startsWith("content://") || storedPath.startsWith("file:")) {
                return try {
                    val uri = Uri.parse(storedPath)
                    importOriginalImage(context, uri)
                    original.takeIf { it.exists() && it.length() > 0L }
                } catch (_: Exception) {
                    null
                }
            }
        }
        return null
    }

    /** 0..100 → blur radius 1..25. */
    private fun levelToRadius(level: Int): Int {
        val t = level / 100f
        return (t * t * 25f).roundToInt().coerceIn(1, 25)
    }

    private fun decodeDownsampled(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        val maxDim = max(bounds.outWidth, bounds.outHeight)
        while (maxDim / sample > MAX_EDGE) {
            sample *= 2
        }

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
        return if (decoded.config == Bitmap.Config.ARGB_8888 && decoded.isMutable) {
            decoded
        } else {
            val copy = decoded.copy(Bitmap.Config.ARGB_8888, true)
            decoded.recycle()
            copy
        }
    }

    /**
     * Fast stack blur (Mario Klingemann). Radius 1..25.
     * Works on all API levels without RenderScript.
     */
    private fun applyStackBlur(sentBitmap: Bitmap, radius: Int): Bitmap {
        val bitmap = if (sentBitmap.isMutable) sentBitmap else sentBitmap.copy(Bitmap.Config.ARGB_8888, true)
        if (radius < 1) return bitmap

        val w = bitmap.width
        val h = bitmap.height
        val pix = IntArray(w * h)
        bitmap.getPixels(pix, 0, w, 0, 0, w, h)

        val wm = w - 1
        val hm = h - 1
        val wh = w * h
        val div = radius + radius + 1

        val r = IntArray(wh)
        val g = IntArray(wh)
        val b = IntArray(wh)
        val vmin = IntArray(max(w, h))

        var divsum = (div + 1) shr 1
        divsum *= divsum
        val dv = IntArray(256 * divsum)
        for (i in dv.indices) {
            dv[i] = i / divsum
        }

        var yi = 0
        var yw = 0

        val stack = Array(div) { IntArray(3) }
        val r1 = radius + 1

        for (y in 0 until h) {
            var boutsum = 0
            var goutsum = 0
            var routsum = 0
            var binsum = 0
            var ginsum = 0
            var rinsum = 0
            var bsum = 0
            var gsum = 0
            var rsum = 0

            for (i in -radius..radius) {
                val p = pix[yi + min(wm, max(i, 0))]
                val sir = stack[i + radius]
                sir[0] = (p and 0xff0000) shr 16
                sir[1] = (p and 0x00ff00) shr 8
                sir[2] = p and 0x0000ff
                val rbs = r1 - kotlin.math.abs(i)
                rsum += sir[0] * rbs
                gsum += sir[1] * rbs
                bsum += sir[2] * rbs
                if (i > 0) {
                    rinsum += sir[0]
                    ginsum += sir[1]
                    binsum += sir[2]
                } else {
                    routsum += sir[0]
                    goutsum += sir[1]
                    boutsum += sir[2]
                }
            }
            var stackpointer = radius

            for (x in 0 until w) {
                r[yi] = dv[rsum]
                g[yi] = dv[gsum]
                b[yi] = dv[bsum]

                rsum -= routsum
                gsum -= goutsum
                bsum -= boutsum

                val stackstart = stackpointer - radius + div
                val sir = stack[stackstart % div]

                routsum -= sir[0]
                goutsum -= sir[1]
                boutsum -= sir[2]

                if (y == 0) {
                    vmin[x] = min(x + radius + 1, wm)
                }
                val p = pix[yw + vmin[x]]

                sir[0] = (p and 0xff0000) shr 16
                sir[1] = (p and 0x00ff00) shr 8
                sir[2] = p and 0x0000ff

                rinsum += sir[0]
                ginsum += sir[1]
                binsum += sir[2]

                rsum += rinsum
                gsum += ginsum
                bsum += binsum

                stackpointer = (stackpointer + 1) % div
                val sir2 = stack[stackpointer % div]

                routsum += sir2[0]
                goutsum += sir2[1]
                boutsum += sir2[2]

                rinsum -= sir2[0]
                ginsum -= sir2[1]
                binsum -= sir2[2]

                yi++
            }
            yw += w
        }

        for (x in 0 until w) {
            var boutsum = 0
            var goutsum = 0
            var routsum = 0
            var binsum = 0
            var ginsum = 0
            var rinsum = 0
            var bsum = 0
            var gsum = 0
            var rsum = 0

            var yp = -radius * w
            for (i in -radius..radius) {
                val yi2 = max(0, yp) + x
                val sir = stack[i + radius]
                sir[0] = r[yi2]
                sir[1] = g[yi2]
                sir[2] = b[yi2]
                val rbs = r1 - kotlin.math.abs(i)
                rsum += r[yi2] * rbs
                gsum += g[yi2] * rbs
                bsum += b[yi2] * rbs
                if (i > 0) {
                    rinsum += sir[0]
                    ginsum += sir[1]
                    binsum += sir[2]
                } else {
                    routsum += sir[0]
                    goutsum += sir[1]
                    boutsum += sir[2]
                }
                if (i < hm) {
                    yp += w
                }
            }
            yi = x
            var stackpointer = radius
            for (y in 0 until h) {
                pix[yi] = (0xff000000.toInt() and pix[yi]) or
                    (dv[rsum] shl 16) or
                    (dv[gsum] shl 8) or
                    dv[bsum]

                rsum -= routsum
                gsum -= goutsum
                bsum -= boutsum

                val stackstart = stackpointer - radius + div
                val sir = stack[stackstart % div]

                routsum -= sir[0]
                goutsum -= sir[1]
                boutsum -= sir[2]

                if (x == 0) {
                    vmin[y] = min(y + r1, hm) * w
                }
                val p = x + vmin[y]

                sir[0] = r[p]
                sir[1] = g[p]
                sir[2] = b[p]

                rinsum += sir[0]
                ginsum += sir[1]
                binsum += sir[2]

                rsum += rinsum
                gsum += ginsum
                bsum += binsum

                stackpointer = (stackpointer + 1) % div
                val sir2 = stack[stackpointer]

                routsum += sir2[0]
                goutsum += sir2[1]
                boutsum += sir2[2]

                rinsum -= sir2[0]
                ginsum -= sir2[1]
                binsum -= sir2[2]

                yi += w
            }
        }

        bitmap.setPixels(pix, 0, w, 0, 0, w, h)
        return bitmap
    }
}
