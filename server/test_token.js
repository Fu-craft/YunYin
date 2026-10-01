// Verify the relay's optional token: /health stays open, everything else needs the secret.
const BASE = 'http://127.0.0.1:8091';
const TOKEN = 'testtoken123';

let failures = 0;
function check(label, cond, detail) {
  console.log(`${cond ? 'ok  ' : 'FAIL'} ${label}${detail ? '  ' + detail : ''}`);
  if (!cond) failures++;
}

async function call(method, path, body, headers = {}) {
  const res = await fetch(BASE + path, {
    method,
    headers: body ? { 'Content-Type': 'application/json', ...headers } : headers,
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  let json = null;
  try { json = JSON.parse(text); } catch { json = text; }
  return { status: res.status, json };
}

(async () => {
  // health must work WITHOUT the token, or an operator cannot smoke-test it.
  const h = await call('GET', '/health');
  check('health is open without a token', h.status === 200 && h.json.ok === true);

  // Creating a room without the token must be refused.
  const noToken = await call('POST', '/room', { uid: 'a', name: 'a' });
  check('room create without token -> 401', noToken.status === 401, JSON.stringify(noToken.json));

  // A wrong token must also be refused.
  const wrong = await call('POST', '/room', { uid: 'a', name: 'a' }, { 'X-Relay-Token': 'nope' });
  check('room create with wrong token -> 401', wrong.status === 401);

  // The right token works.
  const ok = await call('POST', '/room', { uid: 'a', name: 'a' }, { 'X-Relay-Token': TOKEN });
  check('room create with correct token -> 200', ok.status === 200 && !!ok.json.code,
    JSON.stringify(ok.json));
  const code = ok.json.code;

  // ...and so does the read path, which is the point of the whole relay.
  const state = await call('POST', `/room/${code}/state`,
    { uid: 'a', name: 'a', songId: 12345, positionMs: 9999, playing: true },
    { 'X-Relay-Token': TOKEN });
  check('state post with token -> 200', state.status === 200);

  const read = await call('GET', `/room/${code}/state?exclude=b`, null, { 'X-Relay-Token': TOKEN });
  const peer = (read.json.peers || [])[0];
  check('peer state readable with token', peer && peer.songId === 12345 && peer.positionMs === 9999,
    JSON.stringify(read.json));

  // A query-parameter token also works (handy for curl).
  const viaQuery = await call('GET', `/room/${code}?token=${TOKEN}`);
  check('token accepted as a query parameter', viaQuery.status === 200);

  // Clean up.
  await call('DELETE', `/room/${code}`, null, { 'X-Relay-Token': TOKEN });

  console.log(failures === 0 ? '\nRESULT: token gate behaves correctly' : `\nRESULT: ${failures} FAILURES`);
  process.exit(failures === 0 ? 0 : 1);
})();
