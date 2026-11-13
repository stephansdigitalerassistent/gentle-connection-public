package ch.heuscher.gentleconnect.telecom

import android.content.Context

object RingerSettings {
    fun getSilenceTimeoutSeconds(context: Context): Int = try {
        context.getSharedPreferences("gentle_connection_prefs", Context.MODE_PRIVATE)
            .getInt("ringSilenceTimeoutSeconds", 0)
    } catch (e: Exception) {
        0
    }

    fun rememberSilenceTimeout(context: Context, seconds: Int) {
        try {
            context.getSharedPreferences("gentle_connection_prefs", Context.MODE_PRIVATE)
                .edit()
                .putInt("ringSilenceTimeoutSeconds", seconds)
                .apply()
        } catch (e: Exception) {}
    }
}
