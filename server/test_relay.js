// End-to-end test of the relay API, against whatever base URL is given.
//
//   node server/test_relay.js [BASE_URL] [TOKEN]
//   BASE_URL defaults to the local relay; TOKEN defaults to $RELAY_TOKEN (empty = no auth).
//
// The same API is served by server/together-relay.js and by the Cloudflare Worker
// (server/cloudflare/worker.js), which is why this takes a URL: one test covers both.
const BASE = (process.argv[2] || process.env.RELAY_URL || 'http://127.0.0.1:8090').replace(/\/+$/, '');
const TOKEN = process.argv[3] || process.env.RELAY_TOKEN || '';

let failures = 0;
function check(label, cond, detail) {
  console.log(`${cond ? 'ok  ' : 'FAIL'} ${label}${detail ? '  ' + detail : ''}`);
  if (!cond) failures++;
}

const HEADERS = { 'Content-Type': 'application/json' };
if (TOKEN) HEADERS['X-Relay-Token'] = TOKEN;

async function j(method, path, body) {
  const res = await fetch(BASE + path, {
    method,
    headers: HEADERS,
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  let parsed = null;
  try { parsed = JSON.parse(text); } catch { parsed = text; }
  return { status: res.status, json: parsed };
}

(async () => {
  console.log(`base=${BASE}  token=${TOKEN ? 'yes' : 'none'}\n`);

  const h = await j('GET', '/health');
  check('health responds', h.status === 200 && h.json.ok === true, JSON.stringify(h.json));

  const created = await j('POST', '/room', { uid: 'host-1', name: '主持人' });
  check('room created', created.status === 200 && !!created.json.code, JSON.stringify(created.json));
  const code = created.json.code;
  check('code is 6 chars from the unambiguous alphabet',
    typeof code === 'string' && /^[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{6}$/.test(code), code);

  const lookup = await j('GET', `/room/${code}`);
  check('join lookup finds the room', lookup.status === 200 && lookup.json.count === 1);

  const joined = await j('POST', `/room/${code}/join`, { uid: 'guest-2', name: '听众' });
  check('second member joined', joined.status === 200 && joined.json.members === 2, JSON.stringify(joined.json));

  const posted = await j('POST', `/room/${code}/state`, {
    uid: 'host-1', name: '主持人', songId: 1824020871, positionMs: 42000, playing: true,
    updatedAt: Date.now(), seq: 1,
  });
  check('host posted state', posted.status === 200);
  const postedPeer = (posted.json.peers || [])[0];
  check('posting also returns the peer', postedPeer && postedPeer.uid === 'guest-2',
    JSON.stringify(posted.json));

  const read = await j('GET', `/room/${code}/state?exclude=guest-2`);
  const peer = (read.json.peers || [])[0];
  check('guest can READ the host song', peer && peer.songId === 1824020871, JSON.stringify(read.json));
  check('guest can READ the host position', peer && peer.positionMs === 42000);
  check('guest can READ the host playing flag', peer && peer.playing === true);
  check('the caller is excluded from its own peer list',
    (read.json.peers || []).every((p) => p.uid !== 'guest-2'));

  const hostView = await j('GET', `/room/${code}/state?exclude=host-1`);
  check('host sees only the peer', (hostView.json.peers || []).length === 1 &&
    hostView.json.peers[0].uid === 'guest-2');

  await j('POST', `/room/${code}/leave`, { uid: 'guest-2' });
  const afterLeave = await j('GET', `/room/${code}`);
  check('room survives one leaving', afterLeave.status === 200 && afterLeave.json.count === 1,
    JSON.stringify(afterLeave.json));

  const missing = await j('GET', '/room/ZZZZZZ');
  check('unknown room -> 404', missing.status === 404, JSON.stringify(missing.json));

  const badBody = await j('POST', `/room/${code}/state`, null);
  check('missing body rejected', badBody.status === 400);

  const closed = await j('DELETE', `/room/${code}`);
  check('room closed', closed.status === 200);
  const gone = await j('GET', `/room/${code}`);
  check('closed room is gone', gone.status === 404);

  console.log(failures === 0 ? '\nRESULT: relay behaves correctly' : `\nRESULT: ${failures} FAILURES`);
  process.exit(failures === 0 ? 0 : 1);
})().catch((e) => {
  console.log('FAILED:', e.name, e.message);
  process.exit(1);
});
