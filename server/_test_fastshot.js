// 测试 screenshot_fast.py (DXGI) - 触发3次取帧, 验证输出base64
const { spawn } = require('child_process');
const p = spawn('python', ['-u', 'screenshot_fast.py'], {
  cwd: __dirname,
  stdio: ['pipe', 'pipe', 'pipe']
});
let out = '';
let shots = 0;
const t0 = Date.now();
p.stdout.on('data', (d) => {
  out += d.toString();
  let nl;
  while ((nl = out.indexOf('\n')) >= 0) {
    const line = out.slice(0, nl).trim();
    out = out.slice(nl + 1);
    if (line.startsWith('[FastShot]')) { console.log(line); continue; }
    shots++;
    const sz = line ? Buffer.from(line, 'base64').length : 0;
    if (sz > 0) console.log(`帧${shots}: base64 -> ${sz} bytes (${Date.now() - t0}ms)`);
    else console.log(`帧${shots}: 已去重(无变化)`);
    if (shots >= 3) {
      console.log(`==== 3帧完成于 ${Date.now() - t0}ms ====`);
      p.kill();
      process.exit(0);
    }
  }
});
p.stderr.on('data', (d) => process.stderr.write(d));
p.on('error', (e) => { console.log('启动失败: ' + e.message); process.exit(1); });
// 触发3帧
setTimeout(() => { for (let i = 0; i < 3; i++) p.stdin.write('\n'); }, 1500);
setTimeout(() => { console.log('超时'); p.kill(); process.exit(2); }, 15000);