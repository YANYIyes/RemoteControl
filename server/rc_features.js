// 远程控制 - 扩展功能模块: 系统信息 / 文件浏览 / 实时屏幕截图
// 由 control_server.js 挂接 (require + 注册 handleFeatures)
'use strict';
const { spawn, exec } = require('child_process');
const fs = require('fs');
const path = require('path');
const ROOT = __dirname;

// ---------- 通用: 跑命令 ----------
function run(cmd) {
  return new Promise((resolve) => {
    exec(cmd, { encoding: 'utf8', windowsHide: true }, (err, stdout, stderr) => {
      resolve({ code: err ? 1 : 0, stdout: (stdout || ''), stderr: (stderr || '') });
    });
  });
}

// ---------- 系统信息 ----------
async function systemInfo() {
  // 用独立 .ps1 文件采集 (避免内联 PowerShell 的引号/$ 转义地狱)
  const r = await run('powershell -NoProfile -ExecutionPolicy Bypass -File "' + path.join(ROOT, 'sysinfo.ps1') + '"');
  if (r.code !== 0) return { error: r.stderr || '获取系统信息失败' };
  try {
    const start = r.stdout.indexOf('{');
    const j = JSON.parse(r.stdout.slice(start).trim());
    return {
      ok: true,
      osName: j.osName, hostName: j.hostName, cpuName: j.cpuName,
      cpuLoad: j.cpuLoad, totalMem: j.totalMem, freeMem: j.freeMem,
      upTime: { days: j.upDays, hours: j.upHours, mins: j.upMins },
      disks: (j.disks || []).map(d => ({ drive: d.Drive, total: d.Total, free: d.Free }))
    };
  } catch (e) { return { error: '解析系统信息失败' }; }
}

// ---------- 文件浏览 ----------
async function fileList(dirPath) {
  const p = dirPath || 'C:/';
  let resolved;
  try {
    resolved = path.resolve(p);
    const st = fs.statSync(resolved);
    if (!st.isDirectory()) return { error: '不是目录: ' + resolved };
  } catch (e) { return { error: '无法访问: ' + p }; }
  try {
    const entries = fs.readdirSync(resolved, { withFileTypes: true });
    const items = entries.map(d => {
      let size = 0;
      try { if (d.isFile()) size = fs.statSync(path.join(resolved, d.name)).size; } catch (e) {}
      return {
        name: d.name,
        isDir: d.isDirectory(),
        size: d.isFile() ? size : 0,
        type: d.isFile() ? 'file' : (d.isDirectory() ? 'dir' : 'other')
      };
    });
    // 目录在前, 按名称排序
    items.sort((a, b) => (b.isDir - a.isDir) || a.name.localeCompare(b.name, 'zh-CN'));
    return { ok: true, path: resolved, items };
  } catch (e) { return { error: '读取目录失败: ' + e.message }; }
}

// ---------- 实时屏幕截图 ----------
// 常驻截图进程(懒启动), stdin 空行触发, stdout 收 base64
let shotProc = null;
let shotQueue = [];       // 等待接收的帧(chain 防止并发)
let busy = false;
const viewers = new Map(); // serial -> { ws, timer }

function ensureShotProc(log) {
  if (shotProc && shotProc.exitCode === null) return shotProc;
  // v2.x: 用 DXGI 高速截图 (screenshot_fast.py: 硬件捕获+并行编码+帧去重), 替代慢的 PowerShell GDI
  const script = fs.existsSync(path.join(ROOT, 'screenshot_fast.py')) ? path.join(ROOT, 'screenshot_fast.py') : path.join(ROOT, 'screenshot.ps1');
  const procPath = script.endsWith('.py');
  shotProc = spawn(procPath ? 'python' : 'powershell', procPath
      ? ['-u', script]
      : ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', script], {
    windowsHide: true, stdio: ['pipe', 'pipe', 'inherit']
  });
  shotProc.stdin.on('error', () => {});
  shotProc.on('close', (code) => {
    if (log) log('截图进程退出: ' + code, 'WARN');
    // 所有观看者终止
    viewers.forEach((v, s) => { clearInterval(v.timer); viewers.delete(s); });
  });
  return shotProc;
}

// 触发并返回一帧 (串行化)
function captureFrame(log) {
  return new Promise((resolve) => {
    const p = ensureShotProc(log);
    let out = '';
    const onData = (d) => {
      out += d.toString();
      const nl = out.indexOf('\n');
      if (nl >= 0) {
        const line = out.slice(0, nl).trim();
        p.stdout.removeListener('data', onData);
        if (!line) { resolve(null); return; }
        try { resolve(Buffer.from(line, 'base64')); }
        catch (e) { resolve(null); }
      }
    };
    p.stdout.on('data', onData);
    try { p.stdin.write('\n'); } catch (e) { resolve(null); }
    // 5s 超时
    setTimeout(() => { p.stdout.removeListener('data', onData); resolve(null); }, 5000);
  });
}

// 开始一个设备的实时观看: 每 intervalMs 抓一帧发给 ws
function startScreenView(ws, serial, intervalMs, log) {
  stopScreenView(serial);
  // v2.x: 支持高频 (最小 ~11ms ≈ 90fps), DXGI截图+帧去重(静止不发) 对带宽友好
  intervalMs = Math.max(11, Math.min(1000, intervalMs || 50));
  async function tick() {
    if (!ws || ws.readyState !== 1) { stopScreenView(serial); return; }
    const frame = await captureFrame(log);
    if (frame && ws.readyState === 1) {
      // 文本帧: 首0x53; 实际用二进制帧前4字节=长度? 直接用 binary + header
      // header: [0x53,0x43,0x52,0x4E] "SCRN" 4字节魔数 (可选用于标识)
      const header = Buffer.from([0x53, 0x43, 0x52, 0x4E, 0x01]);
      try { ws.send(Buffer.concat([header, frame])); } catch (e) {}
    }
  }
  // 立即抓一帧, 然后按间隔
  tick();
  const timer = setInterval(tick, intervalMs);
  viewers.set(serial, { ws, timer, intervalMs });
  return intervalMs;
}
function stopScreenView(serial) {
  const v = viewers.get(serial);
  if (v) { clearInterval(v.timer); viewers.delete(serial); }
}

// 外部关闭连接时清理
function cleanupViewer(ws) {
  viewers.forEach((v, s) => { if (v.ws === ws) { clearInterval(v.timer); viewers.delete(s); } });
}

// ---------- 主入口: 处理扩展指令 ----------
async function handleFeatures(msg, ws, serial, reply, log) {
  const type = msg.type;
  switch (type) {
    case 'system.info': {
      const info = await systemInfo();
      reply(Object.assign({ type: 'system.info', ok: !info.error }, info));
      return true;
    }
    case 'file.list': {
      const r = await fileList(msg.path);
      reply(Object.assign({ type: 'file.list' }, r));
      return true;
    }
    case 'screen.start': {
      const iv = startScreenView(ws, serial, msg.fps ? Math.round(1000 / msg.fps) : 200, log);
      log('屏幕实时观看启动: ' + serial + ' (fps≈' + Math.round(1000 / iv) + ')', 'INFO');
      reply({ type: 'screen.start', ok: true, fps: Math.round(1000 / iv) });
      return true;
    }
    case 'screen.stop': {
      stopScreenView(serial);
      reply({ type: 'screen.stop', ok: true });
      return true;
    }
    default:
      return false;
  }
}

module.exports = { handleFeatures, cleanupViewer };
