// Verify peer_sim publishes a payload the app's parser accepts, and that commands change it.
const { spawn } = require('node:child_process');
const crypto = require('node:crypto');

const code = 'TEST' + crypto.randomBytes(4).toString('hex').toUpperCase();
const topic = 'yunyin-together-v1-' + code;
const BASE = process.env.NTFY_URL || 'https://ntfy.sh';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

let failures = 0;
const check = (label, cond, detail) => {
  console.log(`${cond ? 'ok  ' : 'FAIL'} ${label}${detail ? '  ' + detail : ''}`);
  if (!cond) failures++;
};

// Read the topic the way NtfyTransport does.
async function readPeer(selfUid) {
  const res = await fetch(`${BASE}/${topic}/json?poll=1&since=10m`);
  const body = await res.text();
  let latest = null;
  for (const line of body.split('\n')) {
    if (!line.trim()) continue;
    let event;
    try { event = JSON.parse(line); } catch { continue; }
    if (event.event !== 'message') continue;
    let p;
    try { p = JSON.parse(event.message); } catch { continue; }
    if (!p.uid || p.uid === selfUid) continue;
    latest = p;
  }
  return latest;
}

(async () => {
  // The sim reads commands from stdin, so drive it by writing lines.
  const child = spawn(process.execPath, [require('node:path').join(__dirname, 'peer_sim.js'), code, '--name', '模拟听众'], {
    cwd: __dirname,
    stdio: ['pipe', 'pipe', 'pipe'],
  });
  child.stdout.on('data', () => {});
  child.stderr.on('data', (d) => console.log('  [sim stderr]', String(d).trim()));

  await sleep(2500);
  let peer = await readPeer('self');
  check('sim publishes a parseable payload', !!peer, JSON.stringify(peer));
  check('the payload has the fields the app reads',
    peer && ['uid', 'name', 'songId', 'positionMs', 'playing', 'updatedAt', 'seq'].every((k) => k in peer),
    peer ? Object.keys(peer).join(',') : '');
  check('the sim is identified as a distinct member', peer && peer.uid.startsWith('sim-'), peer && peer.uid);
  check('the nickname comes through', peer && peer.name === '模拟听众', peer && peer.name);

  // A song change must reach the reader.
  child.stdin.write('song 555000111\n');
  await sleep(2500);
  peer = await readPeer('self');
  check('a song command propagates', peer && peer.songId === 555000111, peer && String(peer.songId));

  // Pausing must propagate.
  child.stdin.write('pause\n');
  await sleep(2500);
  peer = await readPeer('self');
  check('a pause command propagates', peer && peer.playing === false, peer && String(peer.playing));

  // The sequence number is what the app's tie-break uses, so it must move.
  const seqBefore = peer.seq;
  child.stdin.write('pos 90000\n');
  await sleep(2500);
  peer = await readPeer('self');
  check('an explicit position propagates', peer && peer.positionMs === 90000, peer && String(peer.positionMs));
  check('the sequence number advances on a user action', peer && peer.seq > seqBefore,
    `${seqBefore} -> ${peer && peer.seq}`);

  child.stdin.write('quit\n');
  await sleep(300);
  child.kill();
  console.log(failures === 0 ? '\nRESULT: peer_sim speaks the app protocol' : `\nRESULT: ${failures} FAILURES`);
  process.exit(failures === 0 ? 0 : 1);
})().catch((e) => {
  console.log('FAILED:', e.message);
  process.exit(1);
});
