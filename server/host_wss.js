// A headless room host over MQTT-over-WebSocket — the transport the app now uses by default.
//
//   node server/host_wss.js <ROOM_CODE> [songId] [positionMs] [playing]
//
// Publishes retained state to `yunyin/together/v1/<code>/<uid>`, so a joiner sees it immediately.
// Node 24 ships a global WebSocket, so this needs no dependencies.
const DEFAULT_URL = process.env.MQTT_WSS || 'wss://broker.emqx.io:8084/mqtt';
const ROOT = 'yunyin/together/v1';

const [code, songArg, posArg, playArg] = process.argv.slice(2);
if (!code) {
  console.error('usage: node server/host_wss.js <ROOM_CODE> [songId] [positionMs] [playing]');
  process.exit(2);
}
const songId = Number(songArg || 1330348068);
let positionMs = Number(posArg || 45000);
const playing = playArg !== 'false';
const INTERVAL = 5000;

const mqttString = (s) => { const b = Buffer.from(s, 'utf8'); return Buffer.concat([Buffer.from([(b.length >> 8) & 0xff, b.length & 0xff]), b]); };
const remainingLength = (n) => { const out = []; do { let d = n % 128; n = Math.floor(n / 128); if (n > 0) d |= 0x80; out.push(d); } while (n > 0); return Buffer.from(out); };
const packet = (type, bits, body) => Buffer.concat([Buffer.from([(type << 4) | bits]), remainingLength(body.length), body]);
const connectPacket = (id) => packet(1, 0, Buffer.concat([Buffer.from([0x00, 0x04, 0x4d, 0x51, 0x54, 0x54, 0x04, 0x02, 0x00, 0x1e]), mqttString(id)]));
const publishPacket = (t, p, retain) => packet(3, retain ? 0x01 : 0x00, Buffer.concat([mqttString(t), Buffer.from(p, 'utf8')]));

const uid = 'host-' + Math.random().toString(16).slice(2, 8);
const slot = `${ROOT}/${code}/${uid}`;

const ws = new WebSocket(DEFAULT_URL, ['mqtt']);
ws.binaryType = 'arraybuffer';
ws.onopen = async () => {
  ws.send(connectPacket(uid));
  await new Promise((r) => setTimeout(r, 1500));
  console.log(`transport: MQTT over WebSocket ${DEFAULT_URL}`);
  console.log(`room:      ${code}\nslot:      ${slot}\n`);
  const tick = () => {
    ws.send(publishPacket(slot, JSON.stringify({
      uid, name: '测试对方', songId, positionMs, playing, updatedAt: Date.now(), seq: 1,
    }), true));
    console.log(`[${new Date().toLocaleTimeString()}] song=${songId} pos=${positionMs} playing=${playing}`);
    if (playing) positionMs += INTERVAL;
  };
  tick();
  setInterval(tick, INTERVAL);
};
ws.onerror = () => { console.log('websocket error'); process.exit(1); };
ws.onclose = (e) => { console.log('websocket closed', e.code); process.exit(1); };
