// 测试 C# 截图脚本
const { spawn } = require('child_process');
const path = require('path');

const p = spawn('powershell', [
  '-NoProfile',
  '-ExecutionPolicy', 'Bypass',
  '-File', path.join(__dirname, 'screenshot_csharp.ps1')
], {
  windowsHide: true,
  stdio: ['pipe', 'pipe', 'pipe']
});

console.log('PS C# 截图进程已启动，PID:', p.pid);

let output = '';
p.stdout.on('data', (d) => { 
  const s = d.toString();
  output += s;
  console.log('STDOUT:', s.trim().slice(0, 100));
});
p.stderr.on('data', (d) => { console.error('STDERR:', d.toString()); });

p.on('close', (code) => {
  console.log('进程退出码:', code);
  console.log('输出长度:', output.length);
  if (output.length > 1000) {
    console.log('✅ 截图成功:', output.length, '字符');
    console.log('前 4 字符:', output.trim().slice(0, 4));
  } else {
    console.log('❌ 输出太短');
  }
});

// 2 秒后触发
setTimeout(() => {
  console.log('→ 触发截图');
  p.stdin.write('\n');
}, 2000);

// 8 秒后退出
setTimeout(() => {
  console.log('→ 结束');
  p.kill();
  process.exit(0);
}, 8000);
