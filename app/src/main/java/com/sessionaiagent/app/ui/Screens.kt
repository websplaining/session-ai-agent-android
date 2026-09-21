package com.sessionaiagent.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sessionaiagent.app.Step
import com.sessionaiagent.app.UiState
import com.sessionaiagent.app.Validators
import com.sessionaiagent.app.WizardViewModel

@Composable
fun AppScreen(vm: WizardViewModel) {
    val s by vm.state.collectAsState()
    Surface(color = Saa.Bg, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(18.dp))
            Text(
                "[ Session AI Agent ]",
                color = Saa.Accent,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth()
            )
            Text(stepTitle(s.step), color = Saa.Muted, fontSize = 12.sp)
            Spacer(Modifier.height(12.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (s.step) {
                    Step.Welcome -> Welcome(vm)
                    Step.Connect -> Connect(vm, s)
                    Step.Mnemonic -> Mnemonic(vm, s)
                    Step.OwnerId -> OwnerId(vm, s)
                    Step.ApiKey -> ApiKey(vm, s)
                    Step.Engine -> Engine(vm, s)
                    Step.Model -> Model(vm, s)
                    Step.Install -> Install(vm, s)
                    Step.Done -> Done(vm, s)
                }
            }
            if (s.busy) {
                Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = Saa.Accent,
                        trackColor = Saa.Border
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(s.busyLabel, color = Saa.Muted, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(14.dp))
        }
    }
}

private fun stepTitle(step: Step): String = when (step) {
    Step.Welcome -> "private AI agent on your own VPS"
    Step.Connect -> "step 1 of 7 · connect to your server"
    Step.Mnemonic -> "step 2 of 7 · bot's Session recovery password"
    Step.OwnerId -> "step 3 of 7 · your Session ID"
    Step.ApiKey -> "step 4 of 7 · OpenCode Go"
    Step.Engine -> "step 5 of 7 · engine"
    Step.Model -> "step 6 of 7 · model"
    Step.Install -> "step 7 of 7 · installing"
    Step.Done -> "done"
}

// ───────────────────────── screens ─────────────────────────

@Composable
private fun Welcome(vm: WizardViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(
            "This app sets up an AI agent for Session Messenger on your own VPS — over SSH, directly from your phone.",
            color = Saa.Text, fontSize = 15.sp
        )
        Spacer(Modifier.height(14.dp))
        CardBox {
            Text("You will need:", color = Saa.Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Bullet("A VPS running Ubuntu 24.04+ (1 GB RAM ok)")
            Bullet("A Session account for the bot + its 13-word recovery password")
            Bullet("Your Session ID (only it can message the bot)")
            Bullet("An OpenCode Go API key")
        }
        Spacer(Modifier.height(14.dp))
        Text(
            "Your SSH password is never stored and never leaves your phone except to your own server.",
            color = Saa.Muted, fontSize = 12.sp
        )
        Spacer(Modifier.height(18.dp))
        PrimaryButton("Get started") { vm.go(Step.Connect) }
    }
}

@Composable
private fun Connect(vm: WizardViewModel, s: UiState) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Field("Server IP or hostname", s.host, { v -> vm.set { it.copy(host = v) } }, KeyboardType.Uri)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) {
                Field("SSH port", s.port, { v -> vm.set { it.copy(port = v) } }, KeyboardType.Number)
            }
            Box(Modifier.weight(1f)) {
                Field("Username", s.user, { v -> vm.set { it.copy(user = v) } })
            }
        }
        Field("SSH password", s.password, { v -> vm.set { it.copy(password = v, connected = false) } }, isPassword = true)
        s.error.takeIf { it.isNotEmpty() }?.let { ErrorBox(it) }
        if (s.connected) {
            CardBox {
                Text("Connected", color = Saa.Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (s.fingerprintKnown) "Server key verified (known host)"
                    else "Server key saved on first use (TOFU)",
                    color = Saa.Text, fontSize = 12.sp
                )
                Spacer(Modifier.height(4.dp))
                Text(s.fingerprint, color = Saa.Muted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
        }
        Spacer(Modifier.height(14.dp))
        if (!s.connected) {
            PrimaryButton("Connect", enabled = !s.busy) {
                if (validateConnect(s)) vm.testConnection()
            }
        } else {
            PrimaryButton("Continue") { vm.go(Step.Mnemonic) }
        }
        Spacer(Modifier.height(8.dp))
        SecondaryButton("Back") { vm.go(Step.Welcome) }
    }
}

private fun validateConnect(s: UiState): Boolean =
    Validators.hostError(s.host) == null &&
        Validators.portError(s.port) == null &&
        Validators.userError(s.user) == null &&
        Validators.passwordError(s.password) == null

@Composable
private fun Mnemonic(vm: WizardViewModel, s: UiState) {
    var localError by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(
            "Paste the 13-word recovery password of the Session account your bot will use. In Session: Settings → Recovery Password.",
            color = Saa.Text, fontSize = 14.sp
        )
        Spacer(Modifier.height(12.dp))
        Field("Recovery password (13 words)", s.mnemonic, { v -> vm.set { it.copy(mnemonic = v) } }, lines = 3)
        localError.takeIf { it.isNotEmpty() }?.let { ErrorBox(it) }
        Spacer(Modifier.height(14.dp))
        PrimaryButton("Continue") {
            val e = Validators.mnemonicError(s.mnemonic)
            localError = e ?: ""
            if (e == null) vm.go(Step.OwnerId)
        }
        Spacer(Modifier.height(8.dp))
        SecondaryButton("Back") { vm.go(Step.Connect) }
    }
}

@Composable
private fun OwnerId(vm: WizardViewModel, s: UiState) {
    var localError by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(
            "Enter YOUR Session ID. Only this ID will be allowed to talk to the bot.",
            color = Saa.Text, fontSize = 14.sp
        )
        Spacer(Modifier.height(12.dp))
        Field("Your Session ID (66 hex characters)", s.ownerId, { v -> vm.set { it.copy(ownerId = v.trim()) } })
        localError.takeIf { it.isNotEmpty() }?.let { ErrorBox(it) }
        Spacer(Modifier.height(14.dp))
        PrimaryButton("Continue") {
            val e = Validators.ownerIdError(s.ownerId)
            localError = e ?: ""
            if (e == null) vm.go(Step.ApiKey)
        }
        Spacer(Modifier.height(8.dp))
        SecondaryButton("Back") { vm.go(Step.Mnemonic) }
    }
}

@Composable
private fun ApiKey(vm: WizardViewModel, s: UiState) {
    val uri = LocalUriHandler.current
    var localError by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(
            "Session AI Agent runs on an OpenCode Go subscription ($10/month). Subscribing through this link gives you $5 in usage credit:",
            color = Saa.Text, fontSize = 14.sp
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "https://opencode.ai/go?ref=9Q6GKAZPK6",
            color = Saa.Accent, fontSize = 13.sp,
            modifier = Modifier.clickable { uri.openUri("https://opencode.ai/go?ref=9Q6GKAZPK6") }
        )
        Spacer(Modifier.height(12.dp))
        Field("OpenCode Go API key", s.apiKey, { v -> vm.set { it.copy(apiKey = v.trim()) } })
        localError.takeIf { it.isNotEmpty() }?.let { ErrorBox(it) }
        Spacer(Modifier.height(14.dp))
        PrimaryButton("Continue") {
            val e = Validators.apiKeyError(s.apiKey)
            localError = e ?: ""
            if (e == null) vm.go(Step.Engine)
        }
        Spacer(Modifier.height(8.dp))
        SecondaryButton("Back") { vm.go(Step.OwnerId) }
    }
}

@Composable
private fun Engine(vm: WizardViewModel, s: UiState) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text("Choose the AI engine to install on your server:", color = Saa.Text, fontSize = 14.sp)
        Spacer(Modifier.height(12.dp))
        EngineOption(
            title = "OpenClaw",
            desc = "Multi-channel gateway agent (default, fastest install)",
            selected = s.engine == "openclaw"
        ) { vm.set { it.copy(engine = "openclaw") } }
        Spacer(Modifier.height(8.dp))
        EngineOption(
            title = "Hermes Agent",
            desc = "Self-improving agent with memory & skills",
            selected = s.engine == "hermes"
        ) { vm.set { it.copy(engine = "hermes") } }
        Spacer(Modifier.height(16.dp))
        PrimaryButton("Continue") { vm.go(Step.Model) }
        Spacer(Modifier.height(8.dp))
        SecondaryButton("Back") { vm.go(Step.ApiKey) }
    }
}

@Composable
private fun Model(vm: WizardViewModel, s: UiState) {
    LaunchedEffect(Unit) { if (s.models.isEmpty()) vm.loadModels() }
    Column(Modifier.fillMaxSize()) {
        Text("Models available on your plan:", color = Saa.Text, fontSize = 14.sp)
        Spacer(Modifier.height(8.dp))
        s.error.takeIf { it.isNotEmpty() }?.let {
            ErrorBox(it)
            Spacer(Modifier.height(10.dp))
            PrimaryButton("Try again", enabled = !s.busy) { vm.loadModels() }
        }
        if (s.models.isNotEmpty()) {
            LazyColumn(Modifier.weight(1f)) {
                items(s.models.size) { i ->
                    val m = s.models[i]
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { vm.set { it.copy(model = m) } }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = s.model == m,
                            onClick = { vm.set { it.copy(model = m) } },
                            colors = androidx.compose.material3.RadioButtonDefaults.colors(
                                selectedColor = Saa.Accent, unselectedColor = Saa.Muted
                            )
                        )
                        Text(m.removePrefix("opencode-go/"), color = Saa.Text, fontSize = 14.sp)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            PrimaryButton("Install now", enabled = s.model.isNotEmpty() && !s.busy) { vm.startInstall() }
        }
        Spacer(Modifier.height(8.dp))
        SecondaryButton("Back", enabled = !s.busy) { vm.go(Step.Engine) }
    }
}

@Composable
private fun Install(vm: WizardViewModel, s: UiState) {
    val listState = rememberLazyListState()
    LaunchedEffect(s.log.size) {
        if (s.log.isNotEmpty()) listState.animateScrollToItem(s.log.size - 1)
    }
    Column(Modifier.fillMaxSize()) {
        if (s.error.isNotEmpty()) {
            ErrorBox(s.error)
            Spacer(Modifier.height(10.dp))
            PrimaryButton("Retry") { vm.startInstall() }
            Spacer(Modifier.height(8.dp))
            SecondaryButton("Back") { vm.go(Step.Model) }
        } else {
            Text("Installing on your server — keep the app open.", color = Saa.Muted, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier.weight(1f).fillMaxWidth()
                    .background(Saa.CodeBg, RoundedCornerShape(6.dp))
                    .border(1.dp, Saa.Border, RoundedCornerShape(6.dp))
                    .padding(10.dp)
            ) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(s.log.size) { i ->
                        Text(
                            s.log[i],
                            color = if (s.log[i].startsWith("ERROR")) Saa.Danger else Saa.Text,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Done(vm: WizardViewModel, s: UiState) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text("Your agent is live!", color = Saa.Accent, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Text("Bot Session ID:", color = Saa.Text, fontSize = 14.sp)
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier.fillMaxWidth()
                .background(Saa.CodeBg, RoundedCornerShape(6.dp))
                .border(1.dp, Saa.Border, RoundedCornerShape(6.dp))
                .padding(12.dp)
        ) {
            Text(s.botId, color = Saa.Accent, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.height(10.dp))
        PrimaryButton(if (copied) "Copied" else "Copy bot Session ID") {
            clipboard.setText(AnnotatedString(s.botId))
            copied = true
        }
        Spacer(Modifier.height(14.dp))
        CardBox {
            Text("Next steps", color = Saa.Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Bullet("Open Session and paste the bot Session ID to send a message request")
            Bullet("Only your Session ID can message the bot")
            Bullet("Re-run the setup script on your VPS anytime to change model, switch engine, view the Session ID, or uninstall")
        }
        Spacer(Modifier.height(18.dp))
        SecondaryButton("Set up another server") { vm.go(Step.Welcome) }
    }
}

// ───────────────────────── widgets ─────────────────────────

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    keyboard: KeyboardType = KeyboardType.Text,
    isPassword: Boolean = false,
    lines: Int = 1,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, fontSize = 12.sp) },
        singleLine = lines == 1,
        minLines = lines,
        visualTransformation = if (isPassword) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Saa.Text,
            unfocusedTextColor = Saa.Text,
            focusedBorderColor = Saa.Accent,
            unfocusedBorderColor = Saa.Border,
            focusedLabelColor = Saa.Accent,
            unfocusedLabelColor = Saa.Muted,
            cursorColor = Saa.Accent,
            focusedContainerColor = Saa.Surface,
            unfocusedContainerColor = Saa.Surface,
        ),
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
    )
}

@Composable
private fun PrimaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = Saa.Accent,
            contentColor = Color(0xFF00210F),
            disabledContainerColor = Saa.Border,
            disabledContentColor = Saa.Muted
        ),
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.fillMaxWidth().height(46.dp)
    ) {
        Text(text, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}

@Composable
private fun SecondaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = Saa.BtnBg,
            contentColor = Saa.Text,
            disabledContainerColor = Saa.Surface,
            disabledContentColor = Saa.Muted
        ),
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.fillMaxWidth().height(44.dp)
    ) {
        Text(text, fontSize = 13.sp)
    }
}

@Composable
private fun ErrorBox(message: String) {
    Box(
        Modifier.fillMaxWidth()
            .background(Color(0xFF1A0D0D), RoundedCornerShape(6.dp))
            .border(1.dp, Saa.Danger, RoundedCornerShape(6.dp))
            .padding(10.dp)
    ) {
        Text(message, color = Saa.Danger, fontSize = 12.sp)
    }
}

@Composable
private fun CardBox(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .background(Saa.Surface, RoundedCornerShape(6.dp))
            .border(1.dp, Saa.Border, RoundedCornerShape(6.dp))
            .padding(12.dp),
        content = content
    )
}

@Composable
private fun Bullet(text: String) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text("· ", color = Saa.Accent, fontSize = 13.sp)
        Text(text, color = Saa.Text, fontSize = 13.sp)
    }
}

@Composable
private fun EngineOption(title: String, desc: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(if (selected) Saa.BtnBg else Saa.Surface, RoundedCornerShape(6.dp))
            .border(1.dp, if (selected) Saa.Accent else Saa.Border, RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = androidx.compose.material3.RadioButtonDefaults.colors(
                selectedColor = Saa.Accent, unselectedColor = Saa.Muted
            )
        )
        Spacer(Modifier.width(6.dp))
        Column {
            Text(title, color = if (selected) Saa.Accent else Saa.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(desc, color = Saa.Muted, fontSize = 12.sp)
        }
    }
}
