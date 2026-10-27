package dev.kagamimochi.bridge

import android.content.Context

/**
 * host:    optional manual address override ("host" or "host:port"); empty means auto-discovery
 * pairing: result of pairing, or null when this phone is not paired yet
 */
data class Config(val host: String, val pairing: Pairing?) {
    companion object {
        private fun prefs(c: Context) = c.getSharedPreferences("bridge", Context.MODE_PRIVATE)

        fun load(c: Context): Config {
            val p = prefs(c)
            val fp = p.getString("fp", null)
            val deviceId = p.getString("device_id", null)
            val token = p.getString("token", null)
            val pairing = if (fp != null && deviceId != null && token != null) {
                Pairing(fp, deviceId, token, p.getString("server_name", "") ?: "")
            } else null
            return Config(p.getString("host", "") ?: "", pairing)
        }

        /** False after the user pressed Stop; the boot receiver respects it. Defaults to true. */
        fun isServiceEnabled(c: Context): Boolean = prefs(c).getBoolean("service_enabled", true)

        fun setServiceEnabled(c: Context, enabled: Boolean) {
            prefs(c).edit().putBoolean("service_enabled", enabled).apply()
        }

        fun saveHost(c: Context, host: String) {
            prefs(c).edit().putString("host", host).apply()
        }

        fun savePairing(c: Context, pairing: Pairing) {
            prefs(c).edit()
                .putString("fp", pairing.fingerprint)
                .putString("device_id", pairing.deviceId)
                .putString("token", pairing.token)
                .putString("server_name", pairing.serverName)
                .apply()
        }

        /** "host" or "host:port" -> host and port (default 8765). */
        fun parseHostPort(text: String): Pair<String, Int> {
            val parts = text.trim().split(":")
            return parts[0] to (parts.getOrNull(1)?.toIntOrNull() ?: 8765)
        }
    }
}
