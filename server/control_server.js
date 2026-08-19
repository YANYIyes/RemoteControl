/**
 * Remote Control Server (远程控制服务端)
 * -------------------------------------------------
 * 功能：
 *   - 通过 WebSocket 接收 Android APK 的连接
 *   - 设备鉴权：来源访问 = 设备序列号指纹 + 服务端白名单
 *   - 设备初次授权：QQ 通知 + 主程序终端确认（任一方同意即可绑定）
 *   - 操作：关机 / 重启 / 列出进程 / 搜索进程 / 杀进程
 *   - 关机/重启发 QQ 通知 + 本地详细日志
 *
 * 安全边界：APK 不内置任何管理员凭据；不在白名单的设备一律拒绝。
 * 部署：本机运行 node control_server.js
 */
const WebSocket = require('ws');
const { exec, execSync, spawn } = require('child_process');
const fs = require('fs');
const path = require('path');
const os = require('os');
const { handleFeatures, cleanupViewer } = require('./rc_features.js');

// ---------- 配置 ----------
const ROOT = __dirname;
const PORT = Number(process.env.RC_PORT || 8899);
const WHITELIST_FILE = path.join(ROOT, 'whitelist.json');
const LOG_FILE = path.join(ROOT, 'control.log');
const QQ_TARGET = process.env.RC_QQ_TARGET || 'qqbot:c2c:EFED30149FAD116D7B3E8BC81E4DF24C';
const OPENCLAW_CLI = 'C:\\Users\\Administrator\\AppData\\Roaming\\npm\\openclaw.cmd';
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
function loadWhitelist() {
  try {
    if (fs.existsSync(WHITELIST_FILE)) {
      const data = JSON.parse(readWlFile());
      if (data && typeof data === 'object' && data.devices) return data;
    }
  } catch (e) { log('白名单解析失败: ' + e.message, 'ERROR'); }
  return { devices: {}, defaultAllow: false };
}
// 读白名单并剥离可能的 UTF-8 BOM (PowerShell Set-Content -Encoding UTF8 会写 BOM, 破坏 JSON.parse)
function readWlFile() {
  let s = fs.readFileSync(WHITELIST_FILE, 'utf8');
  if (s.charCodeAt(0) === 0xFEFF) s = s.slice(1);
  return s;
}
function saveWhitelist(wl) {
  fs.writeFileSync(WHITELIST_FILE, JSON.stringify(wl, null, 2), 'utf8');
}
let wl = loadWhitelist();
// device: { serial, name, model, allow: bool, addedAt, lastSeen }

function isAllowed(serial) {
  // 每次重读磁盘, 使外部修改 whitelist.json 即时生效(设备数量少, 性能无影响)
  try {
    const fresh = JSON.parse(readWlFile());
    const d = fresh.devices ? fresh.devices[serial] : undefined;
    return !!(d && d.allow === true);
  } catch (e) {
    const d = wl.devices[serial];
    return !!(d && d.allow === true);
  }
}
// 待处理授权队列: 新设备请求后放入，等待 QQ 或主程序确认
const pendingAuth = {}; // serial -> { info, timer }

// ---------- QQ 通知 (写入队列, 由主 agent 前台 send_qq.ps1 代发; 本服务不阻塞) ----------
// 可通过环境变量一键开关: RC_QQ_ENABLED=on|off|auto (默认 auto: 有可用环境才发)
const QQ_QUEUE_DIR = path.join(ROOT, 'qq_queue');
const QQ_ENABLED = (process.env.RC_QQ_ENABLED || 'on').toLowerCase();
function sendQQ(text) {
  if (QQ_ENABLED === 'off') { log('QQ通知已关闭(auto), 跳过'); return Promise.resolve(false); }
  try {
    if (!fs.existsSync(QQ_QUEUE_DIR)) fs.mkdirSync(QQ_QUEUE_DIR, { recursive: true });
    const fname = Date.now() + '-' + Math.random().toString(36).slice(2, 8) + '.json';
    const payload = { time: ts(), text, target: QQ_TARGET };
    fs.writeFileSync(path.join(QQ_QUEUE_DIR, fname), JSON.stringify(payload, null, 2), 'utf8');
    log('QQ通知入队: ' + text.slice(0, 80));
    return Promise.resolve(true);
  } catch (e) {
    log('QQ通知入队失败: ' + e.message, 'ERROR');
    return Promise.resolve(false);
  }
}

// ---------- QQ worker 调度器 (服务端自驱动, 每60秒spawn worker发队列; 不依赖cron/LLM) ----------
const QQ_WORKER = path.join(ROOT, 'qq_worker.js');
let qqWorkerBusy = false;
function pumpQQQueue() {
  if (qqWorkerBusy) return; // 上一次还在跑, 跳过本轮
  if (!fs.existsSync(QQ_QUEUE_DIR)) return;
  const files = fs.readdirSync(QQ_QUEUE_DIR).filter(f => f.endsWith('.json'));
  if (files.length === 0) return;
  qqWorkerBusy = true;
  const child = spawn(process.execPath, [QQ_WORKER], {
    cwd: ROOT,
    windowsHide: true,
    detached: false,
    stdio: ['ignore', 'pipe', 'pipe'],
    env: Object.assign({}, process.env, {
      USERPROFILE: process.env.USERPROFILE || os.homedir(),
      APPDATA: process.env.APPDATA || path.join(os.homedir(), 'AppData', 'Roaming'),
      LOCALAPPDATA: process.env.LOCALAPPDATA || path.join(os.homedir(), 'AppData', 'Local')
    })
  });
  let out = '';
  child.stdout.on('data', d => out += d.toString());
  child.stderr.on('data', d => out += d.toString());
  child.on('close', (code) => {
    qqWorkerBusy = false;
    if (code !== 0) log('QQ worker 退出码 ' + code + ': ' + out.slice(0, 200), 'WARN');
  });
  child.on('error', (e) => { qqWorkerBusy = false; log('QQ worker 启动失败: ' + e.message, 'ERROR'); });
}
// 首次(3秒后)及每60秒扫描队列
setTimeout(pumpQQQueue, 3000);
setInterval(pumpQQQueue, 60000);

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
  // Windows中文输出为GBK, 不直接透传避免乱码; 只取PID信息提示
  return { error: '未能终止进程 PID=' + pid + ' (可能不存在或无权限)' };
}

async function doShutdown() {
  await sendQQ('⛔ 收到远程关机指令，执行关机');
  log('执行关机', 'WARN');
  spawn('shutdown', ['/s', '/t', '10', '/c', '远程控制: 执行关机'], { windowsHide: true, detached: true }).unref();
  return { ok: true, message: '关机指令已执行 (10秒后关机)' };
}
async function doReboot() {
  await sendQQ('🔄 收到远程重启指令，执行重启');
  log('执行重启', 'WARN');
  spawn('shutdown', ['/r', '/t', '10', '/c', '远程控制: 执行重启'], { windowsHide: true, detached: true }).unref();
  return { ok: true, message: '重启指令已执行 (10秒后重启)' };
}

// ---------- 授权 ----------
function notifyPendingAuth(serial, info) {
  // QQ 通知
  sendQQ(`🆕 有设备请求绑定远程控制：
设备序列号: ${serial}
设备名: ${info.name || info.model || '未知'}
型号: ${info.model || '未知'}
回复"允许 ${serial}" 或 "拒绝 ${serial}" 完成绑定`);
  // 主程序终端提示
  console.log('');
  console.log('==============================================');
  console.log('  🆕 设备绑定请求');
  console.log('  序列号: ' + serial);
  console.log('  设备名: ' + (info.name || '未知'));
  console.log('  型号:   ' + (info.model || '未知'));
  console.log('  在终端输入: 允许' + serial + '  或  拒绝' + serial);
  console.log('==============================================');
}

function approveDevice(serial, info) {
  wl.devices[serial] = { serial, name: info && info.name, model: info && info.model, allow: true, addedAt: ts() };
  saveWhitelist(wl);
  delete pendingAuth[serial];
  log('设备已绑定并允许: ' + serial);
  sendQQ('✅ 设备已绑定: ' + serial);
}
function rejectDevice(serial) {
  if (wl.devices[serial]) { wl.devices[serial].allow = false; }
  saveWhitelist(wl);
  delete pendingAuth[serial];
  log('设备已拒绝: ' + serial);
}

// ---------- 终端命令线程 (用于主程序绑定确认 / 白名单管理) ----------
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
    } else if (/^qq\s+.+/.test(line)) {
      sendQQ(line.replace(/^qq\s+/, ''));
    } else {
      console.log('未知命令。可用: 允许<序列号> / 拒绝<序列号> / list / qq <消息>');
    }
  });
}

// ---------- 心跳/在线检查 ----------
function deviceHeartbeat(serial) {
  if (wl.devices[serial]) { wl.devices[serial].lastSeen = ts(); saveWhitelist(wl); }
}

// ---------- 进程消息 ----------
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
      doReboot().then(r => reply({ type: 'reboot', ...r }));
      break;
    case 'shutdown':
      doShutdown().then(r => reply({ type: 'shutdown', ...r }));
      break;
    case 'status':
      reply({ type: 'status', ok: true, server: 'Windows Remote Control', device: serial, time: ts() });
      break;
    default:
      // 尝试扩展功能模块 (系统信息/文件浏览/屏幕截图)
      handleFeatures(msg, ws, serial, reply, log).then(handled => {
        if (!handled) reply({ type: 'error', error: '未知指令: ' + msg.type });
      });
  }
}

// ---------- 主程序绑定确认（新增：另一种授权通道，接收命令行参数触发） ----------
// 支持: node control_server.js --approve <serial>  或  --reject <serial>
const aa = process.argv.slice(2);
if (aa[0] === '--approve' && aa[1]) { approveDevice(aa[1], {}); process.exit(0); }
if (aa[0] === '--reject' && aa[1]) { rejectDevice(aa[1]); process.exit(0); }

// ---------- 启动 ----------
log('远程控制服务端启动，端口 ' + PORT);
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
          log('设备鉴权通过: ' + serial);
          ws.send(JSON.stringify({ type: 'auth', ok: true, device: serial }));
        } else {
          // 未绑定: 进入待授权
          const info = { name: msg.deviceName, model: msg.deviceModel, serial };
          if (!pendingAuth[serial]) {
            pendingAuth[serial] = { info };
            notifyPendingAuth(serial, info);
          }
          ws.send(JSON.stringify({ type: 'auth', ok: false, pending: true, error: '设备未绑定, 等待服务端授权' }));
          // 30秒无确认则断开
          setTimeout(() => { if (!isAllowed(serial)) { try { ws.close(); } catch(e){} } }, 30000);
        }
      } else {
        ws.send(JSON.stringify({ type: 'error', error: '请先完成设备鉴权(hello)' }));
      }
      return;
    }

    // 已鉴权: 处理业务
    handleMessage(ws, text, serial);
  });

  ws.on('error', (e) => log('连接错误: ' + e.message, 'WARN'));
  ws.on('close', () => {
    cleanupViewer(ws);
    log('连接关闭: ' + remote + (serial ? ' (设备 ' + serial + ')' : ''));
  });
});

// 优雅退出
process.on('SIGINT', () => { log('服务端停止'); process.exit(0); });
