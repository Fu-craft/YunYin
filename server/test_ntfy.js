// End-to-end test of the public-service transport protocol, against the real service.
//
// This deliberately mirrors NtfyTransport.kt: publish the state as JSON to the topic, then poll
// /json?poll=1&since=<last event id> and keep the newest state per uid. If the Kotlin side stops
// agreeing with this, the feature breaks in a way unit tests on pure functions cannot catch — so this
// lives here, next to the protocol it describes.
//
// Run:  node server/test_ntfy.js
// It talks to the public instance, so it needs network access.

const crypto = require('node:crypto');

const BASE = process.env.NTFY_URL || 'https://ntfy.sh';
const ALPHABET = 'ABCDEFGHJKMNPQRSTUVWXYZ23456789';
const CODE_LENGTH = 12;
const TOPIC_PREFIX = 'yunyin-together-v1-';
const MEMBER_TTL_MS = 15000;

let failures = 0;
function check(label, cond, detail) {
  console.log(`${cond ? 'ok  ' : 'FAIL'} ${label}${detail ? '  ' + detail : ''}`);
  if (!cond) failures++;
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function freshCode() {
  let out = '';
  for (let i = 0; i < CODE_LENGTH; i++) out += ALPHABET[crypto.randomInt(ALPHABET.length)];
  return out;
}

function topicFor(code) {
  return TOPIC_PREFIX + code.replace(/[^A-Za-z0-9]/g, '').toUpperCase();
}

/** One member: the same cursor-per-event-id model the Kotlin transport uses. */
class Member {
  constructor(uid, name, code) {
    this.uid = uid;
    this.name = name;
    this.topic = topicFor(code);
    this.lastEventId = null;
    this.peers = new Map();
    this.heardAt = new Map();
  }

  async publish(songId, positionMs, playing, seq = 0) {
    const res = await fetch(`${BASE}/${this.topic}`, {
      method: 'POST',
      body: JSON.stringify({
        uid: this.uid, name: this.name, songId, positionMs, playing,
        updatedAt: Date.now(), seq,
      }),
    });
    if (!res.ok) throw new Error('publish HTTP ' + res.status);
  }

  async poll() {
    const cursor = this.lastEventId ? `since=${this.lastEventId}` : 'since=30m';
    const res = await fetch(`${BASE}/${this.topic}/json?poll=1&${cursor}`);
    if (!res.ok) throw new Error('poll HTTP ' + res.status);
    const body = await res.text();
    for (const line of body.split('\n')) {
      if (!line.trim()) continue;
      let event;
      try { event = JSON.parse(line); } catch { continue; }
      if (event.event !== 'message') continue;
      if (event.id) this.lastEventId = event.id;
      let payload;
      try { payload = JSON.parse(event.message); } catch { continue; }
      if (!payload.uid || payload.uid === this.uid) continue;
      this.peers.set(payload.uid, payload);
      this.heardAt.set(payload.uid, Date.now());
    }
    const now = Date.now();
    for (const [uid, at] of [...this.heardAt]) {
      if (now - at > MEMBER_TTL_MS) { this.peers.delete(uid); this.heardAt.delete(uid); }
    }
    return [...this.peers.values()];
  }
}

(async () => {
  const code = freshCode();
  console.log(`base=${BASE}  code=${code}  topic=${topicFor(code)}\n`);

  const host = new Member('host-1', '主持人', code);
  const guest = new Member('guest-2', '听众', code);

  // The host publishes a song; the guest, having only the code, must be able to read it back.
  await host.publish(1824020871, 42000, true, 1);
  await sleep(2500);
  let seen = await guest.poll();
  check('guest reads the host song', seen.length === 1 && seen[0].songId === 1824020871,
    JSON.stringify(seen));
  check('guest reads the host position', seen.length === 1 && seen[0].positionMs === 42000);
  check('guest reads the host play state', seen.length === 1 && seen[0].playing === true);
  check('the payload carries no account or cookie',
    seen.length === 1 && !('cookie' in seen[0]) && !('token' in seen[0]));

  // The guest announces itself; the host must see both members' identity.
  await guest.publish(0, 0, false, 0);
  await sleep(2500);
  seen = await host.poll();
  check('host sees the guest arrive', seen.length === 1 && seen[0].uid === 'guest-2',
    JSON.stringify(seen));
  check('a member with no song does not claim a track', seen[0].songId === 0);

  // A track change propagates with only the new message read (cursor advances).
  await host.publish(999888777, 1000, true, 2);
  await sleep(2500);
  seen = await guest.poll();
  check('a later track reaches the guest', seen.length === 1 && seen[0].songId === 999888777,
    JSON.stringify(seen));

  // Two polls in a row must not re-read the same message (the cursor works).
  const before = guest.lastEventId;
  await guest.poll();
  check('the cursor advances, so nothing is re-read', guest.lastEventId === before);

  // A second code must be a different conversation entirely.
  const other = new Member('host-1', '主持人', freshCode());
  await other.publish(111, 0, true, 1);
  await sleep(2500);
  const cross = await guest.poll();
  check('a different room code is a different conversation',
    cross.every((p) => p.songId !== 111), JSON.stringify(cross));

  console.log(failures === 0
    ? '\nRESULT: ntfy transport protocol behaves correctly'
    : `\nRESULT: ${failures} FAILURES`);
  process.exit(failures === 0 ? 0 : 1);
})().catch((e) => {
  console.log('FAILED:', e.name, e.message);
  process.exit(1);
});
