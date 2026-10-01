// Validates the MQTT packet logic used by MqttClient.kt against a real public broker.
//
// Shape note: one data listener attached from the start, generous sleeps, no listener swapping. Two
// earlier versions of this file failed for harness reasons (a listener replaced mid-stream, and a
// publish raced before the SUBACK) while the protocol itself was fine — proven by printing the packets
// each socket actually received. This keeps the simple shape that works.
const net = require('node:net');
const crypto = require('node:crypto');

const HOST = process.env.MQTT_HOST || 'broker.hivemq.com';
const PORT = Number(process.env.MQTT_PORT || 1883);
const topic = 'yunyin/together/test/' + crypto.randomBytes(4).toString('hex');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

let failures = 0;
const check = (label, cond, detail) => {
  console.log(`${cond ? 'ok  ' : 'FAIL'} ${label}${detail ? '  ' + detail : ''}`);
  if (!cond) failures++;
};

const mqttString = (s) => { const b = Buffer.from(s, 'utf8'); return Buffer.concat([Buffer.from([(b.length >> 8) & 0xff, b.length & 0xff]), b]); };
const remainingLength = (n) => { const out = []; do { let d = n % 128; n = Math.floor(n / 128); if (n > 0) d |= 0x80; out.push(d); } while (n > 0); return Buffer.from(out); };
const packet = (type, bits, body) => Buffer.concat([Buffer.from([(type << 4) | bits]), remainingLength(body.length), body]);
// Keep-alive is big-endian (0x00, 0x1e = 30s) — the same detail that matters in the Kotlin client.
const connectPacket = (id) => packet(1, 0, Buffer.concat([Buffer.from([0x00, 0x04, 0x4d, 0x51, 0x54, 0x54, 0x04, 0x02, 0x00, 0x1e]), mqttString(id)]));
const subscribePacket = (id, t) => packet(8, 0x02, Buffer.concat([Buffer.from([(id >> 8) & 0xff, id & 0xff]), mqttString(t), Buffer.from([0x00])]));
const publishPacket = (t, p, retain) => packet(3, retain ? 0x01 : 0x00, Buffer.concat([mqttString(t), Buffer.from(p, 'utf8')]));

function parser(onPacket) {
  let buf = Buffer.alloc(0);
  return (chunk) => {
    buf = Buffer.concat([buf, chunk]);
    while (buf.length >= 2) {
      let mult = 1, len = 0, i = 1, enc;
      do { if (i >= buf.length) return; enc = buf[i++]; len += (enc & 0x7f) * mult; mult *= 128; } while (enc & 0x80);
      if (buf.length < i + len) return;
      const type = buf[0] >> 4;
      const body = buf.subarray(i, i + len);
      buf = buf.subarray(i + len);
      onPacket(type, body);
    }
  };
}

async function connect(id) {
  const sock = net.createConnection({ host: HOST, port: PORT });
  await new Promise((r, j) => { sock.once('connect', r); sock.once('error', j); });
  sock.write(connectPacket(id));
  await sleep(1500);
  return sock;
}

(async () => {
  console.log(`broker: ${HOST}:${PORT}\ntopic:  ${topic}\n`);

  // Subscriber: listener attached before anything is written, so nothing is missed.
  const received = [];
  let subAck = false;
  const sub = net.createConnection({ host: HOST, port: PORT });
  sub.on('data', parser((type, body) => {
    if (type === 9) subAck = true;
    if (type === 3) received.push(body.subarray(2 + ((body[0] << 8) | body[1])).toString());
  }));
  await new Promise((r, j) => { sub.once('connect', r); sub.once('error', j); });
  sub.write(connectPacket('s' + crypto.randomBytes(3).toString('hex')));
  await sleep(1500);
  sub.write(subscribePacket(1, topic));
  await sleep(2500);
  check('subscribe is acknowledged', subAck);

  const pub = await connect('p' + crypto.randomBytes(3).toString('hex'));
  pub.write(publishPacket(topic, JSON.stringify({ songId: 42 }), false));
  await sleep(3000);
  check('a plain publish reaches the subscriber', received.length >= 1, JSON.stringify(received));

  // RETAIN: a subscriber connecting later must receive the last state at once, without a heartbeat.
  const retained = topic + '/r';
  pub.write(publishPacket(retained, JSON.stringify({ songId: 99 }), true));
  await sleep(2000);

  const lateReceived = [];
  const late = net.createConnection({ host: HOST, port: PORT });
  late.on('data', parser((type, body) => {
    if (type === 3) lateReceived.push(body.subarray(2 + ((body[0] << 8) | body[1])).toString());
  }));
  await new Promise((r, j) => { late.once('connect', r); late.once('error', j); });
  late.write(connectPacket('l' + crypto.randomBytes(3).toString('hex')));
  await sleep(1500);
  late.write(subscribePacket(1, retained));
  await sleep(2500);
  check('a later subscriber gets the retained state immediately',
    lateReceived.length >= 1 && lateReceived[0].includes('99'), JSON.stringify(lateReceived));

  sub.destroy(); late.destroy(); pub.destroy();
  console.log(failures === 0
    ? '\nRESULT: MQTT packet logic works (connect / subscribe / publish / retain)'
    : `\nRESULT: ${failures} FAILURES`);
  process.exit(failures === 0 ? 0 : 1);
})().catch((e) => {
  console.log('FAILED:', e.message);
  process.exit(1);
});
