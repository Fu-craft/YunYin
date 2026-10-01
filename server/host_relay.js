// A headless room host for the SELF-HOSTED relay (server/together-relay.js).
//
//   node server/host_relay.js <RELAY_BASE_URL> [songId] [positionMs] [playing]
//   e.g. node server/host_relay.js http://10.0.0.5:8090
//
// Prints the room code it created, which is what the other member types into the app.
// The address must be reachable by BOTH devices — a LAN address works only on the same network.
const BASE = (process.argv[2] || '').replace(/\/+$/, '');
const songId = Number(process.argv[3] || 1330348068);
let positionMs = Number(process.argv[4] || 45000);
const playing = process.argv[5] !== 'false';
const INTERVAL = 3000;
const TOKEN = process.env.RELAY_TOKEN || '';

if (!BASE) {
  console.error('usage: node server/host_relay.js <RELAY_BASE_URL> [songId] [positionMs] [playing]');
  process.exit(2);
}

const uid = 'host-' + Math.random().toString(16).slice(2, 8);
const headers = { 'Content-Type': 'application/json' };
if (TOKEN) headers['X-Relay-Token'] = TOKEN;

async function api(method, path, body) {
  const res = await fetch(BASE + path, {
    method,
    headers,
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  if (!res.ok) throw new Error(`${method} ${path} -> HTTP ${res.status} ${text.slice(0, 120)}`);
  return text ? JSON.parse(text) : {};
}

(async () => {
  let code;
  try {
    const created = await api('POST', '/room', { uid, name: '测试对方' });
    code = created.code;
  } catch (e) {
    console.error('could not create a room:', e.message);
    console.error('is the relay running?  node server/together-relay.js');
    process.exit(1);
  }

  console.log(`relay:  ${BASE}`);
  console.log(`room:   ${code}`);
  console.log(`uid:    ${uid}`);
  console.log(`song:   ${songId}  from ${positionMs}ms  playing=${playing}`);
  console.log('\n在 App 的「一起听」里输入上面的房间码加入。Ctrl+C 停止。\n');

  const tick = async () => {
    try {
      await api('POST', `/room/${code}/state`, {
        uid, name: '测试对方', songId, positionMs, playing,
        updatedAt: Date.now(), seq: 1,
      });
      console.log(`[${new Date().toLocaleTimeString()}] song=${songId} pos=${positionMs} playing=${playing}`);
      if (playing) positionMs += INTERVAL;
    } catch (e) {
      console.log('publish failed:', e.message);
    }
  };
  tick();
  setInterval(tick, INTERVAL);
})();
