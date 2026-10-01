// Read-only view of an MQTT listen-together room: subscribes to the room wildcard and prints each
// member's slot. Never publishes, so it cannot disturb a room.
//
//   node server/read_room.js <ROOM_CODE> [seconds]
//
// Retained messages mean the current state of every member arrives immediately on subscribe, so this
// also shows a room that is already occupied before you start listening.
const net = require('node:net');

const HOST = process.env.MQTT_HOST || 'broker.hivemq.com';
const PORT = Number(process.env.MQTT_PORT || 1883);
const ROOT = 'yunyin/together/v1';

const [code, secArg] = process.argv.slice(2);
if (!code) {
  console.error('usage: node server/read_room.js <ROOM_CODE> [seconds]');
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

const sock = net.createConnection({ host: HOST, port: PORT });
let buf = Buffer.alloc(0);
sock.on('data', (chunk) => {
  buf = Buffer.concat([buf, chunk]);
  while (buf.length >= 2) {
    let mult = 1, len = 0, i = 1, enc;
    do { if (i >= buf.length) return; enc = buf[i++]; len += (enc & 0x7f) * mult; mult *= 128; } while (enc & 0x80);
    if (buf.length < i + len) return;
    const type = buf[0] >> 4;
    const body = buf.subarray(i, i + len);
    buf = buf.subarray(i + len);
    if (type === 9) { subscribed = true; continue; }
    if (type !== 3) continue;
    const tl = (body[0] << 8) | body[1];
    const topic = body.subarray(2, 2 + tl).toString();
    const payload = body.subarray(2 + tl).toString();
    const slot = topic.slice(topic.lastIndexOf('/') + 1);
    if (payload === '') {
      console.log(`  [left] ${slot} (slot cleared)`);
      seen.delete(slot);
      continue;
    }
    let state;
    try { state = JSON.parse(payload); } catch { state = null; }
    const isNew = !seen.has(slot);
    seen.set(slot, state);
    const age = state?.updatedAt ? `${Math.round((Date.now() - state.updatedAt) / 1000)}s ago` : '?';
    if (isNew) {
      console.log(`[member] ${slot}  name="${state?.name ?? '?'}"`);
    }
    console.log(`   song=${state?.songId} pos=${state?.positionMs}ms playing=${state?.playing} seq=${state?.seq} (reported ${age})`);
  }
});

sock.on('connect', () => {
  sock.write(connectPacket('reader-' + Math.random().toString(16).slice(2, 8)));
  setTimeout(() => sock.write(subscribePacket(1, filter)), 1200);
});
sock.on('error', (e) => { console.log('connection error:', e.message); process.exit(1); });

console.log(`broker: ${HOST}:${PORT}`);
console.log(`room:   ${code}   (subscribing to ${filter}, read-only)\n`);

setTimeout(() => {
  console.log('');
  if (!subscribed) {
    console.log('No SUBACK — could not subscribe (broker unreachable, or blocked network).');
  } else if (seen.size === 0) {
    console.log('Subscribed, but no member is publishing here.');
    console.log('Check the room code, and whether the app is actually in that room.');
  } else {
    console.log(`${seen.size} member(s) present:`);
    for (const [slot, s] of seen) {
      console.log(`  ${slot}  song=${s?.songId} pos=${s?.positionMs} playing=${s?.playing}`);
    }
    console.log('\n(retained slots are the current state; "reported Xs ago" shows how stale each is)');
  }
  sock.destroy();
  process.exit(0);
}, seconds * 1000);
