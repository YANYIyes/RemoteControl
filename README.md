# RemoteControl — Control Your Windows PC from Your Android Phone

> A lightweight **Android → Windows** remote control: your phone is the controller, a zero-dependency Node.js server runs on Windows. Connect over a secure **WebSocket long connection** — no desktop program, no extra runtime (just Node).

**[中文版 Chinese](./README_zh-CN.md)**

---

## ✅ Features

| Feature | Description |
|---------|-------------|
| 🖥 **Process Manager** | List all processes, search, force-kill |
| 🔌 **Shutdown / Reboot** | Remotely power off or restart your PC |
| 📊 **System Info** | OS, CPU load, memory, disk usage, uptime |
| 📁 **File Browser** | Navigate directories, go back, see sizes |
| 🖥️ **Live Screen View** | **View-only** real-time desktop (~3fps), fullscreen landscape |
| 🔄 **Keep-alive** | Auto-reconnect (5s) + heartbeat (15s), survives rotation/page switches |
| 🔐 **Device Auth** | Unauthorized devices rejected; new device → admin approves via terminal |
| 🔔 **Push Alerts** *(v2.0)* | CPU spike alert, process-gone alert, scheduled-shutdown reminder — pushed straight to your phone notification |
| ⏰ **Scheduled Shutdown** *(v2.0)* | Set a daily auto-shutdown time from the app |
| 🎨 **Theme Colors** *(v2.0)* | 5 color themes (blue/purple/green/orange/night) switched in-app |
| ⚙️ **Settings Screen** *(v2.0)* | Toggle notification permission, background persistence, per-alert switches, remember server address |
| 🔋 **Background Persistence** *(v2.0)* | Foreground service keeps the app alive & connected in background (toggleable) |

> Deliberately **not included**: remote mouse/keyboard control, clipboard, screen lock, audio — keeps the attack surface minimal.

---

## 🔒 Zero-Credential Security Model

**The APK contains no credentials at all** — no passwords, keys, or tokens. Even if the APK is reverse-engineered, there is nothing to extract.

- **Device fingerprint**: a hash of `AndroidID + Build.FINGERPRINT` forms an anonymous, stable serial.
- **Server whitelist**: only serials in `whitelist.json` (with `allow:true`) can connect. Unauthorized devices are dropped.
- **Approval flow**: a new device waits in "pending" until the admin confirms on the server console (type `允许 <serial>`).
- **Instant effect**: the server re-reads the whitelist on every connection — no restart needed after authorizing.

---

## 🧱 Architecture

```
┌───────────┐        wss (TLS, optional via cloudflared/frp)       ┌──────────────┐
│ Android APK│  ─────────────────────────────────────────────────▶ │ Windows Server│
│ (controller│        WebSocket long connection                    │  (Node.js)    │
│  zero-creds)│                                                  │ control_server│
└───────────┘                                                        │  .js          │
      ▲                                                            │ device auth + │
      └──────────── commands / screen frames ────────────────────▶   whitelist     │
                                                                    │ process/system│
                                                                    │ /files        │
                                                                    │ push alerts   │
                                                                    │ shutdown/rebot│
                                                                    └──────────────┘
```

- **Protocol**: flat JSON messages (each carries an optional `seq`, echoed in the response).
- **Screen**: a persistent PowerShell process captures the screen via `System.Drawing`, sends **binary frames** (`SCRN\x01` magic header + JPEG) to the viewer. Zero third-party dependencies.
- **Push (v2.0)**: `rc_push.js` runs a monitor loop (CPU threshold / process watch / schedule tick) and pushes alerts over the same WebSocket to the phone.

---

## 🚀 Quick Start (Windows Server, one command)

> Requires **Windows** + **Node.js 18+** (LTS). The server has zero dependencies besides `ws` — no admin needed for normal/background run.

```powershell
cd scripts
.\deploy.ps1                 # default: port 8899, background run
.\deploy.ps1 -Port 9000      # custom port
.\deploy.ps1 -Service on     # register as Windows service (needs nssm + admin)
.\deploy.ps1 -Uninstall      # stop & uninstall service
```

The script auto-checks Node, installs deps, generates an empty `whitelist.json`, and starts the server.

### Authorize your phone

1. Open the APK, fill the server address, tap **Connect**.
2. The app shows "pending auth" with its **serial number**.
3. On the server console (or `授权设备.ps1`), type `允许 <serial>`.
4. Reconnect — now authorized.

---

## 📱 Android App (v2.0)

- **Settings screen** (⚙ top-right): theme colors, remember-server toggle, notification permission, background service, alert switches (master / CPU / process).
- **Scheduled shutdown**: power card → "Scheduled Shutdown" → pick a daily time (`HH:MM`) or cancel.
- **Foreground service**: keeps connection & alerts alive in the background; can be turned off in Settings (ACTION_STOP).

---

## 📄 License

GNU GPL v3 (with additional non-commercial terms) — see [LICENSE](./LICENSE).