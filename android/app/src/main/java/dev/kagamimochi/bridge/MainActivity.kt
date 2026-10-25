package dev.kagamimochi.bridge

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var host: EditText
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val discovery by lazy { Discovery(this) }

    private val permListener = Shizuku.OnRequestPermissionResultListener { _, _ -> }

    // Refresh the status text once per second
    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        Shizuku.addRequestPermissionResultListener(permListener)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }

        status = TextView(this)
        host = EditText(this).apply {
            hint = "Host override (optional, host or host:port)"
            setText(Config.load(this@MainActivity).host)
        }

        val pair = Button(this).apply {
            text = "Pair new device"
            setOnClickListener {
                val manual = host.text.toString().trim()
                Config.saveHost(this@MainActivity, manual)
                if (manual.isNotEmpty()) {
                    val (h, p) = Config.parseHostPort(manual)
                    askCode(h, p, h)
                } else {
                    showDeviceList()
                }
            }
        }
        val grant = Button(this).apply {
            text = "Grant Shizuku permission"
            setOnClickListener { if (Shizuku.pingBinder()) Shizuku.requestPermission(0) }
        }
        val start = Button(this).apply {
            text = "Start"
            setOnClickListener {
                Config.saveHost(this@MainActivity, host.text.toString().trim())
                if (Config.load(this@MainActivity).pairing == null) {
                    Toast.makeText(this@MainActivity, "Pair a device first", Toast.LENGTH_SHORT).show()
                } else {
                    restartService()
                }
            }
        }
        val stop = Button(this).apply {
            text = "Stop"
            setOnClickListener { stopService(Intent(this@MainActivity, SyncService::class.java)) }
        }

        listOf(status, host, pair, grant, start, stop).forEach { root.addView(it) }
        setContentView(root)
    }

    /** Lists daemons found on the network; tapping one asks for the pairing code. */
    private fun showDeviceList() {
        val found = mutableListOf<Found>()
        val labels = mutableListOf<String>()
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        val list = ListView(this).apply { this.adapter = adapter }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Searching for devices...")
            .setView(list)
            .setNegativeButton("Cancel", null)
            .create()

        val stop = discovery.scan { f ->
            runOnUiThread {
                if (found.none { it.name == f.name }) {
                    found += f
                    labels += "${f.name}  (${f.host})"
                    adapter.notifyDataSetChanged()
                    dialog.setTitle("Select a device")
                }
            }
        }
        dialog.setOnDismissListener { stop() }
        list.setOnItemClickListener { _, _, i, _ ->
            val f = found[i]
            dialog.dismiss()
            askCode(f.host, f.port, f.name)
        }
        dialog.show()
    }

    private fun askCode(host: String, port: Int, name: String) {
        val input = EditText(this).apply {
            hint = "XXXX-XXXX"
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        AlertDialog.Builder(this)
            .setTitle("Pair with $name")
            .setMessage("Enter the code shown on the Linux screen. If no code is shown, run: bridge_daemon.py --pair")
            .setView(input)
            .setPositiveButton("Pair") { _, _ -> doPair(host, port, name, input.text.toString()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun doPair(host: String, port: Int, name: String, code: String) {
        Toast.makeText(this, "Pairing...", Toast.LENGTH_SHORT).show()
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                PairingClient.pair(host, port, name, code, Build.MODEL)
            }
            result.onSuccess {
                Config.savePairing(this@MainActivity, it)
                restartService()
                Toast.makeText(this@MainActivity, "Paired with $name", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(this@MainActivity, "Pairing failed: ${it.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun restartService() {
        stopService(Intent(this, SyncService::class.java))
        startForegroundService(Intent(this, SyncService::class.java))
    }

    private fun refresh() {
        val shizuku = when {
            !Shizuku.pingBinder() -> "not running"
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> "permission not granted"
            else -> "ready"
        }
        val paired = Config.load(this).pairing?.serverName?.ifEmpty { "(unnamed)" } ?: "not paired"
        status.text = "Paired with: $paired\n" +
            "Shizuku: $shizuku\n" +
            "Shizuku service: ${if (Status.shizukuServiceBound) "bound" else "not bound"}\n" +
            "Connection: ${Status.connection}\n" +
            "Last event: ${Status.lastEvent}"
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onDestroy() {
        scope.cancel()
        Shizuku.removeRequestPermissionResultListener(permListener)
        super.onDestroy()
    }
}
