#!/bin/bash
# Session AI Agent — app installer (non-interactive)
#
# Runs on the user's VPS, driven by the Session AI Agent Android app.
# The website installer (setup.sh) is intentionally untouched; this is the
# app's standalone twin and shares the same bridge artifact.
#
# Env contract:
#   SAA_ACTION         install | list-models | status | view-id   (default: install)
#   SESSION_MNEMONIC   13-word Session recovery password (bot account)
#   OWNER_SESSION_ID   owner Session ID (66 hex chars)
#   OPENCODE_API_KEY   OpenCode Go API key
#   ENGINE             openclaw | hermes        (default: openclaw)
#   MODEL              opencode-go/<model>      (default: opencode-go/deepseek-v4-flash)
#   SAA_DRY_RUN        1 = validate + print plan, change nothing
#
# Stream markers parsed by the app:
#   SAA:STEP <name> | SAA:INFO <k> <v> | SAA:MODEL_LIST [json]
#   SAA:BOT_SESSION_ID <id> | SAA:OK | SAA:ERROR <msg>

set -u
export PATH="/root/.bun/bin:/usr/local/bin:/usr/bin:/bin:$PATH"

API="https://opencode.ai/zen/go/v1/models"
BRIDGE_URL="https://sessionaiagent.com/session-claw-bridge-v2.tar.gz"
DIR="/root/session-claw-bridge"
DEFAULT_MODEL="opencode-go/deepseek-v4-flash"

ACTION="${SAA_ACTION:-install}"
DRY="${SAA_DRY_RUN:-0}"
ENGINE="${ENGINE:-openclaw}"
MODEL="${MODEL:-$DEFAULT_MODEL}"
MNEMONIC="${SESSION_MNEMONIC:-}"
OWNER="${OWNER_SESSION_ID:-}"
KEY="${OPENCODE_API_KEY:-}"

say()  { echo "SAA:STEP $*"; }
info() { echo "SAA:INFO $*"; }
warn() { echo "  WARNING: $*"; }
die()  { echo "SAA:ERROR $*" >&2; exit 1; }
ok()   { echo "SAA:OK"; }
hr()   { echo "  ---- $*"; }

[[ $EUID -eq 0 ]] || die "must run as root"

# ─────────────────────────────────────────────────────────────
# validation
# ─────────────────────────────────────────────────────────────

validate_install() {
  [[ -n "$MNEMONIC" ]] || die "missing SESSION_MNEMONIC"
  [[ $(wc -w <<<"$MNEMONIC") -eq 13 ]] || die "Session recovery password must be 13 words"
  [[ "$OWNER" =~ ^[0-9a-fA-F]{66}$ ]] || die "owner Session ID must be 66 hex characters"
  [[ -n "$KEY" ]] || die "missing OPENCODE_API_KEY"
  [[ "$ENGINE" == "openclaw" || "$ENGINE" == "hermes" ]] || die "engine must be openclaw or hermes"
  [[ "$MODEL" == */* ]] || MODEL="opencode-go/$MODEL"
  [[ "$MODEL" == opencode-go/* ]] || die "model must be an opencode-go/* model"
}

# ─────────────────────────────────────────────────────────────
# helpers
# ─────────────────────────────────────────────────────────────

apt_wait() {
  local f
  for f in /var/lib/apt/lists/lock /var/lib/dpkg/lock-frontend /var/lib/dpkg/lock; do
    while fuser "$f" >/dev/null 2>&1; do sleep 2; done
  done
}

ensure_swap() {
  local ram
  ram=$(free -m | awk 'NR==2{print $2}')
  if [[ "$ram" -lt 2048 ]] && ! swapon --show 2>/dev/null | grep -q '/'; then
    hr "adding 1G swapfile (low-memory box)"
    fallocate -l 1G /swapfile 2>/dev/null || dd if=/dev/zero of=/swapfile bs=1M count=1024 2>/dev/null
    if chmod 600 /swapfile && mkswap /swapfile >/dev/null 2>&1 && swapon /swapfile >/dev/null 2>&1; then
      hr "swap added"
    else
      rm -f /swapfile
      warn "could not add swap (OpenClaw install may fail on 1GB boxes)"
    fi
  fi
}

fetch_model_ids() {
  local json
  json=$(timeout 12 curl -sf --max-time 10 "$API" 2>/dev/null || true)
  [[ -n "$json" ]] && grep -oP '"id"\s*:\s*"\K[^"]+' <<<"$json" || true
}

# Probe each catalog model with the user's key; 401 "not supported" = not on plan.
probe_models() {
  local key="$1" sid="$2" m i=0 resp code b keep=()
  local CHAT="${API%/models}/chat/completions"
  local tmpd; tmpd=$(mktemp -d)
  local models=()
  while IFS= read -r m; do [[ -n "$m" ]] && models+=("$m"); done < <(fetch_model_ids)
  (( ${#models[@]} )) || die "could not fetch model catalog"
  hr "checking ${#models[@]} models against your plan"
  for m in "${models[@]}"; do
    ( resp=$(curl -s --max-time 12 -X POST "$CHAT" \
        -H "Authorization: Bearer $key" -H "Content-Type: application/json" \
        -H "x-opencode-session: $sid" \
        -d "{\"model\":\"$m\",\"messages\":[{\"role\":\"user\",\"content\":\"ping\"}]}" \
        -w "|%{http_code}")
      printf '%s|%s' "$m" "$resp" > "$tmpd/$i" ) &
    i=$((i+1))
    (( i % 6 == 0 )) && wait
  done
  wait
  local f raw
  for f in "$tmpd"/*; do
    raw=$(cat "$f"); code=${raw##*|}; b=${raw%|*}; m=${b%%|*}; b=${b#*|}
    [[ -z "$m" ]] && continue
    if [[ "$code" == "401" ]] && grep -qiE 'not supported|not available' <<<"$b"; then
      continue
    fi
    keep+=("$m")
  done
  rm -rf "$tmpd"
  (( ${#keep[@]} )) || die "no models available for this key"
  local json
  json=$(printf '%s\n' "${keep[@]}" | sed 's/.*/"&"/' | paste -sd,)
  echo "SAA:MODEL_LIST [$json]"
}

# ─────────────────────────────────────────────────────────────
# prerequisites
# ─────────────────────────────────────────────────────────────

step_prereqs() {
  say "prereqs"
  apt_wait
  apt-get update -qq
  apt-get install -y -qq unzip curl gnupg >/dev/null 2>&1 || true
  if ! command -v node >/dev/null 2>&1 || [[ $(node -v 2>/dev/null | grep -oE '^v[0-9]+' | tr -d v) -lt 22 ]]; then
    hr "installing Node.js 22"
    apt_wait
    curl -fsSL https://deb.nodesource.com/setup_22.x | bash - >/dev/null 2>&1
    apt-get install -y -qq nodejs >/dev/null 2>&1
  fi
  if ! command -v bun >/dev/null 2>&1; then
    hr "installing Bun"
    export BUN_INSTALL=/root/.bun
    curl -fsSL https://bun.sh/install | bash >/dev/null 2>&1
  fi
  ensure_swap
}

# ─────────────────────────────────────────────────────────────
# OpenClaw
# ─────────────────────────────────────────────────────────────

install_openclaw() {
  if which openclaw >/dev/null 2>&1; then return 0; fi
  ensure_swap
  rm -rf /usr/lib/node_modules/openclaw /usr/lib/node_modules/.openclaw-* >/dev/null 2>&1
  local attempt
  for attempt in 1 2; do
    hr "installing OpenClaw (attempt $attempt)"
    npm install -g openclaw@latest --no-audit --no-fund --maxsockets=4 --prefer-offline >/dev/null 2>&1 || true
    if which openclaw >/dev/null 2>&1; then return 0; fi
    rm -rf /usr/lib/node_modules/openclaw /usr/lib/node_modules/.openclaw-* >/dev/null 2>&1
  done
  tail -5 "$(ls -t ~/.npm/_logs/*.log 2>/dev/null | head -1)" 2>/dev/null
  die "OpenClaw install failed (low memory or network)"
}

init_openclaw() {
  local bare="${MODEL#opencode-go/}"
  local tmp entries sid
  hr "configuring OpenClaw"
  openclaw onboard --accept-risk --non-interactive --skip-health --skip-daemon --skip-bootstrap \
    --auth-choice opencode-go --opencode-go-api-key "$KEY" >/dev/null 2>&1 || true
  openclaw config set agents.defaults.model.primary "$MODEL" >/dev/null 2>&1 || true
  openclaw config set agents.defaults.models "{\"$MODEL\":{}}" --strict-json --merge >/dev/null 2>&1 || true

  entries=$(python3 -c '
import json, sys
entry = {"id": sys.argv[1], "name": sys.argv[1], "api": "openai-completions",
  "baseUrl": "https://opencode.ai/zen/go/v1", "reasoning": False, "input": ["text"],
  "cost": {"input": 0, "output": 0, "cacheRead": 0, "cacheWrite": 0},
  "contextWindow": 200000, "maxTokens": 8192}
try: cur = json.loads(sys.argv[2] or "[]")
except Exception: cur = []
arr = [e for e in cur if e.get("id") != sys.argv[1]] + [entry]
print(json.dumps(arr))' "$bare" "$(openclaw config get models.providers.opencode-go.models --json 2>/dev/null || echo '[]')") \
    || entries="[]"

  if ! grep -q '^OPENCODE_SESSION=' "$DIR/.env" 2>/dev/null; then
    sid=$(cat /proc/sys/kernel/random/uuid 2>/dev/null || echo "saa-$(date +%s)")
    echo "OPENCODE_SESSION=$sid" >> "$DIR/.env"
  else
    sid=$(grep -oP '^OPENCODE_SESSION=\K.*' "$DIR/.env" 2>/dev/null)
  fi
  tmp=$(mktemp)
  printf '{ models: { providers: { "opencode-go": { models: %s, headers: { "x-opencode-session": "%s" } } } } }\n' "$entries" "$sid" > "$tmp"
  openclaw config patch --file "$tmp" >/dev/null 2>&1 || true
  rm -f "$tmp"
  if ! openclaw config get models.providers.opencode-go.models --json 2>/dev/null | grep -q "\"id\": \"$bare\""; then
    warn "could not register $bare in OpenClaw providers"
  fi
  if ! openclaw config get models.providers.opencode-go.headers --json 2>/dev/null | grep -q 'x-opencode-session'; then
    warn "could not register the OpenCode session header"
  fi
  hr "warming up OpenClaw"
  timeout 180 openclaw agent --local --session-id warmup --model "$MODEL" \
    --message "Reply with exactly: OK" --json >/dev/null 2>&1 || warn "warm-up returned no output"
}

# ─────────────────────────────────────────────────────────────
# Hermes
# ─────────────────────────────────────────────────────────────

install_hermes() {
  local log=/tmp/hermes-install.log PY="" venv_ok="" V VM
  : > "$log"
  rm -rf "$HOME/.hermes/venv"
  hr "installing Hermes Agent (python <3.14 venv)"

  for c in python3.13 python3.12; do
    if command -v "$c" >/dev/null 2>&1 && "$c" -m venv "$HOME/.hermes/venv" >/dev/null 2>&1; then
      PY="$c"; venv_ok=1; break
    fi
  done
  if [[ -z "$venv_ok" ]] && apt_wait; then
    if apt-get install -y -qq python3.12 python3.12-venv >/dev/null 2>&1 && \
       command -v python3.12 >/dev/null 2>&1 && python3.12 -m venv "$HOME/.hermes/venv" >/dev/null 2>&1; then
      PY=python3.12; venv_ok=1
    fi
  fi
  if [[ -z "$venv_ok" ]]; then
    hr "no python <3.14 found - using uv"
    curl -fsSL https://astral.sh/uv/install.sh | sh > "$log" 2>&1
    "$HOME/.local/bin/uv" venv --python 3.12 --quiet "$HOME/.hermes/venv" >> "$log" 2>&1
    "$HOME/.local/bin/uv" pip install --python "$HOME/.hermes/venv" --quiet hermes-agent >> "$log" 2>&1
  else
    hr "using $PY"
    "$HOME/.hermes/venv/bin/pip" install --upgrade --quiet hermes-agent >> "$log" 2>&1
  fi
  ln -sf "$HOME/.hermes/venv/bin/hermes" /usr/local/bin/hermes

  if ! which hermes >/dev/null 2>&1 || [[ -L /usr/local/bin/hermes && ! -e /usr/local/bin/hermes ]]; then
    tail -5 "$log" 2>/dev/null
    die "Hermes install failed"
  fi
  V=$(hermes --version 2>&1 | head -1)
  VM=$(echo "$V" | grep -oP 'v\K[0-9]+\.[0-9]+' | head -1)
  hr "installed: $V"
  if [[ -n "$VM" ]] && (( $(cut -d. -f2 <<<"$VM") < 16 )); then
    die "old Hermes build ($VM) - one-shot replies are broken"
  fi

  mkdir -p ~/.hermes
  echo "OPENCODE_GO_API_KEY=$KEY" > ~/.hermes/.env
  if ! grep -q '^OPENCODE_SESSION=' "$DIR/.env" 2>/dev/null; then
    sid=$(cat /proc/sys/kernel/random/uuid 2>/dev/null || echo "saa-$(date +%s)")
    echo "OPENCODE_SESSION=$sid" >> "$DIR/.env"
  else
    sid=$(grep -oP '^OPENCODE_SESSION=\K.*' "$DIR/.env" 2>/dev/null)
  fi
  cat > ~/.hermes/config.yaml <<EOF
providers:
  ocgo:
    base_url: https://opencode.ai/zen/go/v1
    api_key: $KEY
    extra_headers:
      x-opencode-session: $sid
EOF
  hr "testing one-shot reply"
  local bare="${MODEL#opencode-go/}" out
  out=$(timeout 120 hermes -z "Reply with exactly: OK" --provider ocgo --model "$bare" 2>&1 | head -c 300)
  if [[ -z "$out" || "$out" == *Error* || "$out" == *error* ]]; then
    warn "smoke test returned no usable reply (${out:-empty})"
  else
    hr "smoke test OK"
  fi
}

# ─────────────────────────────────────────────────────────────
# bridge + service
# ─────────────────────────────────────────────────────────────

step_bridge() {
  say "bridge"
  cd /root || die "cd /root failed"
  hr "downloading bridge"
  curl -sL "$BRIDGE_URL" | tar xz || die "bridge download failed"
  [[ -f "$DIR/index.js" ]] || die "bridge files missing after download"
  if command -v bun >/dev/null 2>&1; then
    (cd "$DIR" && bun install --quiet) >/dev/null 2>&1 || true
  fi
}

step_env() {
  say "env"
  cat > "$DIR/.env" <<EOF
SESSION_MNEMONIC="$MNEMONIC"
OWNER_SESSION_ID=$OWNER
OPENCODE_API_KEY=$KEY
OPENCODE_GO_API_KEY=$KEY
MODEL=$MODEL
BACKEND=$ENGINE
EOF
  chmod 600 "$DIR/.env"
}

step_service() {
  say "service"
  cp "$DIR/claw-bridge.service" /etc/systemd/system/
  systemctl daemon-reload
  systemctl enable --now claw-bridge >/dev/null 2>&1
  sleep 3
  systemctl is-active --quiet claw-bridge || warn "claw-bridge is not active yet (check journalctl -u claw-bridge)"
}

step_bot_id() {
  say "bot-id"
  local f=/tmp/session-ai-agent/session-id.txt id="" i
  for i in $(seq 1 30); do
    [[ -f "$f" ]] && id=$(cat "$f") && [[ -n "$id" ]] && break
    sleep 3
  done
  [[ -n "$id" ]] || die "did not receive the bot Session ID (check journalctl -u claw-bridge)"
  echo "SAA:BOT_SESSION_ID $id"
}

# ─────────────────────────────────────────────────────────────
# actions
# ─────────────────────────────────────────────────────────────

do_install() {
  validate_install
  info "engine $ENGINE"
  info "model $MODEL"
  step_prereqs
  step_bridge
  step_env
  say "engine"
  if [[ "$ENGINE" == "hermes" ]]; then
    install_hermes
  else
    install_openclaw
    init_openclaw
  fi
  step_service
  step_bot_id
  ok
}

do_list_models() {
  [[ -n "$KEY" ]] || die "missing OPENCODE_API_KEY"
  local sid
  sid=$(cat /proc/sys/kernel/random/uuid 2>/dev/null || echo probe)
  probe_models "$KEY" "$sid"
  ok
}

do_status() {
  local state="unknown" m="" e=""
  state=$(systemctl is-active claw-bridge 2>/dev/null || true)
  [[ -f "$DIR/.env" ]] && { m=$(grep -oP '^MODEL=\K.*' "$DIR/.env" || true); e=$(grep -oP '^BACKEND=\K.*' "$DIR/.env" || true); }
  info "service ${state:-unknown}"
  info "engine ${e:-none}"
  info "model ${m:-none}"
  if [[ -f /tmp/session-ai-agent/session-id.txt ]]; then
    echo "SAA:BOT_SESSION_ID $(cat /tmp/session-ai-agent/session-id.txt)"
  fi
  ok
}

case "$ACTION" in
  install)
    if [[ "$DRY" == "1" ]]; then
      validate_install
      info "dry-run ok: action=install engine=$ENGINE model=$MODEL"
      say prereqs; say engine; say bridge; say env; say service; say bot-id
      ok
      exit 0
    fi
    do_install
    ;;
  list-models)
    do_list_models
    ;;
  status|view-id)
    do_status
    ;;
  *)
    die "unknown SAA_ACTION '$ACTION'"
    ;;
esac
