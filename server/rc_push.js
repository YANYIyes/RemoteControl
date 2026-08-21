// 远程控制 v2.0 - 主动推送模块
// 功能: CPU 告警巡检 / 进程消失监控 / 定时关机调度
// 由 control_server.js 挂接 (require + registerDevice + start)
'use strict';
const { exec, spawn } = require('child_process');
const fs = require('fs');
const path = require('path');

const ROOT = __dirname;

// ---------- 配置 (环境变量可覆盖) ----------
const INTERVAL = Number(process.env.RC_PUSH_INTERVAL || 20);        // 巡检间隔(秒)
const CPU_ALERT_THRESHOLD = Number(process.env.RC_CPU_THRESHOLD || 90); // CPU 阈值(%)
const CPU_ALERT_CONSECUTIVE = Number(process.env.RC_CPU_N || 3);    // 连续几次超阈值才告警
const STATE_FILE = path.join(ROOT, 'monitor_state.json');

// ---------- 已连接设备表: serial -> { ws, config } ----------
const devices = new Map();

// ---------- 工具 ----------
function run(cmd) {
  return new Promise((resolve) => {
    exec(cmd, { encoding: 'utf8', windowsHide: true }, (err, stdout, stderr) => {
      resolve({ code: err ? 1 : 0, stdout: (stdout || ''), stderr: (stderr || '') });
    });
  });
}
function defaultConfig() {
  return {
    notifyEnabled: true,     // 总开关
    cpuThreshold: CPU_ALERT_THRESHOLD,
    cpuNotify: true,         // CPU 告警开关
    procNotify: true,        // 进程消失告警开关
    monitored: []            // 关注进程名列表
  };
}
function loadState() {
  try {
    if (fs.existsSync(STATE_FILE)) {
      const j = JSON.parse(fs.readFileSync(STATE_FILE, 'utf8'));
      if (j && j.devices) return j.devices;
    }
  } catch (e) {}
  return {};
}
function saveState() {
  try {
    const out = { devices: {} };
    devices.forEach((v, serial) => { out.devices[serial] = v.config || {}; });
    fs.writeFileSync(STATE_FILE, JSON.stringify(out, null, 2), 'utf8');
  } catch (e) {}
}

// ---------- 设备注册/注销/配置 ----------
function registerDevice(serial, ws) {
  const prev = devices.get(serial);
  const cfg = (prev && prev.config) || loadState()[serial] || defaultConfig();
  devices.set(serial, { ws, config: cfg });
}
function unregisterDevice(ws) {
  devices.forEach((v, serial) => { if (v.ws === ws) devices.delete(serial); });
  saveState();
}
function setConfig(serial, cfg) {
  const d = devices.get(serial);
  if (!d) return false;
  d.config = Object.assign(defaultConfig(), d.config || {}, cfg || {});
  saveState();
  return true;
}
function getConfig(serial) {
  const d = devices.get(serial);
  return d ? Object.assign({}, d.config) : null;
}

// ---------- 推送 ----------
function push(serial, type, text) {
  const d = devices.get(serial);
  if (!d || !d.ws || d.ws.readyState !== 1) return false;
  if (d.config && d.config.notifyEnabled === false) return false;
  try {
    d.ws.send(JSON.stringify({
      type, push: true, text,
      time: new Date().toISOString()
    }));
    return true;
  } catch (e) { return false; }
}

// ---------- CPU 告警 ----------
let cpuOverCount = 0;
async function checkCpu() {
  const r = await run('powershell -NoProfile -ExecutionPolicy Bypass -File "' + path.join(ROOT, 'sysinfo.ps1') + '"');
  if (r.code !== 0) return;
  let load = 0;
  try {
    const start = r.stdout.indexOf('{');
    const j = JSON.parse(r.stdout.slice(start).trim());
    load = Number(j.cpuLoad) || 0;
  } catch (e) { return; }

  let anyOver = false;
  devices.forEach((d, serial) => {
    if (!(d.config && d.config.cpuNotify)) return;
    const thr = d.config.cpuThreshold || CPU_ALERT_THRESHOLD;
    if (load >= thr) anyOver = true;
  });
  if (anyOver) {
    cpuOverCount++;
    if (cpuOverCount >= CPU_ALERT_CONSECUTIVE) {
      devices.forEach((d, serial) => {
        if (!(d.config && d.config.cpuNotify)) return;
        const thr = d.config.cpuThreshold || CPU_ALERT_THRESHOLD;
        if (load >= thr) push(serial, 'push.cpu_alert', '⚠️ CPU 持续高负载: ' + load + '% (连续 ' + cpuOverCount + ' 次超过 ' + thr + '%)');
      });
      cpuOverCount = 0; // 重置, 避免每轮重复推
    }
  } else {
    cpuOverCount = 0;
  }
}

// ---------- 进程消失监控 ----------
async function checkProcesses() {
  const watchList = new Set();
  devices.forEach((d) => {
    if (!(d.config && d.config.procNotify)) return;
    (d.config.monitored || []).forEach(n => watchList.add(n));
  });
  if (watchList.size === 0) return;

  const r = await run('powershell -NoProfile -ExecutionPolicy Bypass -File "' + path.join(ROOT, 'listproc.ps1') + '"');
  if (r.code !== 0) return;
  let running = new Set();
  try {
    let arr = JSON.parse(r.stdout);
    if (!Array.isArray(arr)) arr = [arr];
    running = new Set(arr.map(p => p.Name));
  } catch (e) { return; }

  devices.forEach((d, serial) => {
    if (!(d.config && d.config.procNotify)) return;
    const missing = (d.config.monitored || []).filter(n => !running.has(n));
    if (missing.length > 0) {
      push(serial, 'push.process_gone', '💀 监控的进程已停止: ' + missing.join(', '));
    }
  });
}

// ---------- 系统信息实时推送 (v2.0) ----------
// 设备打开系统信息页时, 服务端每秒采集一次 sysinfo 并 push 给该设备
const sysInfoTimers = new Map(); // serial -> interval

async function collectSysInfo() {
  const r = await run('powershell -NoProfile -ExecutionPolicy Bypass -File "' + path.join(ROOT, 'sysinfo.ps1') + '"');
  if (r.code !== 0) return null;
  try {
    const start = r.stdout.indexOf('{');
    return JSON.parse(r.stdout.slice(start).trim());
  } catch (e) { return null; }
}

function pushSysInfo(serial) {
  collectSysInfo().then(info => {
    if (!info) return;
    const d = devices.get(serial);
    if (!d || !d.ws || d.ws.readyState !== 1) return;
    try {
      d.ws.send(JSON.stringify({ type: 'push.sysinfo', push: true, info, time: new Date().toISOString() }));
    } catch (e) {}
  });
}

function startSysInfoPush(serial) {
  if (sysInfoTimers.has(serial)) return { ok: true, already: true };
  const id = setInterval(() => pushSysInfo(serial), 1000); // 每秒实时
  sysInfoTimers.set(serial, id);
  pushSysInfo(serial); // 立即推一次
  return { ok: true };
}
function stopSysInfoPush(serial) {
  const id = sysInfoTimers.get(serial);
  if (id) { clearInterval(id); sysInfoTimers.delete(serial); }
  return { ok: true };
}
function stopSysInfoAll() {
  sysInfoTimers.forEach(id => clearInterval(id));
  sysInfoTimers.clear();
}

// ---------- 定时关机调度 ----------
// schedules: [{ serial, time: Date, repeat: 'once'|'daily' }]
const schedules = [];
function addSchedule(serial, timeStr) {
  const m = /^(\d{1,2}):(\d{2})$/.exec(String(timeStr).trim());
  if (m) {
    const now = new Date();
    const t = new Date(now.getFullYear(), now.getMonth(), now.getDate(), Number(m[1]), Number(m[2]), 0, 0);
    if (t <= now) t.setDate(t.getDate() + 1);
    // 同设备只保留一个 daily 调度
    for (let i = schedules.length - 1; i >= 0; i--) {
      if (schedules[i].serial === serial && schedules[i].repeat === 'daily') schedules.splice(i, 1);
    }
    schedules.push({ serial, time: t, repeat: 'daily' });
    return { ok: true, message: '已设定每天 ' + m[1] + ':' + m[2] + ' 定时关机', at: t.toISOString(), repeat: 'daily' };
  }
  const t = new Date(timeStr);
  if (!isNaN(t.getTime())) {
    for (let i = schedules.length - 1; i >= 0; i--) {
      if (schedules[i].serial === serial && schedules[i].repeat === 'once') schedules.splice(i, 1);
    }
    schedules.push({ serial, time: t, repeat: 'once' });
    return { ok: true, message: '已设定一次性定时关机: ' + t.toLocaleString('zh-CN'), at: t.toISOString(), repeat: 'once' };
  }
  return { ok: false, error: '时间格式错误: 用 HH:MM(每天) 或 ISO 时间(一次性)' };
}
function listSchedules(serial) {
  return schedules.filter(s => s.serial === serial).map(s => ({ time: s.time.toISOString(), repeat: s.repeat }));
}
function cancelSchedule(serial) {
  let n = 0;
  for (let i = schedules.length - 1; i >= 0; i--) {
    if (schedules[i].serial === serial) { schedules.splice(i, 1); n++; }
  }
  return { ok: true, removed: n };
}
function tickSchedules(log) {
  const now = Date.now();
  for (let i = schedules.length - 1; i >= 0; i--) {
    const s = schedules[i];
    if (now >= s.time.getTime()) {
      push(s.serial, 'push.scheduled_shutdown', '🕐 定时关机时间到! 10 秒后关机');
      if (log) log('定时关机触发: ' + s.serial, 'WARN');
      spawn('shutdown', ['/s', '/t', '10', '/c', '远程控制: 定时关机'], { windowsHide: true, detached: true }).unref();
      if (s.repeat === 'daily') {
        s.time = new Date(s.time.getTime() + 24 * 3600 * 1000); // 明天
      } else {
        schedules.splice(i, 1);
      }
    }
  }
}

// ---------- 启动/停止 ----------
let timers = [];
function start(log) {
  timers.push(setInterval(() => checkCpu(), INTERVAL * 1000));
  timers.push(setInterval(() => checkProcesses(), INTERVAL * 1000));
  timers.push(setInterval(() => tickSchedules(log), 5000));
  // 立即跑一轮
  checkCpu();
  checkProcesses();
  if (log) log('推送模块启动: CPU阈值 ' + CPU_ALERT_THRESHOLD + '%, 巡检每 ' + INTERVAL + 's');
}
function stop() {
  timers.forEach(t => clearInterval(t));
  timers = [];
  stopSysInfoAll();
}

module.exports = {
  registerDevice, unregisterDevice, setConfig, getConfig,
  push, start, stop,
  addSchedule, listSchedules, cancelSchedule,
  startSysInfoPush, stopSysInfoPush
};