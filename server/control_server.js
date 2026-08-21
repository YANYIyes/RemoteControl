/**
 * Remote Control Server v2.0 (远程控制服务端)
 * -------------------------------------------------
 * 功能：
 *   - 通过 WebSocket 接收 Android APK 的连接
 *   - 设备鉴权：来源访问 = 设备序列号指纹 + 服务端白名单
 *   - 设备初次授权：终端确认（可 --approve/--reject 自动授权）
 *   - 操作：关机 / 重启 / 列出进程 / 杀进程 / 系统信息 / 文件浏览 / 屏幕截图
 *   - 主动推送：CPU 告警 / 进程消失告警 / 定时关机提醒（v2.0 新增，无 QQ 依赖）
 *   - 定时关机调度：App 下发 HH:MM(每天) 或 ISO(一次性)，服务端到点执行
 *
 * 安全边界：APK 不内置任何凭据；不在白名单的设备一律拒绝。
 * 部署：本机运行 node control_server.js
 */
const WebSocket = require('ws');
const { exec, spawn } = require('child_process');
const fs = require('fs');
const path = require('path');
const { handleFeatures, cleanupViewer } = require('./rc_features.js');
const push = require('./rc_push.js');

// ---------- 配置 ----------
const ROOT = __dirname;
const PORT = Number(process.env.RC_PORT || 8899);
const WHITELIST_FILE = path.join(ROOT, 'whitelist.json');
const LOG_FILE = path.join(ROOT, 'control.log');
const MAX_LOG_BYTES = 2 * 1024 * 1024; // 2MB 轮转

// ---------- 工具 ----------
function ts() { return new Date().toISOString().replace('T', ' ').replace('Z', ''); }
function log(msg, level = 'INFO') {
  const line = `[${ts()}] [${level}] ${msg}`;
  console.log(line);
  try {
    fs.appendFileSync(LOG_FILE, line + '\n', 'utf8');
    rotateLog();
  } catch (e) { /* 忽略日志写入错误 */ }
}
function rotateLog() {
  try {
    if (fs.existsSync(LOG_FILE) && fs.statSync(LOG_FILE).size > MAX_LOG_BYTES) {
      fs.renameSync(LOG_FILE, LOG_FILE + '.old');
    }
  } catch (e) {}
}

// ---------- 白名单 ----------
function readWlFile() {
  let s = fs.readFileSync(WHITELIST_FILE, 'utf8');
  if (s.charCodeAt(0) === 0xFEFF) s = s.slice(1);
  return s;
}
function loadWhitelist() {
  try {
    if (fs.existsSync(WHITELIST_FILE)) {
      const data = JSON.parse(readWlFile());
      if (data && typeof data === 'object' && data.devices) return data;
    }
  } catch (e) { log('白名单解析失败: ' + e.message, 'ERROR'); }
  return { devices: {}, defaultAllow: false };
}
function saveWhitelist(wl) {
  fs.writeFileSync(WHITELIST_FILE, JSON.stringify(wl, null, 2), 'utf8');
}
let wl = loadWhitelist();

function isAllowed(serial) {
  // 每次重读磁盘, 使外部修改即时生效
  try {
    const fresh = JSON.parse(readWlFile());
    const d = fresh.devices ? fresh.devices[serial] : undefined;
    return !!(d && d.allow === true);
  } catch (e) {
    const d = wl.devices[serial];
    return !!(d && d.allow === true);
  }
}
const pendingAuth = {}; // serial -> { info, timer }

// ---------- 系统操作 ----------
function run(cmd) {
  return new Promise((resolve) => {
    exec(cmd, { encoding: 'utf8', windowsHide: true }, (err, stdout, stderr) => {
      resolve({ code: err ? 1 : 0, stdout: (stdout || '').trim(), stderr: (stderr || '').trim() });
    });
  });
}
async function listProcesses(search) {
  const r = await run('powershell -NoProfile -ExecutionPolicy Bypass -File "' + path.join(ROOT, 'listproc.ps1') + '"');
  if (r.code !== 0) return { error: r.stderr || '获取进程失败' };
  try {
    let arr = JSON.parse(r.stdout);
    if (!Array.isArray(arr)) arr = [arr];
    const list = arr.map(p => ({ pid: p.ProcessId, name: p.Name, cmd: p.CMD || '' }))
      .filter(p => p.pid != null);
    const result = search ? list.filter(p => (p.name + ' ' + p.cmd).toLowerCase().includes(search.toLowerCase())) : list;
    return { processes: result.slice(0, 500) };
  } catch (e) { return { error: '进程解析失败: ' + e.message }; }
}
async function killProcess(pid) {
  const r = await run('taskkill /PID ' + pid + ' /F');
  if (r.code === 0) return { ok: true };
  return { error: '未能终止进程 PID=' + pid + ' (可能不存在或无权限)' };
}
async function doShutdown(reason) {
  log('执行关机: ' + (reason || ''), 'WARN');
  spawn('shutdown', ['/s', '/t', '10', '/c', '远程控制: ' + (reason || '关机')], { windowsHide: true, detached: true }).unref();
  return { ok: true, message: '关机指令已执行 (10秒后关机)' };
}
async function doReboot(reason) {
  log('执行重启: ' + (reason || ''), 'WARN');
  spawn('shutdown', ['/r', '/t', '10', '/c', '远程控制: ' + (reason || '重启')], { windowsHide: true, detached: true }).unref();
  return { ok: true, message: '重启指令已执行 (10秒后重启)' };
}

// ---------- 授权 ----------
function notifyPendingAuth(serial, info) {
  console.log('');
  console.log('==============================================');
  console.log('  🆕 设备绑定请求');
  console.log('  序列号: ' + serial);
  console.log('  设备名: ' + (info.name || '未知'));
  console.log('  型号:   ' + (info.model || '未知'));
  console.log('  在终端输入: 允许' + serial + '  或  拒绝' + serial);
  console.log('  或用: node control_server.js --approve ' + serial);
  console.log('==============================================');
}
function approveDevice(serial, info) {
  wl.devices[serial] = { serial, name: info && info.name, model: info && info.model, allow: true, addedAt: ts() };
  saveWhitelist(wl);
  delete pendingAuth[serial];
  log('设备已绑定并允许: ' + serial);
}
function rejectDevice(serial) {
  if (wl.devices[serial]) { wl.devices[serial].allow = false; }
  saveWhitelist(wl);
  delete pendingAuth[serial];
  log('设备已拒绝: ' + serial);
}

// ---------- 终端命令线程 ----------
function startStdin() {
  const readline = require('readline');
  const rl = readline.createInterface({ input: process.stdin, output: process.stdout, terminal: false });
  rl.on('line', (line) => {
    line = line.trim();
    if (!line) return;
    const mAllow = line.match(/^允许\s*([A-Za-z0-9\-_:]+)$/i) || line.match(/^allow\s+([A-Za-z0-9\-_:]+)$/i);
    const mReject = line.match(/^拒绝\s*([A-Za-z0-9\-_:]+)$/i) || line.match(/^reject\s+([A-Za-z0-9\-_:]+)$/i);
    if (mAllow) {
      const info = (pendingAuth[mAllow[1]] && pendingAuth[mAllow[1]].info) || {};
      approveDevice(mAllow[1], info);
      console.log('已允许设备: ' + mAllow[1]);
    } else if (mReject) {
      rejectDevice(mReject[1]);
      console.log('已拒绝设备: ' + mReject[1]);
    } else if (/^list$/i.test(line)) {
      console.log('已绑定设备:', Object.entries(wl.devices).map(([k, v]) => k + (v.allow ? '(允许)' : '(拒绝)')).join(', ') || '无');
    } else {
      console.log('可用命令: 允许<序列号> / 拒绝<序列号> / list');
    }
  });
}

// ---------- 心跳/在线 ----------
function deviceHeartbeat(serial) {
  if (wl.devices[serial]) { wl.devices[serial].lastSeen = ts(); saveWhitelist(wl); }
}

// ---------- 消息分发 ----------
function handleMessage(ws, msgText, serial) {
  let msg;
  try { msg = JSON.parse(msgText); } catch (e) { return ws.send(JSON.stringify({ type: 'error', error: '非法JSON' })); }
  const reqSeq = msg.seq;
  const reply = (obj) => { try { if (reqSeq !== undefined && reqSeq !== null) obj.seq = reqSeq; ws.send(JSON.stringify(obj)); } catch(e){} };
  switch (msg.type) {
    case 'ping':
      deviceHeartbeat(serial);
      reply({ type: 'pong', time: ts() });
      break;
    case 'process.list':
      listProcesses(msg.search).then(r => reply({ type: 'process.list', ...r }));
      break;
    case 'process.kill':
      killProcess(msg.pid).then(r => reply({ type: 'process.kill', pid: msg.pid, ...r }));
      break;
    case 'reboot':
      doReboot(msg.reason).then(r => reply({ type: 'reboot', ...r }));
      break;
    case 'shutdown':
      doShutdown(msg.reason).then(r => reply({ type: 'shutdown', ...r }));
      break;
    case 'status':
      reply({ type: 'status', ok: true, server: 'Windows Remote Control v2.0', device: serial, time: ts() });
      break;
    // ---------- v2.0 推送/监控 指令 ----------
    case 'push.setConfig': {
      const ok = push.setConfig(serial, msg.config);
      reply({ type: 'push.setConfig', ok, config: push.getConfig(serial) });
      break;
    }
    case 'push.getConfig': {
      reply({ type: 'push.getConfig', ok: true, config: push.getConfig(serial) });
      break;
    }
    case 'push.addSchedule': {
      const r = push.addSchedule(serial, msg.time);
      reply({ type: 'push.addSchedule', ...r, schedules: push.listSchedules(serial) });
      break;
    }
    case 'push.listSchedules': {
      reply({ type: 'push.listSchedules', ok: true, schedules: push.listSchedules(serial) });
      break;
    }
    case 'push.cancelSchedule': {
      const r = push.cancelSchedule(serial);
      reply({ type: 'push.cancelSchedule', ...r, schedules: push.listSchedules(serial) });
      break;
    }
    case 'push.startSysInfo': {
      // v2.0: 系统信息实时推送 (每秒) —— App 打开系统信息页时调用
      const r = push.startSysInfoPush(serial);
      reply({ type: 'push.startSysInfo', ...r });
      break;
    }
    case 'push.stopSysInfo': {
      const r = push.stopSysInfoPush(serial);
      reply({ type: 'push.stopSysInfo', ...r });
      break;
    }
    default:
      // 扩展功能模块 (系统信息/文件浏览/屏幕截图)
      handleFeatures(msg, ws, serial, reply, log).then(handled => {
        if (!handled) reply({ type: 'error', error: '未知指令: ' + msg.type });
      });
  }
}

// ---------- 主程序授权入口 ----------
const aa = process.argv.slice(2);
if (aa[0] === '--approve' && aa[1]) { approveDevice(aa[1], {}); process.exit(0); }
if (aa[0] === '--reject' && aa[1]) { rejectDevice(aa[1]); process.exit(0); }

// ---------- 启动 ----------
log('远程控制服务端 v2.0 启动，端口 ' + PORT);
log('白名单设备: ' + (Object.keys(wl.devices).length || 0) + ' 台', 'INFO');
startStdin();

const wss = new WebSocket.Server({ port: PORT });
wss.on('listening', () => log('WebSocket 监听 0.0.0.0:' + PORT));
wss.on('connection', (ws, req) => {
  const remote = req.socket.remoteAddress + ':' + req.socket.remotePort;
  log('收到连接: ' + remote);

  let authed = false;
  let serial = null;

  ws.on('message', (data) => {
    let text = data.toString('utf8');
    let msg;
    try { msg = JSON.parse(text); } catch (e) { return ws.send(JSON.stringify({ type: 'error', error: '非法JSON' })); }

    // 握手鉴权
    if (!authed) {
      if (msg.type === 'hello') {
        serial = String(msg.serial || '').trim();
        if (!serial) return ws.send(JSON.stringify({ type: 'auth', ok: false, error: '缺少设备序列号' }));
        if (isAllowed(serial)) {
          authed = true;
          deviceHeartbeat(serial);
          push.registerDevice(serial, ws);
          log('设备鉴权通过: ' + serial);
          ws.send(JSON.stringify({ type: 'auth', ok: true, device: serial }));
        } else {
          const info = { name: msg.deviceName, model: msg.deviceModel, serial };
          if (!pendingAuth[serial]) {
            pendingAuth[serial] = { info };
            notifyPendingAuth(serial, info);
          }
          ws.send(JSON.stringify({ type: 'auth', ok: false, pending: true, error: '设备未绑定, 等待服务端授权' }));
          setTimeout(() => { if (!isAllowed(serial)) { try { ws.close(); } catch(e){} } }, 30000);
        }
      } else {
        ws.send(JSON.stringify({ type: 'error', error: '请先完成设备鉴权(hello)' }));
      }
      return;
    }

    handleMessage(ws, text, serial);
  });

  ws.on('error', (e) => log('连接错误: ' + e.message, 'WARN'));
  ws.on('close', () => {
    cleanupViewer(ws);
    push.unregisterDevice(ws);
    log('连接关闭: ' + remote + (serial ? ' (设备 ' + serial + ')' : ''));
  });
});

// 启动推送巡检 (v2.0)
push.start(log);

// 优雅退出
process.on('SIGINT', () => { log('服务端停止'); push.stop(); process.exit(0); });