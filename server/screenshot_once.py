# -*- coding: utf-8 -*-
# 远程控制 - 截图工具 (单次截图，非驻留)
# 用法：python screenshot_once.py
import base64
import io
from mss import mss
from PIL import Image

with mss() as sct:
    monitor = sct.monitors[1] if len(sct.monitors) > 1 else sct.monitors[0]
    screenshot = sct.grab(monitor)
    img = Image.frombytes("RGB", screenshot.size, screenshot.bgra, "raw", "BGRX")
    buf = io.BytesIO()
    img.save(buf, format='JPEG', quality=75)
    print(base64.b64encode(buf.getvalue()).decode('ascii'))
