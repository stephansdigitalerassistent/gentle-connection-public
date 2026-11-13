package ch.heuscher.gentleconnect.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import ch.heuscher.gentleconnect.model.OwnPhotoSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/**
 * Prepares and persists photographs taken by her using the camera.
 *
 * ## Privacy & EXIF stripping
 * Re-encoding the image through an Android [Bitmap] is deliberate and critical: it drops ALL EXIF
 * metadata, including the precise GPS coordinates of her home, camera sensor serial numbers,
 * exposure details, and device timestamps. When sending a picture to a family member or friend over
 * Threema or WhatsApp, she should share only the visual moment, never her private geographic location.
 *
 * ## Memory safety
 * High-resolution phone cameras (e.g. 50 MP) would quickly trigger OutOfMemory errors if decoded
 * naively into memory. By first reading bounds and computing [BitmapFactory.Options.inSampleSize],
 * we downsample the raw capture before full decoding, keeping memory consumption low and safe.
 *
 * Saved images are compressed as JPEG (quality 85) into `File(context.filesDir, "own_photos/<UUID>.jpg")`.
 * The temporary raw capture file is always deleted after processing. Runs on [Dispatchers.IO].
 */
object OwnPhotoStore {

    /**
     * Prepares a raw camera JPEG for sending:
     * 1. Reads orientation from EXIF via [android.media.ExifInterface].
     * 2. Decodes bounds and computes [BitmapFactory.Options.inSampleSize] to avoid OOM.
     * 3. Rotates according to EXIF and scales to [OwnPhotoSize.target].
     * 4. Compresses as JPEG (85% quality) into an app-private file under `filesDir/own_photos/`.
     * 5. Deletes the [rawFile].
     *
     * @return the absolute path of the prepared, EXIF-stripped JPEG file.
     */
    suspend fun prepare(context: Context, rawFile: File): String = withContext(Dispatchers.IO) {
        try {
            if (!rawFile.exists()) {
                throw IOException("Raw photo file does not exist: ${rawFile.absolutePath}")
            }

            // 1. Read EXIF orientation before decoding
            val exif = ExifInterface(rawFile.absolutePath)
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
            val rotationDegrees = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }

            // 2. Read dimensions only
            val boundsOptions = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(rawFile.absolutePath, boundsOptions)
            val rawW = boundsOptions.outWidth
            val rawH = boundsOptions.outHeight
            if (rawW <= 0 || rawH <= 0) {
                throw IOException("Unable to decode bounds of raw camera file")
            }

            val isRotatedSideways = rotationDegrees == 90f || rotationDegrees == 270f
            val orientedW = if (isRotatedSideways) rawH else rawW
            val orientedH = if (isRotatedSideways) rawW else rawH

            val (targetW, targetH) = OwnPhotoSize.target(orientedW, orientedH)

            // 3. Compute inSampleSize so high-resolution cameras don't OOM
            var sampleSize = 1
            val maxRaw = maxOf(rawW, rawH)
            while (maxRaw / (sampleSize * 2) >= OwnPhotoSize.LONG_EDGE) {
                sampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
            }
            val decoded = BitmapFactory.decodeFile(rawFile.absolutePath, decodeOptions)
                ?: throw IOException("Failed to decode raw photo bitmap")

            // 4. Rotate according to EXIF
            val rotated = if (rotationDegrees != 0f) {
                val matrix = Matrix().apply { postRotate(rotationDegrees) }
                val r = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                if (r !== decoded) {
                    decoded.recycle()
                }
                r
            } else {
                decoded
            }

            // 5. Scale to target bounds
            val finalBitmap = if (rotated.width != targetW || rotated.height != targetH) {
                val scaled = Bitmap.createScaledBitmap(rotated, targetW, targetH, true)
                if (scaled !== rotated) {
                    rotated.recycle()
                }
                scaled
            } else {
                rotated
            }

            // 6. Write out EXIF-stripped JPEG into filesDir/own_photos/<UUID>.jpg
            val outDir = File(context.filesDir, "own_photos")
            if (!outDir.exists()) {
                outDir.mkdirs()
            }
            val outFile = File(outDir, "${UUID.randomUUID()}.jpg")
            FileOutputStream(outFile).use { fos ->
                finalBitmap.compress(Bitmap.CompressFormat.JPEG, 85, fos)
            }
            finalBitmap.recycle()

            outFile.absolutePath
        } finally {
            if (rawFile.exists()) {
                rawFile.delete()
            }
        }
    }

    /** Alias for [prepare] for callers using store semantics. */
    suspend fun store(context: Context, rawFile: File): String = prepare(context, rawFile)
}
