// Read-only view of a listen-together room, for whichever transport the app is using.
//
//   node server/read_room.js <ROOM_CODE> [seconds] [--mqtt]
//
// Defaults to the HTTP (443) transport, matching the app's default. Never publishes, so it cannot
// disturb a room.
const net = require('node:net');

const args = process.argv.slice(2);
const useMqtt = args.includes('--mqtt');
const [code, secArg] = args.filter((a) => !a.startsWith('--'));
if (!code) {
  console.error('usage: node server/read_room.js <ROOM_CODE> [seconds] [--mqtt]');
  process.exit(2);
}
const seconds = Number(secArg || 20);

const NTFY = (process.env.NTFY_URL || 'https://ntfy.sh').replace(/\/+$/, '');
const MQTT_HOST = process.env.MQTT_HOST || 'broker.hivemq.com';
const MQTT_PORT = Number(process.env.MQTT_PORT || 1883);
const ROOT = 'yunyin/together/v1';

const seen = new Map();
const report = (slot, state, isNew) => {
  if (state === '' || state === null) {
    console.log(`  [left] ${slot} (slot cleared)`);
    seen.delete(slot);
    return;
  }
  const age = state.updatedAt ? Math.round((Date.now() - state.updatedAt) / 1000) : null;
  if (isNew) console.log(`[member] ${slot}  name="${state.name ?? '?'}"`);
  console.log(`   song=${state.songId} pos=${state.positionMs}ms playing=${state.playing} seq=${state.seq}` +
    (age === null ? '' : `  (reported ${Math.max(0, age)}s ago)`));
  seen.set(slot, state);
};

// --------------------------------------------------------------------------- HTTP (443)
async function watchNtfy() {
  const topic = 'yunyin-together-v1-' + code.replace(/[^A-Za-z0-9]/g, '').toUpperCase();
  console.log(`transport: HTTP ${NTFY}`);
  console.log(`room:      ${code}\ntopic:     ${NTFY}/${topic}   (long-lived subscription, read-only)\n`);

  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), seconds * 1000);
  try {
    // A streaming GET (no poll=1): the service pushes as messages arrive. One connection for the whole
    // view, rather than a request per poll.
    const res = await fetch(`${NTFY}/${topic}/json`, { signal: controller.signal });
    if (!res.ok) {
      console.log(`subscription refused: HTTP ${res.status}`);
      return;
    }
    let buffer = '';
    for await (const chunk of res.body) {
      buffer += Buffer.from(chunk).toString('utf8');
      let nl;
      while ((nl = buffer.indexOf('\n')) >= 0) {
        const line = buffer.slice(0, nl);
        buffer = buffer.slice(nl + 1);
        if (!line.trim()) continue;
        let event; try { event = JSON.parse(line); } catch { continue; }
        if (event.event !== 'message') continue;
        let state; try { state = JSON.parse(event.message); } catch { continue; }
        if (!state.uid) continue;
        report(state.uid, state, !seen.has(state.uid));
      }
    }
  } catch (e) {
    if (e.name !== 'AbortError') console.log('subscription error:', e.message);
  } finally {
    clearTimeout(timer);
  }
}

// --------------------------------------------------------------------------- MQTT
function watchMqtt() {
  const filter = `${ROOT}/${code}/+`;
  console.log(`transport: MQTT ${MQTT_HOST}:${MQTT_PORT}`);
  console.log(`room:      ${code}   (subscribing to ${filter}, read-only)\n`);

  const mqttString = (s) => { const b = Buffer.from(s, 'utf8'); return Buffer.concat([Buffer.from([(b.length >> 8) & 0xff, b.length & 0xff]), b]); };
  const remainingLength = (n) => { const out = []; do { let d = n % 128; n = Math.floor(n / 128); if (n > 0) d |= 0x80; out.push(d); } while (n > 0); return Buffer.from(out); };
  const packet = (type, bits, body) => Buffer.concat([Buffer.from([(type << 4) | bits]), remainingLength(body.length), body]);
  const connectPacket = (id) => packet(1, 0, Buffer.concat([Buffer.from([0x00, 0x04, 0x4d, 0x51, 0x54, 0x54, 0x04, 0x02, 0x00, 0x1e]), mqttString(id)]));
  const subscribePacket = (id, t) => packet(8, 0x02, Buffer.concat([Buffer.from([(id >> 8) & 0xff, id & 0xff]), mqttString(t), Buffer.from([0x00])]));

  const sock = net.createConnection({ host: MQTT_HOST, port: MQTT_PORT });
  let buf = Buffer.alloc(0);
  let subscribed = false;
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
      const slot = body.subarray(2, 2 + tl).toString().slice(body.subarray(2, 2 + tl).toString().lastIndexOf('/') + 1);
      const payload = body.subarray(2 + tl).toString();
      let state = null;
      if (payload !== '') { try { state = JSON.parse(payload); } catch { state = null; } }
      report(slot, payload === '' ? '' : state, !seen.has(slot));
    }
  });
  sock.on('connect', () => {
    sock.write(connectPacket('reader-' + Math.random().toString(16).slice(2, 8)));
    setTimeout(() => sock.write(subscribePacket(1, filter)), 1200);
  });
  sock.on('error', (e) => { console.log('connection error:', e.message); });

  setTimeout(() => {
    console.log('');
    if (!subscribed) console.log('No SUBACK — could not subscribe (broker unreachable, blocked port, or a dropped SUBACK).');
    else if (seen.size === 0) console.log('Subscribed, but no member is publishing here.');
    else {
      console.log(`${seen.size} member(s) present:`);
      for (const [slot, s] of seen) console.log(`  ${slot}  song=${s.songId} pos=${s.positionMs} playing=${s.playing}`);
    }
    sock.destroy();
    process.exit(0);
  }, seconds * 1000);
}

(async () => {
  if (useMqtt) watchMqtt();
  else {
    await watchNtfy();
    console.log('');
    if (seen.size === 0) {
      console.log('No member is publishing here.');
      console.log('Check the room code, that the app is actually in that room,');
      console.log('and that BOTH sides are on the same transport (see README).');
    } else {
      console.log(`${seen.size} member(s) present:`);
      for (const [slot, s] of seen) console.log(`  ${slot}  song=${s.songId} pos=${s.positionMs} playing=${s.playing}`);
    }
    process.exit(0);
  }
})();
