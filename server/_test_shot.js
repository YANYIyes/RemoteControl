// 测试截图脚本
const { spawn } = require('child_process');
const path = require('path');

const p = spawn('powershell', [
  '-NoProfile',
  '-ExecutionPolicy', 'Bypass',
  '-File', path.join(__dirname, 'screenshot.ps1')
], {
  windowsHide: true,
  stdio: ['pipe', 'pipe', 'inherit']
});

console.log('截图进程已启动，PID:', p.pid);

let output = '';
p.stdout.on('data', (d) => { output += d.toString(); });

p.on('close', (code) => {
  console.log('进程退出码:', code);
  console.log('输出长度:', output.length);
  if (output.length > 100) {
    console.log('✅ 有输出，前 100 字符:', output.slice(0, 100));
    console.log('✅ 是有效 base64 (FFD8 JPEG 头):', output.trim().slice(0, 4));
  } else {
    console.log('❌ 输出太短或为空');
  }
});

// 2 秒后触发截图
setTimeout(() => {
  console.log('→ 触发截图 (发送空行)');
  p.stdin.write('\n');
}, 2000);

// 8 秒后退出
setTimeout(() => {
  console.log('→ 结束测试');
  p.kill();
  process.exit(0);
}, 8000);
