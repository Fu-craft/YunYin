// A headless room host that speaks whichever transport the app is using.
//
//   node server/host_room.js <ROOM_CODE> [songId] [startPositionMs] [playing] [--mqtt]
//
// Defaults to the HTTP (443) transport, because that is the app's default and because it is reachable
// on networks that block MQTT's 1883. Pass --mqtt when both sides are configured for MQTT.
const net = require('node:net');
const tls = require('node:tls');

const args = process.argv.slice(2);
const useMqtt = args.includes('--mqtt');
const [code, songArg, posArg, playArg] = args.filter((a) => !a.startsWith('--'));
if (!code) {
  console.error('usage: node server/host_room.js <ROOM_CODE> [songId] [positionMs] [playing] [--mqtt]');
  process.exit(2);
}
const songId = Number(songArg || 1330348068);   // 起风了 (free)
let positionMs = Number(posArg || 30000);
const playing = playArg !== 'false';

// The cadence must match the app's, and that matters for the HTTP transport specifically: its quota is
// per source address and shared, so a simulator publishing faster than the app eats the allowance for
// both. (Measured the hard way — a 5s host hit HTTP 429 within minutes and then dropped out of the
// room, which reads as "the peer left".) The app posts every 30s; MQTT has no quota, so it can be paced
// for responsiveness instead.
const INTERVAL = useMqtt ? 5000 : 30000;

const NTFY = (process.env.NTFY_URL || 'https://ntfy.sh').replace(/\/+$/, '');
const MQTT_HOST = process.env.MQTT_HOST || 'broker.hivemq.com';
// 8883 (TLS) by default: plain 1883 is commonly blocked by mobile carriers, and TLS on 8883 usually is
// not. Set MQTT_PORT=1883 (and MQTT_TLS=0) for a network that allows the plain port.
const MQTT_TLS = process.env.MQTT_TLS !== '0';
const MQTT_PORT = Number(process.env.MQTT_PORT || (MQTT_TLS ? 8883 : 1883));
const ROOT = 'yunyin/together/v1';

const uid = 'host-' + Math.random().toString(16).slice(2, 8);
const state = () => JSON.stringify({
  uid, name: '测试对方', songId, positionMs, playing, updatedAt: Date.now(), seq: 1,
});

// --------------------------------------------------------------------------- MQTT host
const mqttString = (s) => { const b = Buffer.from(s, 'utf8'); return Buffer.concat([Buffer.from([(b.length >> 8) & 0xff, b.length & 0xff]), b]); };
const remainingLength = (n) => { const out = []; do { let d = n % 128; n = Math.floor(n / 128); if (n > 0) d |= 0x80; out.push(d); } while (n > 0); return Buffer.from(out); };
const packet = (type, bits, body) => Buffer.concat([Buffer.from([(type << 4) | bits]), remainingLength(body.length), body]);
const connectPacket = (id) => packet(1, 0, Buffer.concat([Buffer.from([0x00, 0x04, 0x4d, 0x51, 0x54, 0x54, 0x04, 0x02, 0x00, 0x1e]), mqttString(id)]));
const publishPacket = (t, p, retain) => packet(3, retain ? 0x01 : 0x00, Buffer.concat([mqttString(t), Buffer.from(p, 'utf8')]));

function startMqtt() {
  const slot = `${ROOT}/${code}/${uid}`;
  // Plain net for 1883; TLS for 8883. The TLS handshake is forced so a wrong port/cert fails loudly
  // here rather than as a silent no-op later.
  const sock = MQTT_TLS
    ? tls.connect({ host: MQTT_HOST, port: MQTT_PORT, servername: MQTT_HOST })
    : net.createConnection({ host: MQTT_HOST, port: MQTT_PORT });
  const readyEvent = MQTT_TLS ? 'secureConnect' : 'connect';
  sock.on('data', () => {});
  sock.on(readyEvent, async () => {
    sock.write(connectPacket(uid));
    await new Promise((r) => setTimeout(r, 1500));
    console.log(`transport: MQTT${MQTT_TLS ? '+TLS' : ''} ${MQTT_HOST}:${MQTT_PORT}`);
    console.log(`room:      ${code}\nslot:      ${slot}\n`);
    const tick = () => {
      sock.write(publishPacket(slot, state(), true));   // retained, so a joiner sees it at once
      console.log(`[${new Date().toLocaleTimeString()}] song=${songId} pos=${positionMs} playing=${playing}`);
      if (playing) positionMs += INTERVAL;
    };
    tick();
    setInterval(tick, INTERVAL);
  });
  sock.on('error', (e) => { console.log('connection error:', e.message); process.exit(1); });
}

// --------------------------------------------------------------------------- HTTP (443) host
function startNtfy() {
  // The app's topic for this transport is ONE path segment: "yunyin-together-v1-<CODE>". It is not the
  // MQTT-style nested path — getting that wrong publishes into a topic nobody is listening to, which
  // looks exactly like "the room has only me".
  const topic = 'yunyin-together-v1-' + code.replace(/[^A-Za-z0-9]/g, '').toUpperCase();
  console.log(`transport: HTTP ${NTFY}`);
  console.log(`room:      ${code}\ntopic:     ${NTFY}/${topic}\n`);
  const tick = async () => {
    try {
      // One POST per interval; the app *subscribes*, so receiving costs it almost nothing. Publish at
      // the same slow cadence the transport uses, to stay well inside the shared per-address allowance.
      const res = await fetch(`${NTFY}/${topic}`, { method: 'POST', body: state() });
      console.log(`[${new Date().toLocaleTimeString()}] song=${songId} pos=${positionMs} playing=${playing} -> HTTP ${res.status}`);
      if (res.ok && playing) positionMs += INTERVAL;
      if (!res.ok && res.status === 429) console.log('   (rate limited — the shared per-address allowance)');
    } catch (e) {
      console.log('publish failed:', e.message);
    }
  };
  tick();
  setInterval(tick, INTERVAL);
}

if (useMqtt) startMqtt(); else startNtfy();
