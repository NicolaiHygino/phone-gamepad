package dev.androidcontroller.pad

import android.content.Context

data class Settings(
    val host: String = "",
    val port: Int = 9876,
    /** 0 = Player 1, 1 = Player 2 */
    val slot: Int = 0,
    /** Shared key (see server.py --new-secret); empty when the server does not ask for one. */
    val key: String = "",
    val layout: LayoutPrefs = LayoutPrefs(),
)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("pad", Context.MODE_PRIVATE)

    fun load() = Settings(
        host = prefs.getString("host", "") ?: "",
        port = prefs.getInt("port", 9876),
        slot = prefs.getInt("slot", 0),
        key = prefs.getString("key", "") ?: "",
        layout = LayoutPrefs(
            buttonScale = prefs.getFloat("buttonScale", 1f),
            dpadScale = prefs.getFloat("dpadScale", 1f),
            mirrored = prefs.getBoolean("mirrored", false),
        ),
    )

    fun save(s: Settings) {
        prefs.edit()
            .putString("host", s.host)
            .putInt("port", s.port)
            .putInt("slot", s.slot)
            .putString("key", s.key)
            .putFloat("buttonScale", s.layout.buttonScale)
            .putFloat("dpadScale", s.layout.dpadScale)
            .putBoolean("mirrored", s.layout.mirrored)
            .apply()
    }
}
