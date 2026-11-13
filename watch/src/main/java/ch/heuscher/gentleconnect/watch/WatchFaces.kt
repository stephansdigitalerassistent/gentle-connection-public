package ch.heuscher.gentleconnect.watch

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/** One of her people, as the watch holds them. */
data class WatchFace(
    val id: String,
    val name: String,
    val number: String?,
    val threemaId: String?,
    val photo: Bitmap?
)

/**
 * The faces, as they arrive from the phone.
 *
 * Read twice over, and both are needed. The stored data item is read when the screen opens,
 * because the phone may have sent it days ago and there will be no new delivery to wait for; the
 * listener catches a change that happens while she is looking. Only the first of those was there
 * to begin with, and starring somebody new left the watch showing yesterday's people until it was
 * opened again.
 *
 * An empty list is not an error and is never reported as one. A watch that has never been near
 * the phone, and a phone with nobody starred, look the same from here — and the screen says the
 * one true thing either way: there is nobody to ring.
 */
class WatchFaces(private val context: Context) {

    private val dataClient: DataClient by lazy { Wearable.getDataClient(context) }

    /** Whatever the phone has already sent, out of the Wear layer's own store. */
    suspend fun load(): List<WatchFace> = withContext(Dispatchers.IO) {
        try {
            val buffer = dataClient.dataItems.await()
            // Frozen before the buffer is let go, and the buffer let go whatever happens: the
            // items are views onto memory the buffer owns, and reading one afterwards is reading
            // freed memory.
            val item = try {
                buffer.firstOrNull { it.uri.path == WearMessages.FACES_PATH }?.freeze()
            } finally {
                buffer.release()
            }

            item?.let { read(DataMapItem.fromDataItem(it)) }.orEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "Could not read the faces the phone sent", e)
            emptyList()
        }
    }

    /**
     * Turn one data item into people.
     *
     * Photographs are fetched one at a time and a failure only costs that one its picture — the
     * face falls back to a person-shaped mark, and her son is still on the list with his name on
     * him. Losing the whole list because one photograph would not open is the failure worth
     * avoiding here.
     */
    suspend fun read(item: DataMapItem): List<WatchFace> = withContext(Dispatchers.IO) {
        val faces = item.dataMap.getDataMapArrayList(WearMessages.FACES_KEY).orEmpty()
        faces.map { map ->
            WatchFace(
                id = map.getString(WearMessages.FACE_ID).orEmpty(),
                name = map.getString(WearMessages.FACE_NAME).orEmpty(),
                number = map.getString(WearMessages.FACE_NUMBER)?.ifBlank { null },
                threemaId = map.getString(WearMessages.FACE_THREEMA_ID)?.ifBlank { null },
                photo = map.getAsset(WearMessages.FACE_PHOTO)?.let { decode(it) }
            )
        }
    }

    private suspend fun decode(asset: Asset): Bitmap? = try {
        dataClient.getFdForAsset(asset).await().inputStream.use { BitmapFactory.decodeStream(it) }
    } catch (e: Exception) {
        Log.w(TAG, "A face arrived without its picture", e)
        null
    }

    private companion object {
        const val TAG = "WatchFaces"
    }
}
