package dev.androidcontroller.pad

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : ComponentActivity() {
    private lateinit var store: SettingsStore
    private var settings by mutableStateOf(Settings())
    private var wifiLock: WifiManager.WifiLock? = null
    private var useWifiLock = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SettingsStore(this)
        readDevFlags(intent)
        settings = withIntentExtras(intent, store.load())
        connect(settings)
        devDiscover(intent)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                PadScreen(settings) { saved ->
                    store.save(saved)
                    settings = saved
                    connect(saved)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readDevFlags(intent)
        settings = withIntentExtras(intent, settings)
        connect(settings)
        devDiscover(intent)
        acquireWifiLock()
    }

    override fun onStart() {
        super.onStart()
        PadClient.setActive(true)
        acquireWifiLock()
    }

    override fun onStop() {
        PadClient.setActive(false) // release every button and go quiet while in the background
        releaseWifiLock()
        super.onStop()
    }

    /**
     * Keeps the Wi-Fi radio in its low-latency mode while the pad is in front. Without it Android may
     * put the radio to sleep between packets, which shows up as latency spikes. Only effective while
     * the app is in the foreground with the screen on, which is exactly when it is used.
     */
    private fun acquireWifiLock() {
        releaseWifiLock()
        if (!useWifiLock) return
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiLock = wifi.createWifiLock(mode, "pad-controller").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWifiLock() {
        wifiLock?.takeIf { it.isHeld }?.release()
        wifiLock = null
    }

    /** Dev only: `--ez discover true` scans the LAN and logs what answers (logcat tag Discovery). */
    private fun devDiscover(intent: Intent?) {
        if (intent?.getBooleanExtra("discover", false) != true) return
        val port = settings.port
        Thread {
            val found = Discovery.scan(port)
            if (found.isEmpty()) Log.d("Discovery", "no server answered on port $port")
            found.forEach { Log.d("Discovery", "found '${it.name}' at ${it.host}:${it.port} (${it.players} players)") }
        }.start()
    }

    /** Dev only: `--ez lowlatency false` disables the Wi-Fi lock, to compare latency with and without. */
    private fun readDevFlags(intent: Intent?) {
        if (intent?.hasExtra("lowlatency") == true) {
            useWifiLock = intent.getBooleanExtra("lowlatency", true)
        }
    }

    private fun connect(s: Settings) {
        if (s.host.isNotBlank()) PadClient.configure(s.host, s.port, s.slot, s.key)
    }

    /**
     * Development shortcut so the target can be set without typing on the phone:
     * `adb shell am start -n dev.androidcontroller.pad/.MainActivity --es host 192.168.1.10 --ei slot 0`
     * (`--es key ABCD-EFGH-JKLM` sets the shared key, `--es key ""` clears it)
     */
    private fun withIntentExtras(intent: Intent?, current: Settings): Settings {
        val host = intent?.getStringExtra("host") ?: return current
        val updated = current.copy(
            host = host,
            port = intent.getIntExtra("port", current.port),
            slot = intent.getIntExtra("slot", current.slot),
            key = intent.getStringExtra("key") ?: current.key,
        )
        store.save(updated)
        return updated
    }
}
