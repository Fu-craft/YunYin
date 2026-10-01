// Read a listen-together room topic and print what is being published there.
// Read-only: it never writes, so it cannot disturb the room.
const BASE = (process.env.NTFY_URL || 'https://ntfy.sh').replace(/\/+$/, '');
const code = process.argv[2];
if (!code) {
  console.error('usage: node server/read_room.js <ROOM_CODE> [seconds]');
  process.exit(2);
}
const seconds = Number(process.argv[3] || 15);
const topic = 'yunyin-together-v1-' + code.replace(/[^A-Za-z0-9]/g, '').toUpperCase();

/**
 * A scratch topic used only to ask the service what time it thinks it is.
 *
 * Ages must be measured on the *service's* clock, not this machine's: a phone and a PC routinely
 * differ by tens of seconds, and comparing locally produced readings like "+-52s ago" — and worse,
 * classified live traffic as stale. Posting a throwaway message to an unrelated topic is the cheapest
 * way to read that clock without touching the user's room.
 */
const CLOCK_TOPIC = 'yunyin-clock-probe';

async function serviceNow() {
  try {
    const res = await fetch(`${BASE}/${CLOCK_TOPIC}`, { method: 'POST', body: '{}' });
    const json = await res.json();
    return json && json.time ? json.time : null;
  } catch {
    return null;
  }
}

console.log(`topic: ${BASE}/${topic}`);
console.log(`listening ${seconds}s (read-only, nothing is published to the room)\n`);

const seen = new Map();
let lastId = null;
let polls = 0;

async function poll() {
  polls++;
  const cursor = lastId ? `since=${lastId}` : 'since=30m';
  // Ask the service for "now" first, so every age below is on one clock — its own.
  const now = await serviceNow();
  let text;
  try {
    const res = await fetch(`${BASE}/${topic}/json?poll=1&${cursor}`);
    if (!res.ok) { console.log(`  [warn] poll HTTP ${res.status}`); return; }
    text = await res.text();
  } catch (e) {
    console.log(`  [warn] poll failed: ${e.message}`);
    return;
  }
  for (const line of text.split('\n')) {
    if (!line.trim()) continue;
    let event;
    try { event = JSON.parse(line); } catch { continue; }
    if (event.event !== 'message' || !event.time) continue;
    if (event.id) lastId = event.id;
    let payload;
    try { payload = JSON.parse(event.message); } catch { continue; }
    // The age matters more than the content: a poll replays a window of history, so without this a
    // message from ten minutes ago reads exactly like live traffic — which is precisely the mistake
    // this tool exists to prevent. Measured on the service clock, never a local one: a phone and this
    // machine differ by tens of seconds, which would make live traffic look stale (and did).
    const ageS = now === null ? null : Math.max(0, now - event.time);
    const age = ageS === null ? '' : ` (${ageS}s ago)`;
    const isLive = ageS !== null && ageS < 10;
    if (!seen.has(payload.uid)) {
      console.log(`[member] uid=${payload.uid} name="${payload.name}"${age}`);
    } else if (isLive) {
      console.log(`  LIVE uid=${payload.uid} song=${payload.songId} pos=${payload.positionMs}ms playing=${payload.playing}${age}`);
    } else {
      console.log(`  .old uid=${payload.uid} song=${payload.songId}${age}`);
    }
    seen.set(payload.uid, { ...payload, ageS });
  }
}

(async () => {
  const until = Date.now() + seconds * 1000;
  while (Date.now() < until) {
    await poll();
    await new Promise((r) => setTimeout(r, 3000));
  }
  console.log(`\n${polls} polls done.`);
  if (seen.size === 0) {
    console.log('NOTHING FOUND on this topic.');
    console.log('Possible reasons: the app is not in a room with this code, it has not');
    console.log('published yet, or the build does not include the together feature.');
  } else {
    console.log(`\n${seen.size} member(s) seen in the polled window:`);
    for (const p of seen.values()) {
      const live = p.ageS !== null && p.ageS < 10;
      console.log(`  uid=${p.uid} name="${p.name}" song=${p.songId} pos=${p.positionMs}ms ` +
        `playing=${p.playing} last-heard=${p.ageS}s ago${live ? '  <- LIVE' : '  <- stale, not live traffic'}`);
    }
  }
})();
