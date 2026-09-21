package com.sessionaiagent.app

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

enum class Step {
    Welcome, Connect, Mnemonic, OwnerId, ApiKey, Engine, Model, Install, Done
}

data class UiState(
    val step: Step = Step.Welcome,
    val host: String = "",
    val port: String = "22",
    val user: String = "root",
    val password: String = "",
    val fingerprint: String = "",
    val fingerprintKnown: Boolean = false,
    val connected: Boolean = false,
    val mnemonic: String = "",
    val ownerId: String = "",
    val apiKey: String = "",
    val engine: String = "openclaw",
    val model: String = "",
    val models: List<String> = emptyList(),
    val modelsFromCache: Boolean = false,
    val log: List<String> = emptyList(),
    val botId: String = "",
    val error: String = "",
    val busy: Boolean = false,
    val busyLabel: String = "",
    val progress: Int = -1,
    val progressLabel: String = "",
    val actionMode: String = "install",
    val manageModels: Boolean = false,
    val manageResult: String = "",
    val uninstalled: Boolean = false,
)

class WizardViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("saa", Context.MODE_PRIVATE)
    private val ssh = SshManager()

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private fun hostKey() = "hostkey_${_state.value.host.trim()}_${_state.value.port.trim()}"

    private fun keyHash(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(16)

    private fun modelsCacheKey() =
        "models_${_state.value.host.trim()}_${keyHash(_state.value.apiKey.trim())}"

    fun set(block: (UiState) -> UiState) = _state.update(block)

    fun go(step: Step) = _state.update {
        it.copy(
            step = step, error = "",
            manageModels = if (step == Step.Model) it.manageModels else false,
            manageResult = if (step == Step.Done) it.manageResult else ""
        )
    }

    private fun readAsset(name: String): String =
        getApplication<Application>().assets.open(name).bufferedReader().use { it.readText() }

    // ── connect ────────────────────────────────────────────────

    fun testConnection() {
        val s = _state.value
        viewModelScope.launch {
            _state.update { it.copy(busy = true, busyLabel = "Connecting…", error = "") }
            try {
                val known = prefs.getString(hostKey(), null)
                val fp = withContext(Dispatchers.IO) {
                    ssh.connect(s.host.trim(), s.port.trim().toInt(), s.user.trim(), s.password, known)
                }
                if (known == null) prefs.edit().putString(hostKey(), fp).apply()
                _state.update {
                    it.copy(busy = false, connected = true, fingerprint = fp, fingerprintKnown = known != null)
                }
                delay(800)
                _state.update { it.copy(step = Step.Mnemonic, error = "") }
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, connected = false, error = e.message ?: "connection failed") }
            }
        }
    }

    // ── models ─────────────────────────────────────────────────

    private fun cachedModels(): List<String>? {
        val raw = prefs.getString(modelsCacheKey(), null) ?: return null
        val parts = raw.split("|", limit = 2)
        if (parts.size != 2) return null
        val ts = parts[0].toLongOrNull() ?: return null
        if (System.currentTimeMillis() / 1000 - ts > 24 * 3600) return null
        return parts[1].split(",").filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }
    }

    private fun storeModels(models: List<String>) {
        prefs.edit().putString(modelsCacheKey(), "${System.currentTimeMillis() / 1000}|${models.joinToString(",")}").apply()
    }

    private fun pickDefault(models: List<String>, current: String): String =
        current.takeIf { models.contains(it) }
            ?: models.firstOrNull { it.contains("deepseek-v4-flash") }
            ?: models.first()

    fun loadModels(force: Boolean = false) {
        val s = _state.value
        if (!force) {
            cachedModels()?.let { cached ->
                _state.update {
                    it.copy(
                        busy = false, error = "", models = cached, modelsFromCache = true,
                        model = pickDefault(cached, it.model)
                    )
                }
                return
            }
        }
        viewModelScope.launch {
            _state.update {
                it.copy(busy = true, busyLabel = "Fetching models…", error = "", models = emptyList(), modelsFromCache = false)
            }
            try {
                val models = withContext(Dispatchers.IO) {
                    val env = SshManager.envString(
                        buildMap {
                            put("SAA_ACTION", "list-models")
                            put("OPENCODE_API_KEY", s.apiKey.trim())
                            if (force) put("SAA_REFRESH", "1")
                        }
                    )
                    ssh.uploadInstaller(readAsset("saa-app-setup.sh"))
                    val out = ssh.execCapture("env $env bash /tmp/saa-app-setup.sh", 300)
                    val list = out.lines().mapNotNull { line ->
                        (MarkerParser.parse(line) as? SaaEvent.ModelList)?.models
                    }.firstOrNull().orEmpty()
                    if (list.isEmpty()) {
                        val err = out.lines().firstOrNull { it.startsWith("SAA:ERROR ") }
                        throw IllegalStateException(err?.removePrefix("SAA:ERROR ") ?: "no models available for this key")
                    }
                    list
                }
                storeModels(models)
                _state.update {
                    it.copy(busy = false, models = models, modelsFromCache = false, model = pickDefault(models, it.model))
                }
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = e.message ?: "failed to load models") }
            }
        }
    }

    // ── streaming runner (install + manage actions) ──────────────

    private suspend fun runStreaming(action: String, extraEnv: Map<String, String>) {
        val done = AtomicBoolean(false)
        val botId = AtomicReference<String?>(null)
        val failure = AtomicReference<String?>(null)

        val env = SshManager.envString(buildMap {
            put("SAA_ACTION", action)
            putAll(extraEnv)
        })

        withContext(Dispatchers.IO) {
            ssh.uploadInstaller(readAsset("saa-app-setup.sh"))
            val logPath = ssh.uploadAndExecDetached(env, "/var/log/saa-app-setup.log")

            val watchdog = Thread {
                Thread.sleep(20 * 60 * 1000)
                if (!done.get()) {
                    failure.compareAndSet(null, "Timed out waiting for the server. Reconnect and check the logs.")
                    done.set(true)
                }
            }
            watchdog.isDaemon = true
            watchdog.start()

            ssh.streamLog(logPath, { line ->
                when (val ev = MarkerParser.parse(line)) {
                    is SaaEvent.BotSessionId -> {
                        botId.set(ev.id)
                        appendLog("Bot Session ID: ${ev.id}")
                    }
                    is SaaEvent.Error -> {
                        failure.set(ev.message)
                        appendLog("ERROR: ${ev.message}")
                        done.set(true)
                    }
                    SaaEvent.Ok -> done.set(true)
                    is SaaEvent.Step -> appendLog("► ${ev.name}")
                    is SaaEvent.Info -> appendLog("• ${ev.key}: ${ev.value}")
                    is SaaEvent.Progress -> _state.update {
                        it.copy(progress = maxOf(it.progress, ev.percent), progressLabel = ev.label)
                    }
                    else -> appendLog(line)
                }
            }, { done.get() })
        }

        failure.get()?.let { throw IllegalStateException(it) }
        botId.get()?.let { id -> _state.update { it.copy(botId = id) } }
    }

    private fun installEnv(): Map<String, String> = mapOf(
        "SESSION_MNEMONIC" to _state.value.mnemonic.trim(),
        "OWNER_SESSION_ID" to _state.value.ownerId.trim(),
        "OPENCODE_API_KEY" to _state.value.apiKey.trim(),
        "ENGINE" to _state.value.engine,
        "MODEL" to _state.value.model,
    )

    private fun startAction(mode: String, extraEnv: Map<String, String>, busyLabel: String) {
        viewModelScope.launch {
            _state.update {
                it.copy(
                    step = Step.Install, log = emptyList(), busy = true, busyLabel = busyLabel,
                    error = "", progress = -1, progressLabel = "",
                    actionMode = mode, manageResult = "", uninstalled = false
                )
            }
            try {
                runStreaming(
                    when (mode) {
                        "change-model" -> "change-model"
                        "switch-engine" -> "switch-engine"
                        "uninstall" -> "uninstall"
                        else -> "install"
                    },
                    extraEnv
                )
                when (mode) {
                    "install" -> _state.update { it.copy(busy = false, step = Step.Done, progress = 100) }
                    "change-model" -> _state.update { it.copy(busy = false, step = Step.Done, manageResult = "Model updated", progress = 100) }
                    "switch-engine" -> _state.update { it.copy(busy = false, step = Step.Done, manageResult = "Engine switched", progress = 100) }
                    "uninstall" -> _state.update { it.copy(busy = false, step = Step.Done, manageResult = "Uninstalled", uninstalled = true, progress = 100) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = e.message ?: "action failed") }
            }
        }
    }

    fun startInstall() {
        _state.update { it.copy(manageModels = false) }
        startAction("install", installEnv(), "Installing…")
    }

    /** One-tap engine fallback when an install fails (OpenClaw <-> Hermes). */
    fun retryWithOtherEngine() {
        val other = if (_state.value.engine == "openclaw") "hermes" else "openclaw"
        _state.update { it.copy(engine = other) }
        startInstall()
    }

    // ── manage actions ─────────────────────────────────────────

    fun openModelManager() {
        _state.update { it.copy(step = Step.Model, manageModels = true, error = "", manageResult = "") }
    }

    fun applyModelChange() {
        val s = _state.value
        startAction(
            "change-model",
            mapOf("MODEL" to s.model, "OPENCODE_API_KEY" to s.apiKey.trim()),
            "Changing model…"
        )
    }

    fun switchEngine() {
        val s = _state.value
        val target = if (s.engine == "openclaw") "hermes" else "openclaw"
        startAction(
            "switch-engine",
            mapOf("ENGINE" to target, "MODEL" to s.model, "OPENCODE_API_KEY" to s.apiKey.trim()),
            "Switching engine…"
        )
    }

    fun uninstall() = startAction("uninstall", emptyMap(), "Uninstalling…")

    fun refreshSessionId() {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, busyLabel = "Reading Session ID…", error = "", manageResult = "") }
            try {
                val id = withContext(Dispatchers.IO) {
                    val env = SshManager.envString(mapOf("SAA_ACTION" to "view-id"))
                    ssh.uploadInstaller(readAsset("saa-app-setup.sh"))
                    val out = ssh.execCapture("env $env bash /tmp/saa-app-setup.sh", 120)
                    out.lines().mapNotNull { (MarkerParser.parse(it) as? SaaEvent.BotSessionId)?.id }.firstOrNull()
                }
                _state.update {
                    if (id != null) it.copy(busy = false, botId = id, manageResult = "Session ID refreshed")
                    else it.copy(busy = false, error = "could not read the Session ID (is the agent running?)")
                }
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = e.message ?: "failed to read Session ID") }
            }
        }
    }

    private fun appendLog(line: String) {
        if (line.isBlank()) return
        _state.update { st -> st.copy(log = (st.log + line).takeLast(400)) }
    }

    override fun onCleared() {
        ssh.disconnect()
        super.onCleared()
    }
}
