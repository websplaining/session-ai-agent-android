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
    val log: List<String> = emptyList(),
    val botId: String = "",
    val error: String = "",
    val busy: Boolean = false,
    val busyLabel: String = "",
    val installFinished: Boolean = false,
)

class WizardViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("saa", Context.MODE_PRIVATE)
    private val ssh = SshManager()

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private fun key() = "hostkey_${_state.value.host.trim()}_${_state.value.port.trim()}"

    fun set(block: (UiState) -> UiState) = _state.update(block)

    fun go(step: Step) = _state.update { it.copy(step = step, error = "") }

    private fun readAsset(name: String): String =
        getApplication<Application>().assets.open(name).bufferedReader().use { it.readText() }

    fun testConnection() {
        val s = _state.value
        viewModelScope.launch {
            _state.update { it.copy(busy = true, busyLabel = "Connecting…", error = "") }
            try {
                val known = prefs.getString(key(), null)
                val fp = withContext(Dispatchers.IO) {
                    ssh.connect(s.host.trim(), s.port.trim().toInt(), s.user.trim(), s.password, known)
                }
                if (known == null) prefs.edit().putString(key(), fp).apply()
                _state.update {
                    it.copy(
                        busy = false, connected = true,
                        fingerprint = fp, fingerprintKnown = known != null
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, connected = false, error = e.message ?: "connection failed") }
            }
        }
    }

    fun loadModels() {
        val s = _state.value
        viewModelScope.launch {
            _state.update { it.copy(busy = true, busyLabel = "Fetching models…", error = "", models = emptyList()) }
            try {
                val models = withContext(Dispatchers.IO) {
                    val env = SshManager.envString(
                        mapOf(
                            "SAA_ACTION" to "list-models",
                            "OPENCODE_API_KEY" to s.apiKey.trim(),
                        )
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
                val preferred = models.firstOrNull { it.contains("deepseek-v4-flash") } ?: models.first()
                _state.update {
                    it.copy(
                        busy = false, models = models,
                        model = it.model.takeIf { m -> models.contains(m) } ?: preferred
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = e.message ?: "failed to load models") }
            }
        }
    }

    fun startInstall() {
        val s = _state.value
        viewModelScope.launch {
            _state.update {
                it.copy(
                    step = Step.Install, log = emptyList(), busy = true,
                    busyLabel = "Installing…", error = "", botId = "", installFinished = false
                )
            }
            try {
                val done = AtomicBoolean(false)
                val botId = AtomicReference<String?>(null)
                val failure = AtomicReference<String?>(null)

                withContext(Dispatchers.IO) {
                    val env = SshManager.envString(
                        mapOf(
                            "SAA_ACTION" to "install",
                            "SESSION_MNEMONIC" to s.mnemonic.trim(),
                            "OWNER_SESSION_ID" to s.ownerId.trim(),
                            "OPENCODE_API_KEY" to s.apiKey.trim(),
                            "ENGINE" to s.engine,
                            "MODEL" to s.model,
                        )
                    )
                    ssh.uploadInstaller(readAsset("saa-app-setup.sh"))
                    val logPath = ssh.uploadAndExecDetached(env, "/var/log/saa-app-setup.log")

                    // watchdog: never let the stream hang forever
                    val watchdog = Thread {
                        Thread.sleep(20 * 60 * 1000)
                        if (!done.get()) {
                            failure.compareAndSet(null, "Timed out waiting for the installer. Reconnect and check the server logs.")
                            done.set(true)
                        }
                    }
                    watchdog.isDaemon = true
                    watchdog.start()

                    ssh.streamLog(logPath, { line ->
                        val ev = MarkerParser.parse(line)
                        when (ev) {
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
                            else -> appendLog(line)
                        }
                    }, { done.get() })
                }

                val err = failure.get()
                if (err != null) throw IllegalStateException(err)
                val id = botId.get()
                if (id == null) throw IllegalStateException("install finished without a bot Session ID")
                _state.update { it.copy(busy = false, botId = id, step = Step.Done, installFinished = true) }
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = e.message ?: "install failed") }
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
