#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
RemoteControl Fast Screenshot - DXGI hardware capture + parallel JPEG encode (借鉴 FastDesk v2.0)
常驻进程: stdin 每收到一行就取最新帧 -> stdout 输出 base64 JPEG (帧去重: 无变化输出空行)
- DXGI (dxcam) 硬件捕获, 后台 120fps
- 6 线程并行 JPEG 编码(破单线程瓶颈)
- 帧去重 (画面无变化不重发)
- JPEG quality 55
协议与 screenshot.ps1 一致(空行触发 -> base64行), 无缝替换, App 端无需改动
"""
import sys
import io
import threading
import queue
import hashlib
import base64

from PIL import Image
import numpy as np

JPEG_QUALITY = 55
ENCODE_WORKERS = 6
MAX_FPS = 120

# --- capture backend ---
cam = None          # dxcam (DXGI)
sct = None          # mss fallback
HAS_DXGI = False
HAS_MSS = False
try:
    import dxcam
    HAS_DXGI = True
except Exception:
    HAS_DXGI = False
try:
    import mss
    HAS_MSS = True
except Exception:
    HAS_MSS = False

_running = True
_frame_queue = queue.Queue()   # raw numpy frames from capture -> encoders
_jpeg_queue = queue.Queue()    # encoded JPEG bytes -> sender
_latest = None                 # latest encoded frame (just produced)
_latest_lock = threading.Lock()
_prev_sig = None               # dedup signature of last SENT frame


def init_capture():
    global cam, sct, HAS_DXGI
    if HAS_DXGI:
        try:
            cam = dxcam.create(output_color="RGB")
            if cam is None:
                cam = dxcam.create()
            cam.start(target_fps=MAX_FPS, video_mode=False)
            print("[FastShot] DXGI capture @%dfps" % MAX_FPS, flush=True)
            return True
        except Exception as e:
            print("[FastShot] dxcam init fail: %s -> mss" % e, flush=True)
            cam = None
            HAS_DXGI = False
    if not HAS_DXGI and HAS_MSS:
        try:
            sct = mss.mss()
            print("[FastShot] mss capture", flush=True)
            return True
        except Exception as e:
            print("[FastShot] mss init fail: %s" % e, flush=True)
            return False
    return HAS_DXGI or sct is not None


def grab_latest_raw():
    """取最新 raw 帧(numpy RGB), 无则 None"""
    if cam is not None:
        try:
            return cam.get_latest_frame()
        except Exception:
            return None
    if sct is not None:
        try:
            shot = sct.grab(sct.monitors[1])
            return np.frombuffer(shot.rgb, dtype=np.uint8).reshape(shot.height, shot.width, 3)
        except Exception:
            return None
    return None


def _sig(frame):
    """帧指纹(采样哈希)用于去重"""
    try:
        return hashlib.md5(frame[::32, ::32].tobytes()).hexdigest()
    except Exception:
        return None


def _capture_worker():
    """后台抓帧: 从 dxcam 后台缓冲取最新, 交给编码队列"""
    last = None
    while _running:
        frame = grab_latest_raw()
        if frame is not None and frame is not last:
            try:
                _frame_queue.put(frame)
                last = frame
            except Exception:
                pass
        # 限速: 尽量贴近 MAX_FPS, 避免空转占 CPU
        time.sleep(1.0 / MAX_FPS)


def _encoder_worker():
    """一个编码线程: 取 raw -> JPEG -> jpeg队列"""
    while _running:
        try:
            frame = _frame_queue.get(timeout=0.05)
        except queue.Empty:
            continue
        try:
            buf = io.BytesIO()
            Image.fromarray(frame).save(buf, "JPEG", quality=JPEG_QUALITY)
            _jpeg_queue.put(buf.getvalue())
        except Exception:
            pass
        _frame_queue.task_done()


# import time (needed by _capture_worker)
import time


def maybe_latest_jpeg():
    """返回最新编码的 JPEG bytes; 帧去重: 与本帧签名相同则返回 b''"""
    global _prev_sig
    # 取编码队列里最新的
    jpeg = None
    got = []
    try:
        while True:
            got.append(_jpeg_queue.get_nowait())
    except queue.Empty:
        pass
    if got:
        jpeg = got[-1]
        # 计算签名(基于最新的 raw 帧)
        raw = grab_latest_raw()
        if raw is not None:
            sig = _sig(raw)
            if sig == _prev_sig:
                return b''       # 画面没变, 去重
            _prev_sig = sig
    return jpeg or b''


def main():
    global _running
    if not init_capture():
        print("[FastShot] ERROR: no capture backend", flush=True)
        sys.exit(1)
    print("[FastShot] Ready. blank line -> base64 JPEG. (q%d, %d encoders)" % (JPEG_QUALITY, ENCODE_WORKERS), flush=True)

    # 启动编码池
    for _ in range(ENCODE_WORKERS):
        threading.Thread(target=_encoder_worker, daemon=True).start()
    # 启动抓帧
    threading.Thread(target=_capture_worker, daemon=True).start()

    while _running:
        try:
            line = sys.stdin.readline()
        except Exception:
            break
        if line == '':
            break
        jpg = maybe_latest_jpeg()
        if jpg:
            sys.stdout.write(base64.b64encode(jpg).decode('ascii') + '\n')
            sys.stdout.flush()
        else:
            sys.stdout.write('\n')  # 去重/无帧
            sys.stdout.flush()


if __name__ == "__main__":
    main()