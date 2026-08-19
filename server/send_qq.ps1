param(
    [string]$Target = 'qqbot:c2c:EFED30149FAD116D7B3E8BC81E4DF24C',
    [string]$Message = '',
    [string]$MessageFile = ''
)
$ErrorActionPreference = 'SilentlyContinue'
# 自动定位 openclaw CLI (批处理入口, 支持 PATH 或全局 npm)
$cli = ''
$candidates = @(
    (Join-Path $env:USERPROFILE 'AppData\Roaming\npm\openclaw.cmd'),
    (Join-Path $env:APPDATA 'npm\openclaw.cmd'),
    'openclaw.cmd'
)
foreach ($c in $candidates) { if ($c -and (Test-Path -LiteralPath $c)) { $cli = $c; break } }
if (-not $cli) {
    $r = (Get-Command openclaw -ErrorAction SilentlyContinue)
    if ($r) { $cli = $r.Source }
}
if (-not $cli) { Write-Output 'NO_CLI'; exit 1 }

# 消息文本: 优先从文件读取(绕过 PowerShell 命令行转义问题), 否则用 -Message 参数
if ($MessageFile -and (Test-Path -LiteralPath $MessageFile)) {
    $body = Get-Content -LiteralPath $MessageFile -Raw -Encoding UTF8
} else {
    $body = $Message
}
$text = $body

# 显式用户环境(openclaw CLI 需 gateway token)
$env:USERPROFILE = $env:USERPROFILE
$env:APPDATA = Join-Path $env:USERPROFILE 'AppData\Roaming'
$env:LOCALAPPDATA = Join-Path $env:USERPROFILE 'AppData\Local'

$sb = {
    param($c, $t, $m)
    $env:USERPROFILE = $env:USERPROFILE
    $env:APPDATA = Join-Path $env:USERPROFILE 'AppData\Roaming'
    $env:LOCALAPPDATA = Join-Path $env:USERPROFILE 'AppData\Local'
    $r = & $c message send --channel qqbot --target $t --message $m --json 2>&1
    [pscustomobject]@{ ExitCode = $LASTEXITCODE; Output = ($r -join ' ') }
}

$job = Start-Job -ScriptBlock $sb -ArgumentList $cli, $Target, $text

if (Wait-Job -Job $job -Timeout 30) {
    $res = Receive-Job -Job $job
    Write-Output ('SENT(' + $res.ExitCode + '): ' + $res.Output)
} else {
    Stop-Job -Job $job -ErrorAction SilentlyContinue
    Write-Output 'TIMEOUT(可能已送达)'
}
Remove-Job -Job $job -Force -ErrorAction SilentlyContinue
