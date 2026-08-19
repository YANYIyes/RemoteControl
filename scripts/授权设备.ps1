# 远程控制 - 设备授权工具 (UTF-8 BOM)
# 用法:
#   .\授权设备.ps1 list                 列出当前白名单
#   .\授权设备.ps1 allow <序列号> [名称] 授权并绑定设备
#   .\授权设备.ps1 reject <序列号>       拒绝设备
#   (无参数) 显示帮助 + 当前白名单
$ErrorActionPreference = 'Stop'
# 自动定位白名单: 脚本所在目录的上级/server/whitelist.json
$wlFile = Join-Path (Split-Path -Parent $MyInvocation.MyCommand.Path) '..\server\whitelist.json'
$wlFile = [System.IO.Path]::GetFullPath($wlFile)
if (-not (Test-Path $wlFile)) {
    Write-Host ('[错误] 未找到白名单: ' + $wlFile) -ForegroundColor Red
    Write-Host '请先运行 scripts\deploy.ps1, 或确认目录结构正确。' -ForegroundColor Yellow
    exit 1
}

function Load-WL { Get-Content $wlFile -Raw -Encoding UTF8 | ConvertFrom-Json }
function Save-WL($wl) {
    # 写无 BOM 的 UTF-8 (Node JSON.parse 无法解析带 BOM 的 JSON)
    $json = $wl | ConvertTo-Json -Depth 6
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($wlFile, $json, $utf8NoBom)
}

$cmd = $args[0]
if (-not $cmd) { $cmd = 'list' }

switch ($cmd.ToLower()) {
  'list' {
    echo ''
    echo '========== 已绑定设备 =========='
    $wl = Load-WL
    $devs = $wl.devices
    $n = 0
    $devs.PSObject.Properties | ForEach-Object {
      $n++
      $d = $_.Value
      $flag = if ($d.allow) { '允许' } else { '拒绝' }
      $name = if ($d.name) { $d.name } else { '-' }
      $model = if ($d.model) { $d.model } else { '-' }
      "{0}. [{1}] {2}  名称:{3}  型号:{4}" -f $n, $flag, $_.Name, $name, $model
    }
    if ($n -eq 0) { echo '(当前无绑定设备)' }
    echo '================================='
    echo "用法: .\授权设备.ps1 allow <序列号> [名称]  或  reject <序列号>"
  }
  'allow' {
    $serial = $args[1]
    if (-not $serial) { echo '错误: 缺少序列号, 用法: .\授权设备.ps1 allow <序列号> [名称]'; exit 1 }
    $wl = Load-WL
    $existing = $null
    if ($wl.devices.PSObject.Properties[$serial]) { $existing = $wl.devices.PSObject.Properties[$serial].Value }
    $name = $args[2]
    if (-not $name -and $existing) { $name = $existing.name }
    $model = if ($existing) { $existing.model } else { $null }
    $val = @{ serial = $serial; name = $name; model = $model; allow = $true; addedAt = (Get-Date -Format 'yyyy-MM-dd HH:mm:ss') }
    if ($wl.devices.PSObject.Properties[$serial]) { $wl.devices.PSObject.Properties[$serial].Value = $val }
    else { $wl.devices | Add-Member -NotePropertyName $serial -NotePropertyValue $val }
    Save-WL $wl
    echo "已授权设备: $serial  (名称:$name) => 保存到 whitelist.json"
    echo '运行中的服务端将在该设备下次连接时自动生效'
  }
  'reject' {
    $serial = $args[1]
    if (-not $serial) { echo '错误: 缺少序列号, 用法: .\授权设备.ps1 reject <序列号>'; exit 1 }
    $wl = Load-WL
    if ($wl.devices.PSObject.Properties[$serial]) {
      $wl.devices.PSObject.Properties[$serial].Value.allow = $false
    } else {
      $val = @{ serial = $serial; allow = $false; addedAt = (Get-Date -Format 'yyyy-MM-dd HH:mm:ss') }
      $wl.devices | Add-Member -NotePropertyName $serial -NotePropertyValue $val
    }
    Save-WL $wl
    echo "已拒绝设备: $serial"
  }
  default { echo "未知命令: $cmd (可用: list / allow <序列号> / reject <序列号>)" }
}
