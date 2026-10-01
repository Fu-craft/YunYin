// End-to-end validation of MqttTransport's ROOM LAYOUT, over the real broker:
//   publish (retained)  yunyin/together/v1/<code>/<uid>
//   subscribe           yunyin/together/v1/<code>/+
//   leave               publish "" retained to clear the slot
// This is the part that can silently be wrong even when single publish/subscribe works.
const net = require('node:net');
const crypto = require('node:crypto');

const HOST = process.env.MQTT_HOST || 'broker.hivemq.com';
const PORT = Number(process.env.MQTT_PORT || 1883);
const ROOT = 'yunyin/together/v1';
const code = 'TEST' + crypto.randomBytes(3).toString('hex').toUpperCase();
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

/** A member: its own uid slot, subscribing to the room wildcard, exactly as MqttTransport does. */
async function openMember(uid, name) {
  const sock = net.createConnection({ host: HOST, port: PORT });
  const slots = new Map();          // uid -> payload ("" means tombstone)
  sock.on('data', parser((type, body) => {
    if (type !== 3) return;
    const tl = (body[0] << 8) | body[1];
    const topic = body.subarray(2, 2 + tl).toString();
    const payload = body.subarray(2 + tl).toString();
    const slot = topic.substringAfterLastSlash(topic);
    if (slot === uid) return;                       // the transport ignores its own slot
    slots.set(slot, payload);
  }));
  await new Promise((r, j) => { sock.once('connect', r); sock.once('error', j); });
  sock.write(connectPacket(uid));
  await sleep(1500);
  sock.write(subscribePacket(1, `${ROOT}/${code}/+`));
  await sleep(2000);
  return { uid, name, sock, slots };
}

String.prototype.substringAfterLastSlash = function (s) { return s.slice(s.lastIndexOf('/') + 1); };
function slotOf(topic) { return topic.slice(topic.lastIndexOf('/') + 1); }

(async () => {
  console.log(`broker: ${HOST}:${PORT}\nroom:   ${code}\n`);

  const alice = await openMember('alice-1', 'Alice');
  const bob = await openMember('bob-2', 'Bob');

  // Alice publishes her retained state the way the transport does.
  alice.sock.write(publishPacket(`${ROOT}/${code}/${alice.uid}`,
    JSON.stringify({ uid: alice.uid, name: 'Alice', songId: 111, positionMs: 5000, playing: true, updatedAt: Date.now(), seq: 1 }), true));
  await sleep(2000);
  check('Bob sees Alice\'s slot', bob.slots.get('alice-1')?.includes('111'), JSON.stringify([...bob.slots]));

  // Bob publishes too — and crucially, it must NOT overwrite Alice's slot (separate slots per member).
  bob.sock.write(publishPacket(`${ROOT}/${code}/${bob.uid}`,
    JSON.stringify({ uid: bob.uid, name: 'Bob', songId: 222, positionMs: 0, playing: false, updatedAt: Date.now(), seq: 1 }), true));
  await sleep(2000);
  check('per-member slots do not overwrite each other',
    alice.slots.get('bob-2')?.includes('222') && bob.slots.get('alice-1')?.includes('111'),
    `alice sees ${JSON.stringify([...alice.slots])} bob sees ${JSON.stringify([...bob.slots])}`);

  // A LATER joiner must receive both retained states immediately — the reason for retaining at all.
  const carol = await openMember('carol-3', 'Carol');
  await sleep(2500);
  check('a late joiner immediately gets both retained states',
    carol.slots.get('alice-1')?.includes('111') && carol.slots.get('bob-2')?.includes('222'),
    JSON.stringify([...carol.slots]));

  // Leaving clears the slot with an empty retained payload — otherwise a departed member lingers.
  alice.sock.write(publishPacket(`${ROOT}/${code}/${alice.uid}`, '', true));
  await sleep(2000);
  check('leaving clears the retained slot',
    carol.slots.get('alice-1') === '', `carol sees alice-1 = ${JSON.stringify(carol.slots.get('alice-1'))}`);

  alice.sock.destroy(); bob.sock.destroy(); carol.sock.destroy();
  console.log(failures === 0
    ? '\nRESULT: the room layout works (per-member retained slots, wildcard, tombstones)'
    : `\nRESULT: ${failures} FAILURES`);
  process.exit(failures === 0 ? 0 : 1);
})().catch((e) => { console.log('FAILED:', e.message); process.exit(1); });
