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

console.log(`topic: ${BASE}/${topic}`);
console.log(`listening ${seconds}s (read-only, nothing is published)\n`);

const seen = new Map();
let lastId = null;
let polls = 0;

async function poll() {
  polls++;
  const cursor = lastId ? `since=${lastId}` : 'since=30m';
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
    if (event.event !== 'message') continue;
    if (event.id) lastId = event.id;
    let payload;
    try { payload = JSON.parse(event.message); } catch { continue; }
    const key = payload.uid;
    const isNew = !seen.has(key);
    seen.set(key, payload);
    if (isNew) {
      console.log(`[member appeared] uid=${payload.uid} name="${payload.name}"`);
    } else {
      console.log(`  update uid=${payload.uid} song=${payload.songId} pos=${payload.positionMs}ms playing=${payload.playing} seq=${payload.seq}`);
    }
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
    console.log(`\n${seen.size} member(s) publishing here:`);
    for (const p of seen.values()) console.log('  ' + JSON.stringify(p));
  }
})();
