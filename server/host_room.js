// A headless room host: publishes a fixed track forever, with no stdin. Use it to give the app a
// "peer" to follow when there is no second device.
//
//   node server/host_room.js <ROOM_CODE> [songId] [startPositionMs] [playing]
//
// Prints a line per publish so the terminal shows it is alive.
const BASE = (process.env.NTFY_URL || 'https://ntfy.sh').replace(/\/+$/, '');
const [code, songArg, posArg, playArg] = process.argv.slice(2);
if (!code) {
  console.error('usage: node server/host_room.js <ROOM_CODE> [songId] [positionMs] [playing]');
  process.exit(2);
}
const songId = Number(songArg || 1330348068);   // 起风了 (fee=8, free) by default
let positionMs = Number(posArg || 20000);
const playing = playArg !== 'false';
const INTERVAL = 3000;

const topic = 'yunyin-together-v1-' + code.replace(/[^A-Za-z0-9]/g, '').toUpperCase();
const uid = 'host-' + Math.random().toString(16).slice(2, 8);
let seq = 1;

async function publish() {
  const body = JSON.stringify({
    uid, name: '测试对方', songId, positionMs, playing, updatedAt: Date.now(), seq,
  });
  try {
    const res = await fetch(`${BASE}/${topic}`, { method: 'POST', body });
    const json = await res.json().catch(() => ({}));
    console.log(`[${new Date().toLocaleTimeString()}] song=${songId} pos=${positionMs} playing=${playing} -> HTTP ${res.status}${res.ok ? '' : ' ' + JSON.stringify(json).slice(0, 120)}`);
    if (!res.ok) return;
  } catch (e) {
    console.log(`publish failed: ${e.message}`);
    return;
  }
  // Advance the reported position while "playing", like a real player.
  if (playing) positionMs += INTERVAL;
}

console.log(`topic: ${BASE}/${topic}`);
console.log(`hosting as ${uid}, song=${songId}, playing=${playing}. Ctrl+C to stop.\n`);
publish();
setInterval(publish, INTERVAL);
