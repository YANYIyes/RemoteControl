# 测试截图核心功能 (UTF-8 BOM)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Windows.Forms

try {
  $bounds = [System.Windows.Forms.SystemInformation]::VirtualScreen
  Write-Host "VirtualScreen: $($bounds.Width) x $($bounds.Height) @ ($($bounds.Left),$($bounds.Top))"
  
  $bmp = New-Object System.Drawing.Bitmap $bounds.Width, $bounds.Height
  $g = [System.Drawing.Graphics]::FromImage($bmp)
  $g.CopyFromScreen($bounds.Location, [System.Drawing.Point]::Empty, $bounds.Size)
  $g.Dispose()
  
  $ms = New-Object System.IO.MemoryStream
  $bmp.Save($ms, [System.Drawing.Imaging.ImageFormat]::Jpeg)
  $bmp.Dispose()
  
  $bytes = $ms.ToArray()
  $ms.Dispose()
  
  Write-Host "✅ 截图成功：$($bytes.Length) 字节 (JPEG)"
  if ($bytes.Length -gt 3) {
    Write-Host "JPEG 头 (应 FFD8FF): $([BitConverter]::ToString($bytes[0..3]))"
  }
  
  # 保存到文件验证
  [System.IO.File]::WriteAllBytes("$env:TEMP\test_screen.jpg", $bytes)
  Write-Host "📁 已保存到: $env:TEMP\test_screen.jpg"
  
} catch {
  Write-Host "❌ 失败：$($_.Exception.Message)"
  Write-Host "堆栈：$($_.ScriptStackTrace)"
}
