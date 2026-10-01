// End-to-end test of the together relay: two simulated members, full lifecycle.
// Exits non-zero on the first failed assertion so it can gate a build.

const BASE = 'http://127.0.0.1:8090';

let failures = 0;
function check(label, cond, detail) {
  console.log(`${cond ? 'ok  ' : 'FAIL'} ${label}${detail ? '  ' + detail : ''}`);
  if (!cond) failures++;
}

async function j(method, path, body) {
  const res = await fetch(BASE + path, {
    method,
    headers: body ? { 'Content-Type': 'application/json' } : {},
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  let parsed = null;
  try { parsed = JSON.parse(text); } catch { parsed = text; }
  return { status: res.status, json: parsed };
}

(async () => {
  // health
  const h = await j('GET', '/health');
  check('health responds', h.status === 200 && h.json.ok === true, JSON.stringify(h.json));

  // host creates a room
  const created = await j('POST', '/room', { uid: 'host-1', name: '主持人' });
  check('room created', created.status === 200 && !!created.json.code, JSON.stringify(created.json));
  const code = created.json.code;
  check('code is 6 chars from the unambiguous alphabet',
    typeof code === 'string' && /^[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{6}$/.test(code), code);

  // a second member looks the room up and joins
  const lookup = await j('GET', `/room/${code}`);
  check('join lookup finds the room', lookup.status === 200 && lookup.json.count === 1);

  const joined = await j('POST', `/room/${code}/join`, { uid: 'guest-2', name: '听众' });
  check('second member joined', joined.status === 200 && joined.json.members === 2, JSON.stringify(joined.json));

  // host reports state — the guest must be able to READ it (the whole point)
  const posted = await j('POST', `/room/${code}/state`, {
    uid: 'host-1', name: '主持人', songId: 1824020871, positionMs: 42000, playing: true,
    updatedAt: Date.now(), seq: 1,
  });
  check('host posted state', posted.status === 200);

  const read = await j('GET', `/room/${code}/state?exclude=guest-2`);
  const peer = (read.json.peers || [])[0];
  check('guest can READ the host song', peer && peer.songId === 1824020871, JSON.stringify(read.json));
  check('guest can READ the host position', peer && peer.positionMs === 42000);
  check('guest can READ the host playing flag', peer && peer.playing === true);
  check('caller is excluded from its own peer list',
    (read.json.peers || []).every((p) => p.uid !== 'guest-2'));

  // host reading its own view must not see itself
  const hostView = await j('GET', `/room/${code}/state?exclude=host-1`);
  check('host sees only the peer', (hostView.json.peers || []).length === 1 &&
    hostView.json.peers[0].uid === 'guest-2');

  // one member leaves; the room survives for the other
  await j('POST', `/room/${code}/leave`, { uid: 'guest-2' });
  const afterLeave = await j('GET', `/room/${code}`);
  check('room survives one leaving', afterLeave.status === 200 && afterLeave.json.count === 1,
    JSON.stringify(afterLeave.json));

  // unknown room is a clean 404, not a crash
  const missing = await j('GET', '/room/ZZZZZZ');
  check('unknown room -> 404', missing.status === 404, JSON.stringify(missing.json));

  // bad bodies are rejected, not fatal
  const badBody = await j('POST', `/room/${code}/state`, null);
  check('missing body rejected', badBody.status === 400);

  // host closes the room
  const closed = await j('DELETE', `/room/${code}`);
  check('room closed', closed.status === 200);
  const gone = await j('GET', `/room/${code}`);
  check('closed room is gone', gone.status === 404);

  console.log(failures === 0 ? '\nRESULT: relay behaves correctly' : `\nRESULT: ${failures} FAILURES`);
  process.exit(failures === 0 ? 0 : 1);
})();
