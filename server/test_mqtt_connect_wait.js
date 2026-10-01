// Explains the "尚未连接" bug, and pins the fact that matters for the fix.
//
// MqttTransport called connect() (async, returns immediately) and then published at once. Its own
// guard -- `if (!client.isConnected) return Failed("尚未连接")` -- therefore fired on every join.
//
// The obvious theory is "the publish happened too early and was lost". This test shows that theory is
// WRONG: the broker buffers bytes written before CONNACK and processes them fine. The guard was the
// whole problem, client-side. That distinction matters because it means the fix is "wait for the
// connection", not "retry the publish" -- and a fix aimed at the wrong mechanism would have looked
// plausible and not worked.
const net = require('node:net');
const crypto = require('node:crypto');

const HOST = process.env.MQTT_HOST || 'broker.hivemq.com';
const PORT = Number(process.env.MQTT_PORT || 1883);
const ROOT = 'yunyin/together/v1';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

let failures = 0;
const check = (label, cond, detail) => {
  console.log(`${cond ? 'ok  ' : 'FAIL'} ${label}${detail ? '  ' + detail : ''}`);
  if (!cond) failures++;
};

const mqttString = (s) => { const b = Buffer.from(s, 'utf8'); return Buffer.concat([Buffer.from([(b.length >> 8) & 0xff, b.length & 0xff]), b]); };
const remainingLength = (n) => { const out = []; do { let d = n % 128; n = Math.floor(n / 128); if (n > 0) d |= 0x80; out.push(d); } while (n > 0); return Buffer.from(out); };
const packet = (type, bits, body) => Buffer.concat([Buffer.from([(type << 4) | bits]), remainingLength(body.length), body]);
const connectPacket = (id) => packet(1, 0, Buffer.concat([Buffer.from([0x00, 0x04, 0x4d, 0x51, 0x54, 0x54, 0x04, 0x02, 0x00, 0x1e]), mqttString(id)]));
const subscribePacket = (id, t) => packet(8, 0x02, Buffer.concat([Buffer.from([(id >> 8) & 0xff, id & 0xff]), mqttString(t), Buffer.from([0x00])]));
const publishPacket = (t, p, retain) => packet(3, retain ? 0x01 : 0x00, Buffer.concat([mqttString(t), Buffer.from(p, 'utf8')]));

/** Opens a socket, reporting the moment CONNACK arrives. */
function open(clientId) {
  const sock = net.createConnection({ host: HOST, port: PORT });
  let connackAt = null;
  let buf = Buffer.alloc(0);
  const connacked = new Promise((resolve) => {
    sock.on('data', (chunk) => {
      buf = Buffer.concat([buf, chunk]);
      while (buf.length >= 2) {
        let mult = 1, len = 0, i = 1, enc;
        do { if (i >= buf.length) return; enc = buf[i++]; len += (enc & 0x7f) * mult; mult *= 128; } while (enc & 0x80);
        if (buf.length < i + len) return;
        const type = buf[0] >> 4;
        buf = buf.subarray(i + len);
        if (type === 2 && connackAt === null) { connackAt = Date.now(); resolve(true); }
      }
    });
  });
  return new Promise((resolve, reject) => {
    sock.once('connect', () => {
      sock.write(connectPacket(clientId));
      resolve({ sock, connacked, connackAt: () => connackAt });
    });
    sock.once('error', reject);
  });
}

(async () => {
  const code = 'WAIT' + crypto.randomBytes(3).toString('hex').toUpperCase();
  const earlyTopic = `${ROOT}/${code}/early`;
  const lateTopic = `${ROOT}/${code}/late`;
  console.log(`broker: ${HOST}:${PORT}\nroom:   ${code}\n`);

  // A subscriber that records everything, so we can tell whether a publish actually landed.
  const watcher = await open('w' + crypto.randomBytes(3).toString('hex'));
  const seen = [];
  let wbuf = Buffer.alloc(0);
  watcher.sock.on('data', (chunk) => {
    wbuf = Buffer.concat([wbuf, chunk]);
    while (wbuf.length >= 2) {
      let mult = 1, len = 0, i = 1, enc;
      do { if (i >= wbuf.length) return; enc = wbuf[i++]; len += (enc & 0x7f) * mult; mult *= 128; } while (enc & 0x80);
      if (wbuf.length < i + len) return;
      const type = wbuf[0] >> 4;
      const body = wbuf.subarray(i, i + len);
      wbuf = wbuf.subarray(i + len);
      if (type === 3) seen.push(body.subarray(2 + ((body[0] << 8) | body[1])).toString());
    }
  });
  await watcher.connacked;
  await sleep(500);
  watcher.sock.write(subscribePacket(1, `${ROOT}/${code}/+`));
  await sleep(2000);

  // Publish BEFORE the connection is acknowledged.
  const eager = await open('eager' + crypto.randomBytes(2).toString('hex'));
  eager.sock.write(publishPacket(earlyTopic, JSON.stringify({ who: 'early' }), true));
  check('a publish written before CONNACK is really sent before it',
    eager.connackAt() === null, `connackAt=${eager.connackAt()}`);

  // The behaviour the fix relies on: publish after CONNACK, which is what the client now waits for.
  const patient = await open('pat' + crypto.randomBytes(3).toString('hex'));
  await patient.connacked;
  patient.sock.write(publishPacket(lateTopic, JSON.stringify({ who: 'after-connack' }), true));

  // Judge both after a single generous wait; timing a fast assertion against a public broker is how an
  // earlier version of this file reported a false failure.
  await sleep(4000);
  check('the broker still delivers the early publish, so a lost message was never the cause',
    seen.some((s) => s.includes('early')), JSON.stringify(seen));
  check('waiting for CONNACK also delivers',
    seen.some((s) => s.includes('after-connack')), JSON.stringify(seen));

  eager.sock.destroy(); patient.sock.destroy(); watcher.sock.destroy();
  console.log(failures === 0
    ? '\nRESULT: the broker tolerates early writes — so 尚未连接 was the client guard, not a lost message'
    : `\nRESULT: ${failures} FAILURES`);
  process.exit(failures === 0 ? 0 : 1);
})().catch((e) => { console.log('FAILED:', e.message); process.exit(1); });

