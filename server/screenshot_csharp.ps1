# 远程控制 - 截图工具 (C# 内联)
# 常驻循环：stdin 空行触发 -> 截全屏 -> stdout base64 JPEG

$code = @'
using System;
using System.IO;
using System.Drawing;
using System.Drawing.Imaging;
using System.Windows.Forms;

public class DxgiCapture {
    public static byte[] CaptureScreen() {
        try {
            Rectangle bounds = Screen.PrimaryScreen.Bounds;
            Bitmap bitmap = new Bitmap(bounds.Width, bounds.Height);
            Graphics g = Graphics.FromImage(bitmap);
            g.CopyFromScreen(new Point(0, 0), new Point(0, 0), bounds.Size);
            g.Dispose();
            
            MemoryStream ms = new MemoryStream();
            bitmap.Save(ms, ImageFormat.Jpeg);
            bitmap.Dispose();
            return ms.ToArray();
        } catch (Exception ex) {
            throw new Exception("Capture failed: " + ex.Message);
        }
    }
}
'@

try {
    Add-Type -TypeDefinition $code -Language CSharp -ReferencedAssemblies System.Drawing.dll,System.Windows.Forms.dll -ErrorAction Stop
    Write-Host "C# class loaded"
    
    while ($true) {
        $line = [Console]::In.ReadLine()
        if ($null -eq $line) { break }
        
        try {
            $bytes = [DxgiCapture]::CaptureScreen()
            $b64 = [Convert]::ToBase64String($bytes)
            [Console]::Out.WriteLine($b64)
            [Console]::Out.Flush()
            Write-Host "Shot: $($bytes.Length) bytes"
        } catch {
            Write-Host "Error: $($_.Exception.Message)"
            [Console]::Out.WriteLine()
            [Console]::Out.Flush()
        }
    }
} catch {
    Write-Host "Init failed: $($_.Exception.Message)"
}
