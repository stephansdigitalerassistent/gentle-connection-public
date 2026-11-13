package ch.heuscher.gentleconnect.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import ch.heuscher.gentleconnect.model.Contact
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import java.io.ByteArrayOutputStream

/**
 * Her favourites, put on her wrist.
 *
 * The watch is the same idea as the home screen and nothing more: the handful of people she
 * actually rings, as faces, and a tap places the call. It carries no messages, no mail and no
 * settings — a watch is a glance and a press, and everything else on it would be something to
 * get lost in on a screen the size of a stamp.
 *
 * Sent as a *data item* rather than a message. A message is delivered or it is not; a data item
 * is held by the Wear layer and handed to the watch the moment it comes back into range. Her
 * watch spends its nights on a shelf, and a favourite starred while it was there must still be
 * on it in the morning.
 *
 * Nothing here fails loudly. A phone with no watch paired is the ordinary case, not a fault: the
 * Wear layer simply holds the item and nobody ever collects it.
 */
class WearSyncManager(private val context: Context) {

    private val dataClient by lazy { Wearable.getDataClient(context) }

    /**
     * @param favourites in the order the home screen draws them, which is the order the watch
     *   will draw them. She knows her son is the second face; he must be the second face on both.
     */
    fun syncFavourites(favourites: List<Contact>) {
        try {
            val request = PutDataMapRequest.create(WearMessages.FACES_PATH)

            val faces = favourites.take(FACES_AT_MOST).map { contact ->
                DataMap().apply {
                    putString(WearMessages.FACE_ID, contact.id)
                    putString(WearMessages.FACE_NAME, contact.name)
                    putString(WearMessages.FACE_NUMBER, contact.phoneNumber)
                    putString(WearMessages.FACE_THREEMA_ID, contact.threemaId.orEmpty())
                    photoAsset(contact.photoUri)?.let { putAsset(WearMessages.FACE_PHOTO, it) }
                }
            }

            request.dataMap.putDataMapArrayList(WearMessages.FACES_KEY, ArrayList(faces))
            // See WearMessages.UPDATED_AT_KEY: without something that changes, an identical list
            // is dropped as a no-op and a re-paired watch never receives anything at all.
            request.dataMap.putLong(WearMessages.UPDATED_AT_KEY, System.currentTimeMillis())

            dataClient.putDataItem(request.asPutDataRequest().setUrgent())
                .addOnFailureListener { e -> Log.w(TAG, "Faces did not reach the watch", e) }
        } catch (e: Exception) {
            Log.w(TAG, "Could not put the faces together for the watch", e)
        }
    }

    /**
     * A contact photograph, small enough to send.
     *
     * Two hundred pixels square. The watch screen is smaller than that and the Wear layer is a
     * Bluetooth link — sending her daughter's full-resolution portrait would take long enough
     * that the faces arrive minutes after she looked.
     */
    private fun photoAsset(photoUri: String?): Asset? {
        if (photoUri.isNullOrBlank()) return null
        return try {
            val bitmap = context.contentResolver.openInputStream(Uri.parse(photoUri))?.use {
                BitmapFactory.decodeStream(it)
            } ?: return null

            val scaled = Bitmap.createScaledBitmap(bitmap, PHOTO_PIXELS, PHOTO_PIXELS, true)
            if (scaled != bitmap) bitmap.recycle()

            val bytes = ByteArrayOutputStream().also {
                scaled.compress(Bitmap.CompressFormat.PNG, 100, it)
            }.toByteArray()
            scaled.recycle()

            Asset.createFromBytes(bytes)
        } catch (e: Exception) {
            // A face that will not load is a face the watch draws as a person-shaped mark, which
            // is what it does for everybody with no photograph anyway.
            Log.w(TAG, "Could not read a contact photo for the watch", e)
            null
        }
    }

    private companion object {
        const val TAG = "WearSync"

        /**
         * How many faces the watch is given.
         *
         * It is a wrist, not an address book. Past about this many the list has to be scrolled,
         * and scrolling to find somebody is the thing the watch is not for — that is what the
         * phone in her pocket is.
         */
        const val FACES_AT_MOST = 12

        const val PHOTO_PIXELS = 200
    }
}
