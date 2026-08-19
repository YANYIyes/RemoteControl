# ============================================================================
#  Remote Control - Device Authorization Tool
#  Usage:
#    .\auth.ps1 list                     show whitelist
#    .\auth.ps1 allow <serial> [name]    allow & bind a device
#    .\auth.ps1 reject <serial>          reject a device
#    (no args) show help + current whitelist
# ============================================================================
$ErrorActionPreference = 'Stop'

# Locate whitelist automatically: <repo>\server\whitelist.json
$wlFile = Join-Path (Split-Path -Parent $MyInvocation.MyCommand.Path) '..\server\whitelist.json'
$wlFile = [System.IO.Path]::GetFullPath($wlFile)
if (-not (Test-Path $wlFile)) {
    Write-Host "[ERROR] whitelist not found: $wlFile" -ForegroundColor Red
    Write-Host 'Run scripts\deploy.ps1 first, or check the directory layout.' -ForegroundColor Yellow
    exit 1
}

function Load-WL { Get-Content $wlFile -Raw -Encoding UTF8 | ConvertFrom-Json }
function Save-WL($wl) {
    # Write BOM-less UTF-8 (Node JSON.parse fails on BOM)
    $json = $wl | ConvertTo-Json -Depth 6
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($wlFile, $json, $utf8NoBom)
}

$cmd = $args[0]
if (-not $cmd) { $cmd = 'list' }

switch ($cmd.ToLower()) {
  'list' {
    Write-Host ''
    Write-Host '========== Whitelisted Devices =========='
    $wl = Load-WL
    $devs = $wl.devices
    $n = 0
    $devs.PSObject.Properties | ForEach-Object {
      $n++
      $d = $_.Value
      $flag = if ($d.allow) { 'ALLOW' } else { 'REJECT' }
      $name = if ($d.name) { $d.name } else { '-' }
      $model = if ($d.model) { $d.model } else { '-' }
      "{0}. [{1}] {2}  name:{3}  model:{4}" -f $n, $flag, $_.Name, $name, $model
    }
    if ($n -eq 0) { Write-Host '(no devices bound yet)' }
    Write-Host '=========================================='
    Write-Host 'Usage: .\auth.ps1 allow <serial> [name]   or   reject <serial>'
  }
  'allow' {
    $serial = $args[1]
    if (-not $serial) { Write-Host 'ERROR: missing serial. Usage: .\auth.ps1 allow <serial> [name]'; exit 1 }
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
    Write-Host "ALLOWED device: $serial  (name:$name)  -> saved to whitelist.json"
    Write-Host 'The running server picks this up on the device next connect (no restart needed).'
  }
  'reject' {
    $serial = $args[1]
    if (-not $serial) { Write-Host 'ERROR: missing serial. Usage: .\auth.ps1 reject <serial>'; exit 1 }
    $wl = Load-WL
    if ($wl.devices.PSObject.Properties[$serial]) {
      $wl.devices.PSObject.Properties[$serial].Value.allow = $false
    } else {
      $val = @{ serial = $serial; allow = $false; addedAt = (Get-Date -Format 'yyyy-MM-dd HH:mm:ss') }
      $wl.devices | Add-Member -NotePropertyName $serial -NotePropertyValue $val
    }
    Save-WL $wl
    Write-Host "REJECTED device: $serial"
  }
  default { Write-Host "Unknown command: $cmd (available: list / allow <serial> / reject <serial>)" }
}
