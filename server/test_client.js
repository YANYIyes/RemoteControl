// 模拟 Android APK 客户端测试工具
// 用法: node test_client.js <serial> [--list | --kill <pid> | --search <kw> | --status]
const WebSocket = require('ws');
const serial = process.argv[2] || 'TEST-PHONE-001';
const action = process.argv[3] || '--status';
const arg2 = process.argv[4];

const url = 'ws://127.0.0.1:8899';
const ws = new WebSocket(url);

function send(o) { ws.send(JSON.stringify(o)); }
let authed = false;

ws.on('open', () => {
  console.log('[连接成功] 发送hello, 序列号=' + serial);
  send({ type: 'hello', serial, deviceName: '测试安卓手机', deviceModel: 'Pixel Test' });
});

ws.on('message', (d) => {
  const m = JSON.parse(d.toString());
  console.log('[收到]', JSON.stringify(m, null, 2).slice(0, 800));
  if (m.type === 'auth') {
    if (m.ok) {
      authed = true;
      console.log('>>> 鉴权通过，测试操作: ' + action);
      if (action === '--list') send({ type: 'process.list' });
      else if (action === '--status') send({ type: 'status' });
      else if (action === '--search') send({ type: 'process.list', search: arg2 || 'node' });
      else if (action === '--kill') send({ type: 'process.kill', pid: parseInt(arg2) });
      else if (action === '--shutdown') send({ type: 'shutdown' });
      else if (action === '--reboot') send({ type: 'reboot' });
      else { console.log('未知操作'); ws.close(); }
    } else if (m.pending) {
      console.log('>>> 设备未授权，等待服务端授权...（服务端QQ/终端已收到通知）');
      // 不关闭，等待授权
    } else {
      console.log('>>> 鉴权失败: ' + m.error);
      ws.close();
    }
  } else {
    // 业务响应
    if (m.type === 'status') { console.log('状态OK, 服务端时间: ' + m.time); ws.close(); }
    else if (m.type === 'process.list') {
      console.log('进程数量: ' + (m.processes ? m.processes.length : 0));
      if (m.processes) console.log('前5个: ' + m.processes.slice(0,5).map(p=>p.name+'('+p.pid+')').join(', '));
      ws.close();
    }
    else if (m.type === 'process.kill') { console.log('杀进程结果: ' + JSON.stringify(m)); ws.close(); }
    else { console.log('响应: ' + JSON.stringify(m)); ws.close(); }
  }
});

ws.on('error', (e) => console.log('[错误]', e.message));
setTimeout(() => { try { ws.close(); } catch(e){} }, 120000);
