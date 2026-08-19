# 远程控制 Remote Control

> 一个 **Android App ↔ Windows PC** 的远程控制方案。手机 App 作为控制器，Windows 端运行一个零依赖的 Node.js 服务端（无需桌面程序、无需安装额外运行时，除 Node 外零依赖）。

设备通过 **WebSocket 长连接** 安全接入。**APK 内不内置任何凭据**（密码 / 密钥 / 令牌），安全依靠 **设备序列号指纹 + 服务端白名单** 双向校验，未绑定设备一律拒绝——即使 APK 被反编译也拿不到任何可用的凭据。

---

## ✨ 功能

| 功能 | 说明 |
|------|------|
| 🖥 **进程管理** | 查看全部进程 / 搜索 / 强制结束进程 |
| ⏻ **关机 / 重启** | 远程关闭或重启电脑（可配 10 秒倒计时） |
| 📊 **系统信息** | 操作系统、CPU 型号与负载、内存占用、磁盘用量、开机时长 |
| 📁 **文件浏览** | 浏览电脑目录、进入子目录、返回上级、查看大小 |
| 🖥️ **屏幕实时观看** | **仅观看**，低延迟实时画面（约 5fps），横屏全屏，不可远程操作 |
| 🔔 **连接保持** | 自动重连（5 秒退避）+ 协议心跳（15s），转屏/切页不断线 |
| 🔐 **设备鉴权** | 未绑定设备拒绝连接；新设备请求绑定 → QQ 或服务端控制台确认 |

> 设计上**不包含**远程命令执行、剪贴板、锁屏、声音控制等潜在高风险/易滥用能力，保持最小攻击面。

---

## 🏗 架构

```
┌─────────────┐   wss (TLS, 可选经 cloudflared/frp 公网)   ┌────────────────────────────┐
│  Android APK │ ──────────────────────────────────────────▶ │  Windows 服务端 (Node.js)   │
│  (控制器,    │         WebSocket 长连接                     │  control_server.js          │
│   零凭据)    │ ◀────────────────────────────────────────── │  ├─ 设备鉴权 + 白名单       │
└─────────────┘           指令 / 截图帧响应                  │  ├─ 进程/系统/文件/截图     │
              ▲                                             │  ├─ QQ 通知 (可选)          │
              └ 新设备请求 → 服务端控制台 或 QQ → 管理员授权    │  └─ 关机/重启              │
                                                             └────────────────────────────┘
```

- **协议**：服务端用扁平 JSON 消息（非 `{type:'command'}` 包裹），每条带可选 `seq`，响应回带同 `seq` 便于请求-响应配对。
- **屏幕截图**：服务端常驻 PowerShell 进程（`System.Drawing`）抓屏 → JPEG(base64) → **二进制帧**（5 字节魔数头 `SCRN\x01` + JPEG）推给观看者。零第三方依赖。

---

## 📁 目录结构

```
C:\远程控制\
├── README.md                本文件
├── server\                  服务端源码 (Node.js)
│   ├── control_server.js    主服务 (WebSocket, 鉴权, 关机/重启, 指令路由)
│   ├── rc_features.js       扩展功能 (系统信息/文件浏览/屏幕截图)
│   ├── qq_worker.js         QQ 队列发送 worker (可选通道)
│   ├── send_qq.ps1          QQ 发送 (通过 openclaw CLI)
│   ├── screenshot.ps1       屏幕截图常驻进程
│   ├── sysinfo.ps1          系统信息采集
│   ├── listproc.ps1         进程列表采集
│   ├── package.json         npm 依赖 (仅 ws)
│   └── whitelist.json       白名单 (部署时自动生成空模板)
├── scripts\
│   ├── deploy.ps1           一键部署 (可选 QQ 通道, 自动检测)
│   ├── auth.ps1             一键设备授权 (英文版)
│   └── 授权设备.ps1         一键设备授权 (中文版)
├── android\                 Android App 完整源码 (Kotlin + Gradle)
│   └── app\src\main\java\com\remotecontrol\app\
│       ├── MainActivity.kt          主页 (连接/进程/电源/功能入口)
│       ├── ScreenActivity.kt        屏幕实时观看
│       ├── SystemInfoActivity.kt    系统信息
│       ├── FileBrowserActivity.kt   文件浏览
│       ├── WsClient.kt              WebSocket 客户端 (鉴权/指令/自动重连/截图回调)
│       ├── RemoteControlApp.kt      Application 单例 (全局共享连接)
│       └── ProcessAdapter.kt        进程列表适配器
├── apk\
│   └── 远程控制-v1.2-release.apk    封装好的正式签名 APK
└── keystore\
    └── remote-control.keystore      签名密钥 (私有, 勿公开!)
```

---

## 🚀 快速开始（服务端一键部署）

> 需要 **Windows** + **Node.js 18+**（LTS）。服务端零第三方依赖（除 `ws`），无需管理员权限即可前台/后台运行。

```powershell
# 进入交付目录的 scripts 文件夹
cd C:\远程控制\scripts

# 一键部署（默认端口 8899, QQ 通道 auto 检测）
.\deploy.ps1

# 常用参数
.\deploy.ps1 -Port 9000          # 自定义端口
.\deploy.ps1 -QQ off             # 强制关闭 QQ 通知
.\deploy.ps1 -QQ on              # 强制开启 QQ 通知
.\deploy.ps1 -Service on         # 注册为 Windows 开机自启服务 (需 nssm + 管理员)
.\deploy.ps1 -Uninstall          # 卸载服务并停止
```

部署脚本会自动：
1. 检查 Node.js
2. 安装依赖 (`npm install`)
3. 生成空 `whitelist.json`
4. **检测 QQ 通道**：若检测到 `openclaw` CLI（QQ Bot 环境）则开启 QQ 通知，否则自动忽略（仅走服务端控制台授权）
5. 后台启动服务端

### 验证运行
```powershell
# 端口是否监听
Get-NetTCPConnection -LocalPort 8899 -State Listen

# 查看日志
Get-Content C:\远程控制\server\stdout.log -Tail 20
```

---

## 📱 手机 App 安装与连接

1. 把 `apk\远程控制-v1.2-release.apk` 装到 Android 手机（Android 8.0+ / API 26+）。
2. 打开 App，在「服务器地址」填入：
   - **局域网**：`ws://你的电脑IP:8899`
   - **公网**：`wss://你的域名:8899`（需先做 TLS 公网映射，见下）
3. 点「连接」。第一次会显示 **待授权绑定**，同时你的服务端控制台会打印设备序列号。
4. 在电脑上执行授权：
   ```powershell
   cd C:\远程控制\scripts
   .\auth.ps1 allow <序列号> 你的手机名
   ```
   或直接在服务端控制台输入 `允许 <序列号>`。
5. 手机上点「连接」→ 授权通过，即可使用全部功能。

---

## 🔔 QQ 通知通道（可选）

设备请求绑定 / 关机 / 重启时，可通过 **QQ 机器人** 把通知推送到你的 QQ。

### 原理
服务端把通知写入 `server\qq_queue\` 队列 → `qq_worker.js` 每 60 秒调用 `send_qq.ps1` → 通过 **openclaw CLI**（`openclaw message send --channel qqbot`）发送到指定 QQ。

### 开启条件
1. 已安装并配置 **OpenClaw**（含 qqbot 通道）——即本机存在 `openclaw.cmd` 且 QQ Bot 可用。
2. 设置通知目标（可选，默认取环境变量 `RC_QQ_TARGET`）：
   ```powershell
   $env:RC_QQ_TARGET = 'qqbot:c2c:<你的QQ openid>'
   ```

### 自动 / 强制开关
- **auto（默认）**：部署脚本自动检测 openclaw CLI，能用就开，不能用就自动忽略并提示。
- **on**：强制开启（即使没检测到也会尝试，失败只记日志不影响核心功能）。
- **off**：强制关闭。

> 关闭 QQ 通道不影响任何核心功能；此时设备授权走**服务端控制台**（`允许 <序列号>`）。

---

## 🔐 设备鉴权 & 安全模型

- **零凭据 APK**：App 内不含任何密码/密钥/令牌，反编译也无法获取可用凭据。
- **设备指纹**：`AndroidID + Build.FINGERPRINT` 的哈希，作为设备序列号（匿名、稳定、不可反推）。
- **服务端白名单**：只有 `whitelist.json` 中 `allow:true` 的序列号才能连接；未绑定设备进入「待授权」状态，等待管理员确认（QQ 或控制台）。
- **即时生效**：服务端每次连接都重新读取白名单，授权脚本改完**无需重启服务端**。
- **建议**：公网访问务必使用 **wss:// (TLS)**，避免明文 ws 被中间人截获。

---

## 🌐 公网访问（wss + TLS）

推荐用 **cloudflared Tunnel**（免费、无需开放端口）或 frp：

```bash
# cloudflared 例：把 8899 映射到公网域名
cloudflared tunnel route dns <tunnel-id> remote.example.com
# config.yml ingress 加一行:
#   - hostname: remote.example.com
#     service: http://127.0.0.1:8899
```

之后 App 地址填 `wss://remote.example.com`。**请在你的真实部署中使用自己的域名与证书**；本仓库配套 APK 默认服务器地址为占位符 `wss://YOUR_SERVER:8899`，你可在 App 内直接修改。

---

## 🔨 从源码构建 APK（可选）

需要：JDK 17+（推荐 21）、Android SDK（compileSdk 36）、Gradle 8.14。

```powershell
cd android
# 配置签名 (可选, 不配则打 debug 包)
#   1. 生成密钥: keytool -genkeypair -v -keystore remote-control.keystore -alias remotecontrol -keyalg RSA -keysize 2048 -validity 10950
#   2. 在同目录建 keystore.properties:
#        storePassword=你的密码
#        keyAlias=remotecontrol
#        keyPassword=你的密码
#   3. 把 remote-control.keystore 放到 android/ 根目录

# 打 release 包
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'
gradle :app:assembleRelease --no-daemon
# 输出: android\app\build\outputs\apk\release\app-release.apk
```

> **⚠️ 签名密钥 (keystore) 是私有文件**：丢失后无法发布相同签名的更新。公开仓库请务必通过 `.gitignore` 排除 `android/remote-control.keystore` 与 `android/keystore.properties`，只把 `keystore/` 里的密钥**私下**交给可信人员。

---

## 📡 协议速查（服务端）

客户端连接后先发 `hello` 鉴权，随后即可发指令。所有消息为 JSON，可选携带 `seq`，响应回带同 `seq`。

| 指令 | 请求 | 响应 |
|------|------|------|
| 握手 | `{type:'hello',serial,deviceName,deviceModel,androidVersion}` | `{type:'auth',ok,device}` 或 `{type:'auth',ok:false,pending:true,error}` |
| 心跳 | `{type:'ping'}` | `{type:'pong',time}` |
| 进程列表 | `{type:'process.list',search?}` | `{type:'process.list',processes:[{pid,name,cmd}]}` |
| 杀进程 | `{type:'process.kill',pid}` | `{type:'process.kill',ok}` 或 `{error}` |
| 系统信息 | `{type:'system.info'}` | `{type:'system.info',osName,cpuLoad,totalMem,freeMem,upTime,disks}` |
| 文件列表 | `{type:'file.list',path}` | `{type:'file.list',path,items:[{name,isDir,size}]}` |
| 屏幕观看 | `{type:'screen.start',fps}` | `{type:'screen.start',ok,fps}` + 二进制帧流 `SCRN\x01` + JPEG |
| 停止观看 | `{type:'screen.stop'}` | `{type:'screen.stop',ok}` |
| 关机/重启 | `{type:'shutdown'}` / `{type:'reboot'}` | `{type:'shutdown'/'reboot',ok,message}` |

---

## ❓ FAQ

- **进程列表空白 / 报"获取进程失败"？** 确保已连接且已授权；服务端需能执行 `listproc.ps1`。
- **能反向控制（操作）屏幕吗？** 不能。屏幕仅为**只读观看**，无任何鼠标/键盘通道，刻意保持最小能力。
- **服务端要常开吗？** 是的，服务端运行期间手机才能连接；可用 `-Service on` 注册开机自启。
- **换电脑/重装白名单？** `whitelist.json` 是唯一状态文件，备份它即可迁移授权。

---

## 📄 许可

本项目以 **GNU GPL-3.0** 授权发布，并附加 **非商用限制条款**（详见 `LICENSE`）：

- **非商业用途免费开源**：个人学习、研究、内部使用、非营利用途，可自由复制/修改/再分发（须遵守 GPL-3.0）。
- **开源≠商用**：将本项目免费开源供公众下载、使用不算商业用途；仅当第三方将其用于**营利目的**（销售、收费服务、集成进收费商业产品）时，才需作者书面授权。
- **商业用途需授权**：任何销售、集成进收费产品/商业服务、营利性部署，须事先获得作者书面授权。
- 该附加条款依 GPL-3.0 第 7 节 "Additional Terms" 加入，GPL 其余条款完整保留。

> ⚠️ `apk/`（正式 APK）与 `keystore/`（签名密钥）为**私有交付物**，不属开源范畴，请勿公开；`keystore.properties`、`remote-control.keystore` 已列入 `.gitignore`。

---

*Built with ❤️ for reliable, credential-free remote control.*
