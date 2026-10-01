// A headless room host over MQTT: publishes a fixed track (retained) forever, with no stdin.
// Use it to give the app a "peer" to follow when there is no second device.
//
//   node server/host_room_mqtt.js <ROOM_CODE> [songId] [startPositionMs] [playing]
const net = require('node:net');
const crypto = require('node:crypto');

const HOST = process.env.MQTT_HOST || 'broker.hivemq.com';
const PORT = Number(process.env.MQTT_PORT || 1883);
const ROOT = 'yunyin/together/v1';

const [code, songArg, posArg, playArg] = process.argv.slice(2);
if (!code) {
  console.error('usage: node server/host_room_mqtt.js <ROOM_CODE> [songId] [positionMs] [playing]');
  process.exit(2);
}
const songId = Number(songArg || 1330348068);   // 起风了 (free)
let positionMs = Number(posArg || 20000);
const playing = playArg !== 'false';
const uid = 'host-' + crypto.randomBytes(4).toString('hex');
const slot = `${ROOT}/${code}/${uid}`;

const mqttString = (s) => { const b = Buffer.from(s, 'utf8'); return Buffer.concat([Buffer.from([(b.length >> 8) & 0xff, b.length & 0xff]), b]); };
const remainingLength = (n) => { const out = []; do { let d = n % 128; n = Math.floor(n / 128); if (n > 0) d |= 0x80; out.push(d); } while (n > 0); return Buffer.from(out); };
const packet = (type, bits, body) => Buffer.concat([Buffer.from([(type << 4) | bits]), remainingLength(body.length), body]);
const connectPacket = (id) => packet(1, 0, Buffer.concat([Buffer.from([0x00, 0x04, 0x4d, 0x51, 0x54, 0x54, 0x04, 0x02, 0x00, 0x1e]), mqttString(id)]));
const publishPacket = (t, p, retain) => packet(3, retain ? 0x01 : 0x00, Buffer.concat([mqttString(t), Buffer.from(p, 'utf8')]));

const sock = net.createConnection({ host: HOST, port: PORT });
sock.on('data', () => {});   // drain CONNACK; nothing else is read

sock.on('connect', async () => {
  sock.write(connectPacket(uid));
  await new Promise((r) => setTimeout(r, 1500));
  console.log(`broker: ${HOST}:${PORT}`);
  console.log(`room:   ${code}`);
  console.log(`slot:   ${slot}`);
  console.log(`hosting as ${uid}, song=${songId}, playing=${playing}. Ctrl+C to stop.\n`);

  const publish = () => {
    const payload = JSON.stringify({
      uid, name: '测试对方', songId, positionMs, playing,
      updatedAt: Date.now(), seq: 1,
    });
    sock.write(publishPacket(slot, payload, true));   // retained, so a joiner sees it at once
    console.log(`[${new Date().toLocaleTimeString()}] song=${songId} pos=${positionMs} playing=${playing}`);
    if (playing) positionMs += 3000;
  };
  publish();
  setInterval(publish, 3000);
});

sock.on('error', (e) => { console.log('connection error:', e.message); process.exit(1); });
