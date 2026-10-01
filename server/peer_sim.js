#!/usr/bin/env node
'use strict';

/**
 * peer_sim — impersonate the OTHER member of a listen-together room, from this computer.
 *
 * WHY THIS EXISTS
 * Testing listen-together normally needs two devices signed in. With one device you can still test
 * the half that actually contains the logic — *reading a peer and following it* — by having this
 * script play the peer. Start a room in the app, then point this at that room code, and the phone
 * will follow whatever this publishes: change the song here and the phone loads it, pause here and
 * the phone pauses, set a position and the phone seeks to it.
 *
 * It speaks the same protocol as the app's NtfyTransport:
 *   topic  yunyin-together-v1-<CODE, upper-cased, separators stripped>
 *   body   {"uid","name","songId","positionMs","playing","updatedAt","seq"}
 * and re-publishes every couple of seconds so the app does not expire the member as silent.
 *
 * USAGE
 *   node server/peer_sim.js <ROOM_CODE> [--name 对方] [--song 1824020871] [--pos 0] [--paused]
 *
 *   Then type commands (state is re-published automatically):
 *     song <id>      switch the room to another track
 *     pos <ms>       jump to a position (the phone will seek)
 *     pause | play   toggle the shared play state
 *     drift <ms>     offset every reported position, to see the tolerance in action
 *     status         print what is currently being published
 *     quit
 *
 * ENV
 *   NTFY_URL   override the pub/sub host (default https://ntfy.sh)
 */

const crypto = require('node:crypto');
const readline = require('node:readline');

const NTFY = (process.env.NTFY_URL || 'https://ntfy.sh').replace(/\/+$/, '');
const PUBLISH_MS = 2000;

// ---------------------------------------------------------------- arguments

const argv = process.argv.slice(2);
const code = (argv[0] || '').trim();
if (!code) {
  console.error('usage: node server/peer_sim.js <ROOM_CODE> [--name 对方] [--song <id>] [--pos <ms>] [--paused]');
  process.exit(2);
}
function flag(name, fallback) {
  const i = argv.indexOf('--' + name);
  return i >= 0 && argv[i + 1] && !argv[i + 1].startsWith('--') ? argv[i + 1] : fallback;
}

const topic = 'yunyin-together-v1-' + code.replace(/[^A-Za-z0-9]/g, '').toUpperCase();
const state = {
  uid: 'sim-' + crypto.randomBytes(4).toString('hex'),
  name: flag('name', '对方'),
  songId: Number(flag('song', 1824020871)),
  positionMs: Number(flag('pos', 0)),
  playing: !argv.includes('--paused'),
  driftMs: 0,
  seq: 1,
};

// ---------------------------------------------------------------- publishing

function payload() {
  return JSON.stringify({
    uid: state.uid,
    name: state.name,
    songId: state.songId,
    // The drift is applied here rather than to the stored value, so it behaves like a real peer whose
    // clock/position genuinely differs from ours.
    positionMs: Math.max(0, Math.round(state.positionMs + state.driftMs)),
    playing: state.playing,
    updatedAt: Date.now(),
    seq: state.seq,
  });
}

async function publish() {
  try {
    const res = await fetch(`${NTFY}/${topic}`, { method: 'POST', body: payload() });
    if (!res.ok) console.log(`  [warn] publish HTTP ${res.status}`);
  } catch (e) {
    console.log(`  [warn] publish failed: ${e.message}`);
  }
}

console.log('==============================================');
console.log(` 扮演对方成员`);
console.log(` 房间码: ${code}`);
console.log(` topic : ${topic}`);
console.log(` 成员id: ${state.uid}`);
console.log(` 昵称  : ${state.name}`);
console.log('==============================================');
console.log('正在每 2 秒发布一次状态。在手机 App 里创建/加入同一个房间，它就会跟随这里。');
console.log('命令：song <id> | pos <ms> | play | pause | drift <ms> | status | quit');
console.log('');

// Advance the reported position in step with playback, like a real player would.
setInterval(() => {
  if (state.playing) state.positionMs += PUBLISH_MS;
}, PUBLISH_MS);
setInterval(publish, PUBLISH_MS);
publish();

// ---------------------------------------------------------------- commands

const rl = readline.createInterface({ input: process.stdin, output: process.stdout, prompt: '> ' });
rl.prompt();

rl.on('line', async (line) => {
  const [cmd, arg] = line.trim().split(/\s+/);
  switch ((cmd || '').toLowerCase()) {
    case 'song':
      state.songId = Number(arg);
      state.positionMs = 0;
      state.seq++;
      console.log(`  -> 切到歌曲 ${state.songId}（手机应加载它）`);
      break;
    case 'pos':
      state.positionMs = Number(arg);
      state.seq++;
      console.log(`  -> 进度跳到 ${state.positionMs}ms（手机应 seek 过来）`);
      break;
    case 'play':
      state.playing = true;
      state.seq++;
      console.log('  -> 播放');
      break;
    case 'pause':
      state.playing = false;
      state.seq++;
      console.log('  -> 暂停');
      break;
    case 'drift':
      state.driftMs = Number(arg);
      console.log(`  -> 位置偏移 ${state.driftMs}ms（大于 2 秒才会触发纠正；6 秒内不重复纠正）`);
      break;
    case 'status':
      console.log('  ' + payload());
      break;
    case 'quit':
    case 'exit':
      console.log('结束（离开房间：不会再发布，App 侧约 15 秒后把你判为离开）');
      process.exit(0);
      break;
    case '':
      break;
    default:
      console.log('  未知命令。可用：song <id> | pos <ms> | play | pause | drift <ms> | status | quit');
  }
  await publish();
  rl.prompt();
});

rl.on('close', () => process.exit(0));
