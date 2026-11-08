package fr.mescours.app.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File

object ImageUtils {
    private const val MAX_SIDE = 2000

    /** Réduit, redresse (EXIF) et compresse une photo de cahier pour l'envoi à l'IA. */
    fun compressForUpload(file: File): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return file.readBytes()

        val scale = MAX_SIDE.toFloat() / maxOf(decoded.width, decoded.height)
        val matrix = Matrix()
        if (scale < 1f) matrix.postScale(scale, scale)
        val rotation = runCatching {
            when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)
        if (rotation != 0f) matrix.postRotate(rotation)

        val out = if (matrix.isIdentity) decoded
        else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        return ByteArrayOutputStream().use { bos ->
            out.compress(Bitmap.CompressFormat.JPEG, 85, bos)
            bos.toByteArray()
        }
    }
}
