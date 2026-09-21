# Testing the app (manual E2E)

> The app performs a real installation on a real server. Use a **scratch VPS** for testing
> (a fresh Ubuntu 24.04 droplet is ideal — 1 GB RAM shows the worst case, which is good).

## 1. Get the APK

- CI artifact: repo → **Actions** → latest `build` run → `session-ai-agent-debug` artifact (unzip → `app-debug.apk`)
- Or build locally: `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`

## 2. Install on the phone

1. Copy the APK to the phone (download, USB, or `adb install app-debug.apk`)
2. Allow installing from unknown sources when prompted

## 3. Run the wizard

Prepare beforehand:

- VPS IP + root password (password SSH login must be enabled)
- The bot's Session account **13-word recovery password**
- Your **Session ID** (66 hex chars)
- An **OpenCode Go API key** ([referral link](https://opencode.ai/go?ref=9Q6GKAZPK6) — $10/month, $5 usage credit via the link)

Walk through the steps: Connect → (fingerprint is shown and pinned on first use) → Mnemonic → Owner ID → API key → Engine → Model (fetched live, only plan-available models listed) → Install.

## 4. Verify

Expect the Done screen with the **bot Session ID**. Then:

1. Open Session, start a chat with that ID, send a message — the bot should reply
2. On the server (optional):
   ```bash
   systemctl status claw-bridge
   journalctl -u claw-bridge -n 30
   cat /root/session-claw-bridge/.env        # confirms engine + model
   ```

## 5. Known limits (v0.1)

- Password SSH login only (no key auth yet)
- Install wizard only — change model / switch engine / uninstall come later
- If the install fails, the app shows the error and the last log lines; the full log is on the server at `/var/log/saa-app-setup.log`

## Failure drill (good to test)

- Wrong SSH password → clear "authentication failed" error on the Connect step
- Wrong API key → model picker reports no models / clear error
- Test both engines (OpenClaw and Hermes) and at least two models
