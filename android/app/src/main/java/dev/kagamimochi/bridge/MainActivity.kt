package dev.kagamimochi.bridge

import android.Manifest
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.ColorRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private lateinit var statusDot: View
    private lateinit var connectionValue: TextView
    private lateinit var pairedValue: TextView
    private lateinit var shizukuValue: TextView
    private lateinit var shizukuServiceValue: TextView
    private lateinit var retryValue: TextView
    private lateinit var mediaValue: TextView
    private lateinit var notificationsValue: TextView
    private lateinit var lastEventValue: TextView
    private lateinit var grant: MaterialButton

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val discovery by lazy { Discovery(this) }

    private val permListener = Shizuku.OnRequestPermissionResultListener { _, _ -> refresh() }

    // Refresh the status once per second
    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // The app draws edge-to-edge, so match the system bar icons to the theme
        val isNight = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isNight
            isAppearanceLightNavigationBars = !isNight
        }

        statusDot = findViewById(R.id.statusDot)
        connectionValue = findViewById(R.id.connectionValue)
        pairedValue = findViewById(R.id.pairedValue)
        shizukuValue = findViewById(R.id.shizukuValue)
        shizukuServiceValue = findViewById(R.id.shizukuServiceValue)
        retryValue = findViewById(R.id.retryValue)
        mediaValue = findViewById(R.id.mediaValue)
        notificationsValue = findViewById(R.id.notificationsValue)
        lastEventValue = findViewById(R.id.lastEventValue)
        grant = findViewById(R.id.grantButton)

        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        Shizuku.addRequestPermissionResultListener(permListener)

        findViewById<MaterialButton>(R.id.pairButton).setOnClickListener { showPairDialog() }
        grant.setOnClickListener { if (Shizuku.pingBinder()) Shizuku.requestPermission(0) }
        findViewById<MaterialButton>(R.id.startButton).setOnClickListener {
            if (Config.load(this@MainActivity).pairing == null) {
                Toast.makeText(this@MainActivity, R.string.pair_first, Toast.LENGTH_SHORT).show()
            } else {
                restartService()
            }
        }
        findViewById<MaterialButton>(R.id.stopButton).setOnClickListener {
            Config.setServiceEnabled(this@MainActivity, false)
            stopService(Intent(this@MainActivity, SyncService::class.java))
        }
    }

    /**
     * Pairing dialog: an optional host override plus the daemons found on the network.
     * Typing an address and pressing "Pair" skips discovery; tapping a device uses it.
     */
    private fun showPairDialog() {
        val content = layoutInflater.inflate(R.layout.dialog_pair, null)
        val hostInput = content.findViewById<TextInputEditText>(R.id.hostInput)
        val list = content.findViewById<ListView>(R.id.deviceList)
        val emptyView = content.findViewById<TextView>(R.id.emptyView)
        hostInput.setText(Config.load(this).host)

        val adapter = DeviceAdapter()
        list.adapter = adapter
        list.emptyView = emptyView

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pair_new_device)
            .setView(content)
            .setPositiveButton(R.string.pair_button, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            // Keep the dialog open when there is nothing to pair with yet
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val manual = hostInput.text?.toString()?.trim().orEmpty()
                if (manual.isEmpty()) {
                    Toast.makeText(this, R.string.pair_enter_host_or_select, Toast.LENGTH_SHORT).show()
                } else {
                    Config.saveHost(this, manual)
                    dialog.dismiss()
                    val (h, p) = Config.parseHostPort(manual)
                    askCode(h, p, h)
                }
            }
        }

        val stop = discovery.scan { f ->
            runOnUiThread {
                if (!isFinishing && !isDestroyed && adapter.items.none { it.name == f.name }) {
                    adapter.add(f)
                    dialog.setTitle(R.string.pair_select_device)
                }
            }
        }
        dialog.setOnDismissListener { stop() }
        list.setOnItemClickListener { _, _, position, _ ->
            val f = adapter.getItem(position)
            dialog.dismiss()
            // A discovered device is reachable at its announced address, so drop any stale override
            Config.saveHost(this, "")
            askCode(f.host, f.port, f.name)
        }
        dialog.show()
    }

    private fun askCode(host: String, port: Int, name: String) {
        val content = layoutInflater.inflate(R.layout.dialog_code, null)
        val codeLayout = content.findViewById<TextInputLayout>(R.id.codeLayout)
        val codeInput = content.findViewById<TextInputEditText>(R.id.codeInput)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.pair_with, name))
            .setView(content)
            .setPositiveButton(R.string.pair_button, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            // Keep the dialog open so a missing code can be reported inline
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val code = codeInput.text?.toString().orEmpty()
                if (code.isBlank()) {
                    codeLayout.error = getString(R.string.pair_code_required)
                } else {
                    dialog.dismiss()
                    doPair(host, port, name, code)
                }
            }
        }
        dialog.show()
    }

    private fun doPair(host: String, port: Int, name: String, code: String) {
        Toast.makeText(this, R.string.pairing, Toast.LENGTH_SHORT).show()
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                PairingClient.pair(host, port, name, code, Build.MODEL)
            }
            result.onSuccess {
                Config.savePairing(this@MainActivity, it)
                restartService()
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.paired_with, name),
                    Toast.LENGTH_SHORT
                ).show()
            }.onFailure {
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.pairing_failed, it.message ?: it.javaClass.simpleName),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun restartService() {
        Config.setServiceEnabled(this, true)
        stopService(Intent(this, SyncService::class.java))
        startForegroundService(Intent(this, SyncService::class.java))
    }

    private fun refresh() {
        val permissionGranted = Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        grant.visibility = if (permissionGranted) View.GONE else View.VISIBLE

        val shizuku = when {
            !Shizuku.pingBinder() -> getString(R.string.shizuku_not_running)
            !permissionGranted -> getString(R.string.shizuku_permission_not_granted)
            else -> getString(R.string.shizuku_ready)
        }
        val paired = Config.load(this).pairing?.serverName?.ifEmpty {
            getString(R.string.status_unnamed)
        } ?: getString(R.string.status_not_paired)

        val connection = Status.connection
        connectionValue.setIfChanged(connection)
        statusDot.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, statusColor(connection))
        )
        pairedValue.setIfChanged(paired)
        shizukuValue.setIfChanged(shizuku)
        shizukuServiceValue.setIfChanged(
            getString(if (Status.shizukuServiceBound) R.string.status_bound else R.string.status_not_bound)
        )
        retryValue.setIfChanged(Status.retry.ifEmpty { getString(R.string.status_none) })
        mediaValue.setIfChanged(Status.media)
        notificationsValue.setIfChanged(Status.notifications)
        lastEventValue.setIfChanged(Status.lastEvent)
    }

    @ColorRes
    private fun statusColor(connection: String): Int = when {
        connection.startsWith("connected") -> R.color.status_connected
        connection.startsWith("failed") || connection.startsWith("closed") -> R.color.status_error
        connection.startsWith("connecting") ||
            connection.startsWith("searching") ||
            connection.startsWith("secure") ||
            connection.startsWith("Linux shut down") -> R.color.status_pending
        else -> R.color.status_idle
    }

    private fun TextView.setIfChanged(value: CharSequence) {
        if (text.toString() != value.toString()) text = value
    }

    private inner class DeviceAdapter : BaseAdapter() {
        val items = mutableListOf<Found>()
        private val inflater = LayoutInflater.from(this@MainActivity)

        fun add(found: Found) {
            items += found
            notifyDataSetChanged()
        }

        override fun getCount(): Int = items.size

        override fun getItem(position: Int): Found = items[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: inflater.inflate(R.layout.item_device, parent, false)
            val found = getItem(position)
            view.findViewById<TextView>(R.id.deviceName).text = found.name
            view.findViewById<TextView>(R.id.deviceHost).text =
                getString(R.string.pair_device_address, found.host, found.port)
            return view
        }
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        scope.cancel()
        Shizuku.removeRequestPermissionResultListener(permListener)
        super.onDestroy()
    }
}
