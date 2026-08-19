# ============================================================================
#  远程控制服务端 - 一键部署脚本 (Windows)
#  用法:
#    .\deploy.ps1                     # 默认: 端口8899, QQ=auto, 后台运行
#    .\deploy.ps1 -Port 9000          # 指定端口
#    .\deploy.ps1 -QQ off             # 强制关闭 QQ 通知通道
#    .\deploy.ps1 -QQ on              # 强制开启 (需已配置 openclaw/QQBot)
#    .\deploy.ps1 -Service on         # 注册为开机自启 Windows 服务 (nssm, 需管理员)
#    .\deploy.ps1 -Uninstall          # 卸载服务(若注册过) 并停止
# ============================================================================
param(
    [int]$Port = 8899,
    [ValidateSet('on','off','auto')]
    [string]$QQ = 'auto',
    [ValidateSet('on','off')]
    [string]$Service = 'off',
    [switch]$Uninstall
)

$ErrorActionPreference = 'Stop'
$ServerDir = Split-Path -Parent $MyInvocation.MyCommand.Path | Join-Path -ChildPath '..'
$ServerDir = [System.IO.Path]::GetFullPath($ServerDir)
$ServerJs  = Join-Path $ServerDir 'server\control_server.js'
$qqTarget  = $env:RC_QQ_TARGET

function Color($c, $msg) { Write-Host $msg -ForegroundColor $c }
function Info($m)  { Color 'Cyan'    "[信息] $m" }
function Warn($m)  { Color 'Yellow'  "[警告] $m" }
function Ok($m)    { Color 'Green'   "[完成] $m" }
function Err($m)   { Color 'Red'     "[错误] $m" }

# ---------- 0. 卸载模式 ----------
if ($Uninstall) {
    $svc = Get-Service -Name 'RemoteControlSrv' -ErrorAction SilentlyContinue
    if ($svc) {
        & nssm stop RemoteControlSrv 2>$null | Out-Null
        & nssm remove RemoteControlSrv confirm 2>$null | Out-Null
        Ok '已停止并卸载服务 RemoteControlSrv'
    } else {
        Warn '未找到服务 RemoteControlSrv'
    }
    # 停止当前 node 服务端
    Get-CimInstance Win32_Process -Filter "Name='node.exe'" |
        Where-Object { $_.CommandLine -like '*control_server.js*' } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    Ok '已停止运行中的服务端进程'
    exit 0
}

# ---------- 1. 检查 Node.js ----------
Info '检查 Node.js ...'
if (-not (Get-Command node -ErrorAction SilentlyContinue)) {
    Err '未检测到 Node.js，请先安装: https://nodejs.org (LTS)'
    exit 1
}
$nodeVer = node -v
Ok "Node.js $nodeVer"

# ---------- 2. 安装依赖 ----------
if (Test-Path (Join-Path $ServerDir 'server\package.json')) {
    Info '安装服务端依赖 (npm install) ...'
    Push-Location (Join-Path $ServerDir 'server')
    npm install --no-audit --no-fund 2>$null | Out-Null
    if ($LASTEXITCODE -ne 0) { Warn 'npm install 有警告(可能离线), 尝试继续' }
    Pop-Location
    Ok '依赖安装完成'
}

# ---------- 3. 生成白名单模板 ----------
$wlFile = Join-Path $ServerDir 'server\whitelist.json'
if (-not (Test-Path $wlFile)) {
    $tpl = '{"devices":{}}'
    [System.IO.File]::WriteAllText($wlFile, $tpl, [System.Text.UTF8Encoding]::new($false))
    Info '已生成空白名单 whitelist.json (首次需用 授权 脚本绑定设备)'
} else {
    Ok '白名单已存在，保留'
}

# ---------- 4. 检测/配置 QQ 通道 ----------
function Test-QQChannel {
    # openclaw CLI 是 QQ 通知的唯一依赖
    foreach ($c in @((Join-Path $env:USERPROFILE 'AppData\Roaming\npm\openclaw.cmd'),
                     (Join-Path $env:APPDATA 'npm\openclaw.cmd'),
                     'openclaw.cmd')) {
        if ($c -and (Test-Path -LiteralPath $c)) { return $true }
    }
    return $false
}
$qqState = $QQ
$qqEnv = ''
if ($qqTarget) { $qqEnv = " (目标: $qqTarget)" }
if ($QQ -eq 'auto') {
    if (Test-QQChannel) { $qqState = 'on';   Ok "QQ 通道已自动检测到开启$qqEnv" }
    else                { $qqState = 'off';  Warn '未检测到 openclaw/QQBot 环境，QQ 通知已自动忽略' }
    $qqState = $qqState
} elseif ($QQ -eq 'on') {
    if (Test-QQChannel) { Ok "QQ 通道强制开启$qqEnv" }
    else { Warn '你强制开启 QQ，但未检测到 openclaw CLI；通知会写入队列但无法发送' }
} else {
    Warn 'QQ 通道已按要求关闭'
}
$env:RC_QQ_ENABLED = $qqState

# ---------- 5. 启动(或注册服务) ----------
function Stop-Running {
    Get-CimInstance Win32_Process -Filter "Name='node.exe'" |
        Where-Object { $_.CommandLine -like '*control_server.js*' } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
}
Stop-Running

if ($Service -eq 'on') {
    # 用 nssm 注册为 Windows 服务 (开机自启)
    if (-not (Get-Command nssm -ErrorAction SilentlyContinue)) {
        Err '未找到 nssm，无法注册为服务。请安装 nssm 或改用后台运行 (-Service off)'
        exit 1
    }
    $nodeExe = (Get-Command node).Source
    $args = Join-Path $ServerDir 'server\control_server.js'
    & nssm install RemoteControlSrv $nodeExe $args | Out-Null
    & nssm set RemoteControlSrv AppDirectory (Join-Path $ServerDir 'server') | Out-Null
    & nssm set RemoteControlSrv AppEnvironmentExtra "RC_PORT=$Port" "RC_QQ_ENABLED=$qqState" | Out-Null
    & nssm start RemoteControlSrv | Out-Null
    Ok "服务 RemoteControlSrv 已注册并启动 (端口 $Port)"
} else {
    # 后台运行 (hidden, 输出到日志)
    $nodeExe = (Get-Command node).Source
    $logOut = Join-Path $ServerDir 'server\stdout.log'
    $logErr = Join-Path $ServerDir 'server\stderr.log'
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $nodeExe
    $psi.Arguments = '"' + $ServerJs + '"'
    $psi.WorkingDirectory = Join-Path $ServerDir 'server'
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $false
    $psi.RedirectStandardError = $false
    $psi.CreateNoWindow = $true
    $psi.EnvironmentVariables['RC_PORT'] = "$Port"
    $psi.EnvironmentVariables['RC_QQ_ENABLED'] = $qqState
    $env:RC_PORT = "$Port"
    $env:RC_QQ_ENABLED = $qqState
    $p = [System.Diagnostics.Process]::Start($psi)
    Start-Sleep -Seconds 3
    Ok "服务端已后台启动 (PID $($p.Id), 端口 $Port, QQ=$qqState)"
}

# ---------- 6. 结果输出 ----------
Write-Host ''
Ok '部署完成！关键信息：'
Write-Host '  ─────────────────────────────────────────────'
Write-Host ('  服务端地址   : ws://本机IP:' + $Port + '   (APK 填 wss://你的域名:' + $Port + ' 或局域网地址)')
Write-Host ('  QQ 通知     : ' + $qqState)
Write-Host '  授权设备     : 在 server 目录运行 授权设备.ps1  或  在服务端控制台输入  允许 <序列号>'
Write-Host '  查看日志     : server\stdout.log'
Write-Host '  ─────────────────────────────────────────────'
Write-Host '  首次使用: 手机装 APK → 填服务器地址 → 连接 → 会显示"待授权"'
Write-Host '            → 回到本机运行授权脚本完成绑定'
Write-Host ''
Write-Host '提示: 若要让外网访问，请用 cloudflared/frp 等把端口映射到公网，并让 APK 使用 wss:// (TLS)。'
Write-Host ''
