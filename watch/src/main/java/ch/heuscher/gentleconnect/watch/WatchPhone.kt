package ch.heuscher.gentleconnect.watch

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * The watch talking back to the phone.
 *
 * Sent to every connected node rather than to a chosen one. There is only ever one phone in her
 * case, and picking "the right" node out of a list is a decision with a wrong answer — a message
 * sent to the wrong node is simply not delivered, and nothing anywhere reports it. Sending to all
 * of them costs one wasted message on a setup she does not have.
 *
 * Returns whether anything was reachable, because that is the one failure the watch can honestly
 * tell her about: the phone is off, or out of range, and pressing the face again will not help.
 */
class WatchPhone(private val context: Context) {

    suspend fun ring(face: WatchFace): Boolean =
        send(WearMessages.CALL_PATH, WearMessages.callPayload(face.number, face.threemaId))

    suspend fun findPhone(): Boolean = send(WearMessages.FIND_PHONE_PATH, "")

    private suspend fun send(path: String, payload: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val nodes = Wearable.getNodeClient(context).connectedNodes.await()
            if (nodes.isEmpty()) return@withContext false

            val messageClient = Wearable.getMessageClient(context)
            var reached = false
            nodes.forEach { node ->
                try {
                    messageClient.sendMessage(node.id, path, payload.toByteArray()).await()
                    reached = true
                } catch (e: Exception) {
                    Log.w(TAG, "Could not reach ${node.displayName}", e)
                }
            }
            reached
        } catch (e: Exception) {
            Log.w(TAG, "Could not find the phone at all", e)
            false
        }
    }

    private companion object {
        const val TAG = "WatchPhone"
    }
}
