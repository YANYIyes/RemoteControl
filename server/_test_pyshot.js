// 测试 Python 截图脚本
const { spawn } = require('child_process');
const path = require('path');

const p = spawn('python', [
  path.join(__dirname, 'screenshot.py')
], {
  windowsHide: true,
  stdio: ['pipe', 'pipe', 'pipe']
});

console.log('Python 截图进程已启动，PID:', p.pid);

let output = '';
p.stdout.on('data', (d) => { output += d.toString(); });
p.stderr.on('data', (d) => { console.error('STDERR:', d.toString()); });

p.on('close', (code) => {
  console.log('进程退出码:', code);
  console.log('输出长度:', output.length);
  if (output.length > 100) {
    console.log('✅ 截图成功:', output.length, '字符 (base64 JPEG)');
    console.log('前 4 字符 (应 FFD8):', output.trim().slice(0, 4));
  } else {
    console.log('❌ 输出太短或为空');
  }
});

// 2 秒后触发截图
setTimeout(() => {
  console.log('→ 触发截图 (发送空行)');
  p.stdin.write('\n');
  console.log('stdin 已写入');
}, 2000);

// 10 秒后退出（给更多时间）
setTimeout(() => {
  console.log('→ 结束测试');
  console.log('最终输出长度:', output.length);
  if (output.length > 100) {
    console.log('✅ 成功:', output.length, '字符');
    console.log('前 10 字符:', output.trim().slice(0, 10));
  }
  p.kill();
  process.exit(0);
}, 10000);
