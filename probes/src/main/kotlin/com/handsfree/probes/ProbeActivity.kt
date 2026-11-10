package com.handsfree.probes

import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.telecom.TelecomManager
import android.view.WindowManager
import android.widget.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine

open class ProbeActivity : Activity() {
    private val controller get() = (application as ProbesApp).controller
    private val ui = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var screen: String? = null
    private lateinit var body: LinearLayout
    private var lineJob: Job? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        show(intent.getStringExtra("experiment"))
    }
    private fun label(text: String) { body.addView(TextView(this).apply { this.text = text; textSize = 16f; setPadding(8, 8, 8, 8) }) }
    private fun button(text: String, tag: String = text, action: () -> Unit) {
        body.addView(Button(this).apply { this.text = text; this.tag = tag; setOnClickListener { action() } })
    }
    private fun field(hint: String, value: String = ""): EditText = EditText(this).also {
        it.hint = hint; it.setText(value); body.addView(it)
    }
    fun show(id: String?) {
        lineJob?.cancel()
        screen = id
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 16, 16, 16) }
        setContentView(ScrollView(this).apply { addView(body) })
        val experiment = id?.let(Experiments::find)
        if (experiment == null) {
            label("Probes — device experiments")
            label("Diagnostic build. Results require real-device validation. Each experiment requests its own consent and permissions.")
            button("Export CSV and JSON") { controller.exportResults() }
            label("Exports: Files > Downloads > probes (CSV and JSON).")
            val exportStatus = TextView(this).also(body::addView)
            lineJob = ui.launch { controller.repo.lines.collect { lines -> exportStatus.text = lines.filter { it.startsWith("export / ") }.takeLast(3).joinToString("\n") } }
            Experiments.all.forEach { item -> button("${item.id}: ${item.title}", item.id) { show(item.id) } }
            return
        }
        button("Experiments") { show(null) }
        label("${experiment.id}: ${experiment.title}")
        label(experiment.steps)
        val consents = (experiment.required + experiment.optional).distinct().filter { it != android.Manifest.permission.POST_NOTIFICATIONS || android.os.Build.VERSION.SDK_INT >= 33 }.associateWith { permission ->
            CheckBox(this).apply { text = "I consent to using ${permission.substringAfterLast('.')}"; isChecked = permission in controller.consents; setOnCheckedChangeListener { _, checked -> controller.setConsent(permission, checked) }; body.addView(this) }
        }
        button("Request selected permissions") {
            val selected = consents.filterValues { it.isChecked }.keys
            val missing = selected.filter { it != ProbeNotificationListener.ACCESS && checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 100)
            else controller.repo.emit(experiment.id, "permissions", "none_missing")
        }
        if (id == "T5" || id == "T6") button("Notification access settings") { launchSettings(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        button("Assistant system settings") { launchSettings(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }
        if (id == "T1b") button("Request call screening role") {
            val roles = getSystemService(RoleManager::class.java)
            if (roles.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) launchSettings(roles.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING))
            else controller.repo.emit(id, "role", "screening_unavailable", outcome = "UNSUPPORTED")
        }
        val number = field("Test phone number", controller.config.number)
        val query = field("Media search", controller.config.query)
        val destination = field("Navigation destination", controller.config.destination)
        val appPackage = field("App package to launch", controller.config.appPackage)
        if (id == "T6") {
            val players = controller.integrations.players()
            label("Declared MEDIA_PLAY_FROM_SEARCH players: ${players.joinToString()}")
            if (players.isNotEmpty()) {
                val picker = Spinner(this).apply {
                    adapter = ArrayAdapter(this@ProbeActivity, android.R.layout.simple_spinner_dropdown_item, players)
                    onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, itemId: Long) { appPackage.setText(players[position]); label("Player version: ${controller.repo.packageVersion(players[position])}") }
                        override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                    }
                    body.addView(this)
                }
            }
        }
        val cold = CheckBox(this).apply { text = "Cold player trial (close the player manually first)"; isChecked = true; if (id == "T6") body.addView(this) }
        val useController = CheckBox(this).apply { text = "Use platform MediaController instead of explicit intent"; if (id == "T6") body.addView(this) }
        val captureButtons = CheckBox(this).apply { text = "Activate probe media session (changes button routing)"; if (id == "T11") body.addView(this) }
        val account = field("Outgoing phone account index (0, 1, ...)", "0")
        button("List outgoing phone accounts") {
            if (consents[android.Manifest.permission.READ_PHONE_STATE]?.isChecked == true && checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                try {
                    getSystemService(TelecomManager::class.java).callCapablePhoneAccounts.forEachIndexed { i, handle ->
                        val phoneAccount = getSystemService(TelecomManager::class.java).getPhoneAccount(handle)
                        label("$i: ${phoneAccount?.label ?: "unknown"}")
                    }
                } catch (_: SecurityException) { controller.repo.emit(id, "account", "permission_denied", outcome = "PERMISSION_DENIED") }
            } else controller.repo.emit(id, "account", "permission_denied", outcome = "PERMISSION_DENIED")
        }
        val personal = CheckBox(this).apply { text = "Allow personal results on lock for this test"; body.addView(this) }
        fun config() = ProbeConfig(number.text.toString(), query.text.toString(), destination.text.toString(), appPackage.text.toString(), account.text.toString().toIntOrNull() ?: 0, personal.isChecked, mediaCold = cold.isChecked, mediaMethod = if (useController.isChecked) "controller" else "intent", captureButtons = captureButtons.isChecked)
        button("Start", "start") {
            val config = config()
            if (id == "T9") {
                controller.start(id, config)
                val roles = getSystemService(RoleManager::class.java)
                val available = roles.isRoleAvailable(RoleManager.ROLE_ASSISTANT)
                controller.repo.emit(id, "role", "available", available.toString())
                if (available) {
                    try { startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT), 109) }
                    catch (error: RuntimeException) { controller.repo.emit(id, "role", "request_failed", error.javaClass.simpleName, "UNSUPPORTED") }
                }
            } else controller.start(id, config)
        }
        if (id == "T5") {
            button("Send WhatsApp Answer action") { controller.integrations.whatsapp("Answer") }
            button("Send WhatsApp Decline action") { controller.integrations.whatsapp("Decline") }
        }
        if (id == "T6") {
            val pickerShown = CheckBox(this).apply { text = "A player picker appeared"; body.addView(this) }
            val titleMatch = CheckBox(this).apply { text = "The resulting title matched my query"; body.addView(this) }
            button("Save media observation") { controller.repo.emit(id, "manual", "media_result", "pickerShown=${pickerShown.isChecked};titleMatched=${titleMatch.isChecked}", "MANUAL") }
        }
        button("Export CSV and JSON") { controller.exportResults() }
        button("Stop / Cancel") { controller.stop("user") }
        if (id == "T4") {
            val understood = field("Understood sentence count (0 to 10)").apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER }
            button("Save intelligibility count") {
                val count = understood.text.toString().toIntOrNull()
                if (count != null && count in 0..10) controller.repo.emit(id, "manual", "understood_of_ten", count.toString(), "MANUAL")
                else label("Enter a count from 0 to 10")
            }
        }
        val observation = field("Manual observation")
        button("Save observation") { controller.repo.emit(id, "manual", "observation", observation.text.toString(), "MANUAL") }
        val state = TextView(this).apply { tag = "status"; setPadding(8, 8, 8, 8); body.addView(this) }
        lineJob = ui.launch { combine(controller.repo.lines, controller.repo.spoken) { lines, spoken -> lines.joinToString("\n") + "\n" + spoken }.collect { state.text = it } }
    }
    private fun launchSettings(intent: Intent) {
        try { startActivity(intent) }
        catch (_: android.content.ActivityNotFoundException) { controller.repo.emit(screen ?: "setup", "settings", "unavailable", outcome = "UNSUPPORTED") }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permissions.forEachIndexed { i, p -> controller.repo.emit(screen ?: "setup", "permission", p.substringAfterLast('.'), (grantResults.getOrNull(i) == PackageManager.PERMISSION_GRANTED).toString()) }
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 109) controller.repo.emit("T9", "role", "dialog_result", resultCode.toString())
    }
    override fun onDestroy() { lineJob?.cancel(); ui.cancel(); super.onDestroy() }
}
class LockProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        setShowWhenLocked(true)
        setContentView(TextView(this).apply { text = "Probes keyguard callback received. Use the assistant session controls." })
    }
}
