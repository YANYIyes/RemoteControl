# -*- coding: utf-8 -*-
# 远程控制 - 截图工具 (Python + mss)
# 常驻循环：stdin 读空行触发 -> 截全屏 -> stdout 输出 base64 JPEG
import sys
import base64
import io
from mss import mss
from PIL import Image

# 初始化（只一次）
sct = mss()

def get_screen_base64():
    monitor = sct.monitors[1] if len(sct.monitors) > 1 else sct.monitors[0]
    screenshot = sct.grab(monitor)
    img = Image.frombytes("RGB", screenshot.size, screenshot.bgra, "raw", "BGRX")
    buf = io.BytesIO()
    img.save(buf, format='JPEG', quality=75)
    return base64.b64encode(buf.getvalue()).decode('ascii')

if __name__ == '__main__':
    try:
        while True:
            # Windows 上 readline() 可能不返回，改用 read(1) 检测
            c = sys.stdin.read(1)
            if not c:
                break  # EOF
            # 读到换行符就触发
            if c == '\n' or c == '\r':
                try:
                    result = get_screen_base64()
                    sys.stdout.write(result + '\n')
                    sys.stdout.flush()
                except Exception as e:
                    sys.stderr.write(f"Shot error: {e}\n")
                    sys.stderr.flush()
                    sys.stdout.write('\n')
                    sys.stdout.flush()
    finally:
        sct.close()
