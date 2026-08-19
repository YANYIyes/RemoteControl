# 远程控制 - 截图工具 (UTF-8 BOM)
# 常驻循环截图: 每次读取 stdin 空白行 -> 截全屏 -> 输出 JPEG base64 到 stdout
# 由 Node 端 spawn 驱动, 通过 stdin 触发, 避免每次启动 PowerShell 的开销
$ErrorActionPreference = 'SilentlyContinue'
Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Windows.Forms

# 截全屏 -> JPEG byte[] -> base64 单行输出
function Get-ScreenShotBase64 {
    $bounds = [System.Windows.Forms.SystemInformation]::VirtualScreen
    $bmp = New-Object System.Drawing.Bitmap $bounds.Width, $bounds.Height
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.CopyFromScreen($bounds.Location, [System.Drawing.Point]::Empty, $bounds.Size)
    $g.Dispose()
    $ms = New-Object System.IO.MemoryStream
    # JPEG 质量 75 (平衡体积与清晰度)
    $enc = [System.Drawing.Imaging.ImageCodecInfo]::GetImageEncoders() | Where-Object { $_.MimeType -eq 'image/jpeg' }
    $ep = New-Object System.Drawing.Imaging.EncoderParameters 1
    $ep.Param[0] = New-Object System.Drawing.Imaging.EncoderParameter([System.Drawing.Imaging.Encoder]::Quality, [long]75)
    $bmp.Save($ms, $enc, $ep)
    $bmp.Dispose()
    $bytes = $ms.ToArray()
    $ms.Dispose()
    return [Convert]::ToBase64String($bytes)
}

# 常驻循环
while ($true) {
    $line = [Console]::In.ReadLine()
    if ($null -eq $line) { break }
    $s = Get-ScreenShotBase64
    [Console]::Out.WriteLine($s)
    [Console]::Out.Flush()
}
