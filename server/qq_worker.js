// QQ 队列发送 worker: 主 agent cron 触发, 前台逐个发送队列 QQ 通知
// 通过临时消息文件传递文本(绕过 PowerShell 命令行转义问题), execFileSync 前台可靠发送
const fs = require('fs');
const path = require('path');
const os = require('os');
const { execFileSync } = require('child_process');

const DIR = path.join(__dirname, 'qq_queue');
const SEND_PS1 = path.join(__dirname, 'send_qq.ps1');
const PS1 = 'powershell.exe';

const ENV = Object.assign({}, process.env, {
  USERPROFILE: process.env.USERPROFILE || os.homedir(),
  APPDATA: process.env.APPDATA || path.join(os.homedir(), 'AppData', 'Roaming'),
  LOCALAPPDATA: process.env.LOCALAPPDATA || path.join(os.homedir(), 'AppData', 'Local')
});

function run() {
  if (!fs.existsSync(DIR)) { console.log('NO_DIR'); return; }
  const files = fs.readdirSync(DIR).filter(f => f.endsWith('.json'));
  if (files.length === 0) { console.log('QUEUE_EMPTY'); return; }
  let sent = 0;
  for (const f of files) {
    const fp = path.join(DIR, f);
    try {
      const payload = JSON.parse(fs.readFileSync(fp, 'utf8'));
      // 换行转单行 + 双引号替换为中文引号(openclaw.cmd 是 cmd 批处理, %* 对英文引号解析会报 Too many arguments)
      // 前缀 [远程控制] 在此拼好(JS UTF-8 无 BOM 问题), 不再依赖 send_qq.ps1 里的中文字面量
      const text = '[远程控制] ' + (payload.text || '')
        .replace(/[\r\n]+/g, ' | ')
        .replace(/"/g, '「');
      const msgFile = path.join(os.tmpdir(), 'rc_qq_msg_' + Date.now() + '_' + process.pid + '.txt');
      fs.writeFileSync(msgFile, text, 'utf8'); // 默认 UTF-8 无 BOM
      console.log('SENDING:', text.slice(0, 60));
      const out = execFileSync(PS1, ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', SEND_PS1, '-MessageFile', msgFile], {
        encoding: 'utf8',
        timeout: 40000,
        windowsHide: true,
        env: ENV
      });
      try { fs.unlinkSync(msgFile); } catch (_) {}
      fs.unlinkSync(fp); // 发送后删队列文件
      sent++;
      console.log('DONE:', (out || '').trim().slice(0, 100));
    } catch (e) {
      console.log('FAIL:', fp, e.message);
      try {
        const st = fs.statSync(fp);
        if (Date.now() - st.mtimeMs > 10 * 60000) { fs.unlinkSync(fp); console.log('DROPPED stale:', f); }
      } catch (_) {}
    }
  }
  console.log('SENT_TOTAL:', sent);
}

run();
