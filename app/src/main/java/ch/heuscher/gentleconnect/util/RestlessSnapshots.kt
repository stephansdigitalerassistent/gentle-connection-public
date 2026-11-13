package ch.heuscher.gentleconnect.util

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.PixelCopy
import ch.heuscher.gentleconnect.model.AgitationPolicy
import ch.heuscher.gentleconnect.model.AgitationTrigger
import ch.heuscher.gentleconnect.model.RestlessSnapshotPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Captures silent snapshots of the app's own window when restlessness is detected.
 *
 * Only the app's own window is captured (no permission, no prompt, she never sees it);
 * other apps/system dialogs are not in the picture; the pictures contain her messages,
 * so they stay on the phone in the app's files dir, capped at 30 files / 7 days.
 */
object RestlessSnapshots {

    private const val TAG = "RestlessSnapshots"
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var latestTrailFile: File? = null
    private var latestTriggerAt: Long = 0L
    private var pendingResolution: Pair<String, Long>? = null

    /**
     * Appends a resolution line to the latest trail sidecar if restlessness was settled
     * by reaching a constructive goal within [AgitationPolicy.RESOLVE_MS].
     */
    @Synchronized
    fun markResolved(goal: String, now: Long = System.currentTimeMillis()) {
        val triggerAt = latestTriggerAt
        val file = latestTrailFile
        if (file != null && triggerAt > 0L) {
            if (now - triggerAt in 0L..AgitationPolicy.RESOLVE_MS) {
                val line = formatResolutionLine(goal, now, triggerAt)
                scope.launch {
                    synchronized(this@RestlessSnapshots) {
                        appendResolutionLine(file, line)
                    }
                }
            }
        } else if (triggerAt == 0L || now - triggerAt in 0L..AgitationPolicy.RESOLVE_MS) {
            pendingResolution = goal to now
        }
    }

    private fun formatResolutionLine(goal: String, now: Long, triggerAt: Long): String {
        val seconds = ((now - triggerAt) / 1000L).coerceAtLeast(0L)
        val clock = synchronized(timeFormat) { timeFormat.format(Date(now)) }
        return "$clock resolved $goal after $seconds s"
    }

    private fun appendResolutionLine(file: File, line: String) {
        runCatching {
            file.appendText("\n$line")
            Log.i(TAG, "Appended resolution to trail ${file.name}: $line")
        }.onFailure {
            Log.w(TAG, "Could not append resolution to trail ${file.name}", it)
        }
    }

    fun capture(activity: Activity, trigger: AgitationTrigger, index: Int) {
        if (activity.isFinishing || activity.isDestroyed) return
        val window = activity.window ?: return
        val decorView = window.decorView
        val width = decorView.width
        val height = decorView.height
        if (width <= 0 || height <= 0) return

        val appContext = activity.applicationContext

        if (index == 0) {
            val triggerTime = System.currentTimeMillis()
            synchronized(this) {
                latestTriggerAt = triggerTime
                latestTrailFile = null
                val pending = pendingResolution
                if (pending != null && pending.second < triggerTime) {
                    pendingResolution = null
                }
            }
        }

        runCatching {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            PixelCopy.request(
                window,
                bitmap,
                PixelCopy.OnPixelCopyFinishedListener { copyResult ->
                    if (copyResult != PixelCopy.SUCCESS) {
                        bitmap.recycle()
                        Log.w(TAG, "PixelCopy failed with result: $copyResult")
                        return@OnPixelCopyFinishedListener
                    }
                    scope.launch {
                        saveSnapshot(appContext, bitmap, trigger, index)
                    }
                },
                Handler(Looper.getMainLooper())
            )
        }.onFailure {
            Log.w(TAG, "could not request restless snapshot", it)
        }
    }

    private fun saveSnapshot(
        context: Context,
        bitmap: Bitmap,
        trigger: AgitationTrigger,
        index: Int
    ) {
        runCatching {
            var scaledBitmap: Bitmap? = null
            try {
                val origWidth = bitmap.width
                val origHeight = bitmap.height
                val maxEdge = maxOf(origWidth, origHeight)
                val toSave = if (maxEdge > RestlessSnapshotPolicy.MAX_EDGE_PX) {
                    val scale = RestlessSnapshotPolicy.MAX_EDGE_PX.toFloat() / maxEdge.toFloat()
                    val targetWidth = (origWidth.toFloat() * scale).roundToInt().coerceAtLeast(1)
                    val targetHeight = (origHeight.toFloat() * scale).roundToInt().coerceAtLeast(1)
                    Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true).also {
                        scaledBitmap = it
                    }
                } else {
                    bitmap
                }

                val base = context.getExternalFilesDir(null) ?: context.filesDir
                val dir = File(base, "restless")
                if (!dir.exists() && !dir.mkdirs()) {
                    Log.w(TAG, "could not create directory: $dir")
                    return@runCatching
                }

                val now = System.currentTimeMillis()
                val fileName = RestlessSnapshotPolicy.fileName(now, trigger, index)
                val targetFile = File(dir, fileName)
                val tmpFile = File(dir, "$fileName.tmp")

                try {
                    FileOutputStream(tmpFile).use { out ->
                        toSave.compress(Bitmap.CompressFormat.JPEG, 70, out)
                    }

                    if (!tmpFile.renameTo(targetFile)) {
                        tmpFile.delete()
                        Log.w(TAG, "could not rename $tmpFile to $targetFile")
                        return@runCatching
                    }
                } catch (t: Throwable) {
                    if (tmpFile.exists()) {
                        tmpFile.delete()
                    }
                    throw t
                }

                Log.i(TAG, "Saved restless snapshot $fileName")

                // Alongside the initial snapshot (index 0), write out the recent touch trail so
                // a caregiver or developer investigating confusion or agitation can see exactly what
                // touches happened in the 30 seconds leading up to the trigger (e.g. repeated drags,
                // dead touches on cards, or rapid button presses).
                if (index == 0) {
                    val trailName = RestlessSnapshotPolicy.trailFileName(now, trigger)
                    val trailTarget = File(dir, trailName)
                    val trailTmp = File(dir, "$trailName.tmp")
                    try {
                        val trailContent = UiTrace.recentTrail(windowMs = 30_000L, now = now)
                        trailTmp.writeText(trailContent)
                        if (!trailTmp.renameTo(trailTarget)) {
                            trailTmp.delete()
                            Log.w(TAG, "could not rename $trailTmp to $trailTarget")
                        } else {
                            Log.i(TAG, "Saved restless trail $trailName")
                            synchronized(this) {
                                val triggerAt = if (latestTriggerAt > 0L) latestTriggerAt else now
                                latestTrailFile = trailTarget
                                latestTriggerAt = triggerAt
                                val pending = pendingResolution
                                pendingResolution = null
                                if (pending != null) {
                                    val (goal, resNow) = pending
                                    if (resNow - triggerAt in 0L..AgitationPolicy.RESOLVE_MS) {
                                        val line = formatResolutionLine(goal, resNow, triggerAt)
                                        appendResolutionLine(trailTarget, line)
                                    }
                                }
                            }
                        }
                    } catch (e: Throwable) {
                        if (trailTmp.exists()) {
                            trailTmp.delete()
                        }
                        Log.w(TAG, "could not save restless trail", e)
                    }
                }

                val existing = dir.list()?.toList() ?: emptyList()
                for (name in RestlessSnapshotPolicy.filesToDelete(existing, now)) {
                    val fileToDelete = File(dir, name)
                    if (!fileToDelete.delete()) {
                        Log.w(TAG, "could not delete old snapshot or trail $name")
                    }
                }
            } finally {
                bitmap.recycle()
                if (scaledBitmap != null && scaledBitmap !== bitmap) {
                    scaledBitmap?.recycle()
                }
            }
        }.onFailure {
            Log.w(TAG, "could not save restless snapshot", it)
        }
    }
}
