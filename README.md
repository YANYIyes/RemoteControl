# RemoteControl — Control Your Windows PC from Your Android Phone

> A lightweight **Android ↔ Windows** remote control: your phone is the controller, a zero-dependency Node.js server runs on Windows. Connect over a secure **WebSocket long connection** — no desktop program, no extra runtime (just Node).

**[中文版 Chinese](./README_zh-CN.md)**

---

## ✨ Features

| Feature | Description |
|---------|-------------|
| 🖥 **Process Manager** | List all processes, search, force-kill |
| ⏻ **Shutdown / Reboot** | Remotely power off or restart your PC |
| 📊 **System Info** | OS, CPU load, memory, disk usage, uptime |
| 📁 **File Browser** | Navigate directories, go back, see sizes |
| 🖥️ **Live Screen View** | **View-only** real-time desktop (≈5fps), fullscreen landscape |
| 🔔 **Keep-alive** | Auto-reconnect (5s) + heartbeat (15s), survives rotation/page switches |
| 🔐 **Device Auth** | Unauthorized devices rejected; new device → admin approves via terminal or QQ |

> Deliberately **not included**: remote mouse/keyboard control, clipboard, screen lock, audio — keeps the attack surface minimal.

---

## 🔒 Zero-Credential Security Model

**The APK contains no credentials at all** — no passwords, keys, or tokens. Even if the APK is reverse-engineered, there is nothing to extract.

- **Device fingerprint**: a hash of `AndroidID + Build.FINGERPRINT` forms an anonymous, stable serial.
- **Server whitelist**: only serials in `whitelist.json` (with `allow:true`) can connect. Unauthorized devices are dropped.
- **Approval flow**: a new device waits in "pending" until the admin confirms (terminal or QQ).
- **Instant effect**: the server re-reads the whitelist on every connection — no restart needed after authorizing.

---

## 🏗 Architecture

```
┌─────────────┐   wss (TLS, optional via cloudflared/frp)   ┌────────────────────────────┐
│  Android APK │ ───────────────────────────────────────────▶ │  Windows Server (Node.js)  │
│  (controller,│          WebSocket long connection          │  control_server.js         │
│   zero-creds)│ ◀─────────────────────────────────────────── │  ├ device auth + whitelist│
└─────────────┘            commands / screen frames          │  ├ process/system/files    │
               ▲                                             │  ├ QQ notify (optional)    │
               └ new device → approve on server console/QQ    │  └ shutdown/reboot         │
                                                              └────────────────────────────┘
```

- **Protocol**: flat JSON messages (each carries an optional `seq`, echoed in the response).
- **Screen**: a persistent PowerShell process captures the screen via `System.Drawing`, sends **binary frames** (`SCRN\x01` magic header + JPEG) to the viewer. Zero third-party dependencies.

---

## 🚀 Quick Start (Windows Server, one command)

> Requires **Windows** + **Node.js 18+** (LTS). The server has zero dependencies besides `ws` — no admin needed for normal/background run.

```powershell
cd scripts
.\deploy.ps1                 # default: port 8899, QQ=auto, background run
.\deploy.ps1 -Port 9000      # custom port
.\deploy.ps1 -QQ off         # disable QQ notifications
.\deploy.ps1 -QQ on          # force enable QQ notifications
.\deploy.ps1 -Service on     # register as Windows service (needs nssm + admin)
.\deploy.ps1 -Uninstall      # stop & uninstall service
```

The script auto-checks Node, installs deps, generates an empty `whitelist.json`, auto-detects the QQ channel (skips gracefully if absent), and starts the server.

### Authorize a device
```powershell
cd scripts
.\auth.ps1 list                    # show whitelist
.\auth.ps1 allow <serial> 手机名    # allow a device
.\auth.ps1 reject <serial>          # reject a device
```

---

## 📱 Android App

1. Install `远程控制-v1.2-release.apk` from the [Releases](https://github.com/YANYIyes/RemoteControl/releases) page (Android 8.0+).
2. Enter the server URL:
   - **LAN**: `ws://<your-pc-ip>:8899`
   - **Public**: `wss://<your-domain>` (needs TLS via cloudflared/frp)
3. Connect. On first time it shows "pending" — approve it on the server.
4. Done.

---

## 🌐 Public Access (wss + TLS)

Use **cloudflared Tunnel** (free, no open ports) or frp:

```bash
# cloudflared example: map 8899 to a public domain
cloudflared tunnel route dns <tunnel-id> remote.example.com
# config.yml ingress:
#   - hostname: remote.example.com
#     service: http://127.0.0.1:8899
```

Then set the App URL to `wss://remote.example.com`. Use your own domain & cert in production.

---

## 🔨 Build from Source (optional)

Requires JDK 17+ (21 recommended), Android SDK (compileSdk 36), Gradle 8.14.

```powershell
cd android
# signing (optional; skip for debug builds)
#   keytool -genkeypair -v -keystore remote-control.keystore -alias remotecontrol -keyalg RSA -keysize 2048 -validity 10950
#   create keystore.properties (storePassword/keyAlias/keyPassword), put remote-control.keystore in android/
$env:JAVA_HOME='<your-jdk-21-path>'
gradle :app:assembleRelease --no-daemon
# output: android\app\build\outputs\apk\release\app-release.apk
```

> **⚠️ The signing keystore is private.** Losing it means you can never ship a compatible update. Keep it out of the repo (it's already in `.gitignore`).

---

## 📡 Protocol Cheat-Sheet

Client sends `hello` first, then commands. All messages are JSON with an optional `seq` (echoed in replies).

| Type | Request | Response |
|------|---------|----------|
| Handshake | `{type:'hello',serial,deviceName,deviceModel,androidVersion}` | `{type:'auth',ok,device}` or `{type:'auth',ok:false,pending:true,error}` |
| Ping | `{type:'ping'}` | `{type:'pong',time}` |
| Processes | `{type:'process.list',search?}` | `{type:'process.list',processes:[{pid,name,cmd}]}` |
| Kill process | `{type:'process.kill',pid}` | `{type:'process.kill',ok}` or `{error}` |
| System info | `{type:'system.info'}` | `{type:'system.info',osName,cpuLoad,totalMem,freeMem,upTime,disks}` |
| File list | `{type:'file.list',path}` | `{type:'file.list',path,items:[{name,isDir,size}]}` |
| Screen view | `{type:'screen.start',fps}` | `{type:'screen.start',ok,fps}` + binary frames `SCRN\x01` + JPEG |
| Stop screen | `{type:'screen.stop'}` | `{type:'screen.stop',ok}` |
| Shutdown/Reboot | `{type:'shutdown'}` / `{type:'reboot'}` | `{type:'shutdown'/'reboot',ok,message}` |

---

## ❓ FAQ

- **Process list empty / "failed to fetch"?** Make sure you're connected & authorized; the server must be able to run `listproc.ps1`.
- **Can it control the screen?** No — screen is **view-only** by design. No mouse/keyboard channel.
- **Must the server run all the time?** Yes, the phone can only connect while the server runs. Use `-Service on` to auto-start.
- **Move to another PC?** `whitelist.json` is the only state file — back it up to migrate.

---

## 📄 License

**GNU GPL-3.0** with additional non-commercial terms (see [LICENSE](./LICENSE)):

- **Open-source != commercial**: free use by the public for study, research, personal & internal use is non-commercial and allowed.
- **Commercial use restricted**: any commercial use (selling, bundling into paid products/services, profitable deployment) requires prior **written permission from the author**.

`apk/` binaries and `keystore/` files are private deliverables, not part of the open-source scope.

---

*Built for reliable, credential-free remote control.* ⭐ Star if you find it useful!
