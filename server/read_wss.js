// Read-only view of a room over MQTT-over-WebSocket — the transport the app uses by default.
//
//   node server/read_wss.js <ROOM_CODE> [seconds]
//
// Subscribes to the room wildcard and prints each member's slot. Never publishes, so it cannot
// disturb a room. Because publishing is retained, a joiner sees the current state immediately.
const URL_ = process.env.MQTT_WSS || 'wss://broker.emqx.io:8084/mqtt';
const ROOT = 'yunyin/together/v1';

const [code, secArg] = process.argv.slice(2);
if (!code) {
  console.error('usage: node server/read_wss.js <ROOM_CODE> [seconds]');
  process.exit(2);
}
const seconds = Number(secArg || 20);
const filter = `${ROOT}/${code}/+`;

const mqttString = (s) => { const b = Buffer.from(s, 'utf8'); return Buffer.concat([Buffer.from([(b.length >> 8) & 0xff, b.length & 0xff]), b]); };
const remainingLength = (n) => { const out = []; do { let d = n % 128; n = Math.floor(n / 128); if (n > 0) d |= 0x80; out.push(d); } while (n > 0); return Buffer.from(out); };
const packet = (type, bits, body) => Buffer.concat([Buffer.from([(type << 4) | bits]), remainingLength(body.length), body]);
const connectPacket = (id) => packet(1, 0, Buffer.concat([Buffer.from([0x00, 0x04, 0x4d, 0x51, 0x54, 0x54, 0x04, 0x02, 0x00, 0x1e]), mqttString(id)]));
const subscribePacket = (id, t) => packet(8, 0x02, Buffer.concat([Buffer.from([(id >> 8) & 0xff, id & 0xff]), mqttString(t), Buffer.from([0x00])]));

const seen = new Map();
let subscribed = false;
let welcomeBuf = Buffer.alloc(0);
const ws = new WebSocket(URL_, ['mqtt']);
ws.binaryType = 'arraybuffer';

ws.onopen = () => {
  ws.send(connectPacket('reader-' + Math.random().toString(16).slice(2, 8)));
  setTimeout(() => ws.send(subscribePacket(1, filter)), 1200);
};

ws.onmessage = (ev) => {
  const bytes = Buffer.from(ev.data);
  welcomeBuf = Buffer.concat([welcomeBuf, bytes]);
  while (welcomeBuf.length >= 2) {
    let mult = 1, len = 0, i = 1, enc;
    do { if (i >= welcomeBuf.length) return; enc = welcomeBuf[i++]; len += (enc & 0x7f) * mult; mult *= 128; } while (enc & 0x80);
    if (welcomeBuf.length < i + len) return;
    const type = welcomeBuf[0] >> 4;
    const body = welcomeBuf.subarray(i, i + len);
    welcomeBuf = welcomeBuf.subarray(i + len);
    if (type === 9) { subscribed = true; continue; }
    if (type !== 3) continue;
    const tl = (body[0] << 8) | body[1];
    const topic = body.subarray(2, 2 + tl).toString();
    const payload = body.subarray(2 + tl).toString();
    const slot = topic.slice(topic.lastIndexOf('/') + 1);
    if (payload === '') { console.log(`  [left] ${slot}`); seen.delete(slot); continue; }
    let state = null;
    try { state = JSON.parse(payload); } catch { state = null; }
    if (!seen.has(slot)) console.log(`[member] ${slot}  name="${state && state.name}"`);
    seen.set(slot, state);
    const age = state && state.updatedAt ? Math.round((Date.now() - state.updatedAt) / 1000) : null;
    console.log(`   song=${state && state.songId} pos=${state && state.positionMs}ms playing=${state && state.playing}` +
      (age === null ? '' : `  (reported ${age}s ago)`));
  }
};
ws.onerror = () => { console.log('websocket error'); process.exit(1); };

console.log(`transport: MQTT over WebSocket ${URL_}`);
console.log(`room:      ${code}   (subscribing to ${filter}, read-only)\n`);

setTimeout(() => {
  console.log('');
  if (!subscribed) console.log('No SUBACK — could not subscribe.');
  else if (seen.size === 0) console.log('Subscribed, but no member is publishing here.');
  else {
    console.log(`${seen.size} member(s) present:`);
    for (const [slot, s] of seen) console.log(`  ${slot}  song=${s && s.songId} pos=${s && s.positionMs} playing=${s && s.playing}`);
  }
  process.exit(0);
}, seconds * 1000);
