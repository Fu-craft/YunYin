#!/usr/bin/env node
'use strict';

/**
 * 一起听 relay — a tiny room server for YunYin's listen-together feature.
 *
 * WHY THIS EXISTS
 * NetEase's own together-listen is driven over an Agora RTC channel
 * (the room payload carries `agoraChannelId`), and its HTTP API is *write-only* for playback
 * state: `sync/list/command`, `play/command` and `heartbeat` all return `result: true`, but
 * `sync/playlist/get` answers `data: {}` and `/listentogether/status` leaves `anotherDeviceInfo`
 * null. Measured directly, so a third-party client cannot read a peer's song or position over
 * NetEase's HTTP. This relay is the missing read path: both clients post their state here and poll
 * for the other's.
 *
 * ZERO DEPENDENCIES on purpose: it uses only Node built-ins, so deploying it is copying one file
 * and running `node together-relay.js` (or pointing pm2/systemd at it). Nothing to npm install on
 * a server you may not want to touch much.
 *
 * IT HOLDS NO SECRETS AND KNOWS NOTHING ABOUT ACCOUNTS. A room is a six-character code; a member is
 * an opaque id plus a display name. It never talks to NetEase and never sees a cookie.
 *
 * ENV
 *   PORT             listen port (default 8090)
 *   ROOM_TTL_MS      idle room lifetime (default 30 min)
 *   MEMBER_TTL_MS    how long a member is kept without a state post (default 15 s)
 *   ALLOW_ORIGIN     CORS origin for browser testing (default *)
 *   RELAY_TOKEN      optional shared secret. When set, every request except /health must carry it as
 *                    the `x-relay-token` header (or a `token` query parameter). Leave it unset and the
 *                    relay is open — which is fine on a LAN, and not fine on a public IP.
 */

const http = require('node:http');
const crypto = require('node:crypto');

const PORT = Number(process.env.PORT || 8090);
const ROOM_TTL_MS = Number(process.env.ROOM_TTL_MS || 30 * 60 * 1000);
const MEMBER_TTL_MS = Number(process.env.MEMBER_TTL_MS || 15 * 1000);
const ALLOW_ORIGIN = process.env.ALLOW_ORIGIN || '*';
const RELAY_TOKEN = process.env.RELAY_TOKEN || '';
const MAX_BODY = 8 * 1024;

/**
 * Unambiguous alphabet: no 0/O, 1/I/L. A room code gets read aloud and typed by hand, and those are
 * the pairs people get wrong.
 */
const CODE_ALPHABET = 'ABCDEFGHJKMNPQRSTUVWXYZ23456789';
const CODE_LENGTH = 6;

/** code -> { code, createdAt, touchedAt, hostUid, members: Map<uid, member> } */
const rooms = new Map();

function newCode() {
  let out = '';
  for (let i = 0; i < CODE_LENGTH; i++) out += CODE_ALPHABET[crypto.randomInt(CODE_ALPHABET.length)];
  return out;
}

/** A code that is not currently in use. Collisions are astronomically unlikely; checked anyway. */
function freshCode() {
  for (let attempt = 0; attempt < 50; attempt++) {
    const code = newCode();
    if (!rooms.has(code)) return code;
  }
  throw new Error('no free room code');
}

function memberIsStale(member, now) {
  return now - member.seenAt > MEMBER_TTL_MS;
}

function pruneRoom(room, now) {
  for (const [uid, member] of room.members) {
    if (memberIsStale(member, now)) room.members.delete(uid);
  }
  if (room.members.size === 0) {
    // An empty room is kept briefly so a reconnecting member does not lose it, but not indefinitely.
    if (now - room.touchedAt > MEMBER_TTL_MS * 4) rooms.delete(room.code);
  }
  if (now - room.touchedAt > ROOM_TTL_MS) rooms.delete(room.code);
}

setInterval(() => {
  const now = Date.now();
  for (const room of rooms.values()) pruneRoom(room, now);
}, 2000).unref?.();

// ------------------------------------------------------------------------------------ primitives

function send(res, status, payload) {
  const body = JSON.stringify(payload);
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(body),
    'Access-Control-Allow-Origin': ALLOW_ORIGIN,
    'Access-Control-Allow-Headers': 'Content-Type, X-Relay-Token',
    'Access-Control-Allow-Methods': 'GET, POST, DELETE, OPTIONS',
    'Cache-Control': 'no-store',
  });
  res.end(body);
}

/** Constant-time compare, so a token cannot be recovered one byte at a time by timing responses. */
function tokenMatches(supplied) {
  if (!RELAY_TOKEN) return true;
  const a = Buffer.from(String(supplied || ''));
  const b = Buffer.from(RELAY_TOKEN);
  if (a.length !== b.length) return false;
  return crypto.timingSafeEqual(a, b);
}

function readBody(req) {
  return new Promise((resolve) => {
    let size = 0;
    const chunks = [];
    req.on('data', (chunk) => {
      size += chunk.length;
      if (size > MAX_BODY) {
        req.destroy();
        resolve(null);
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => {
      const text = Buffer.concat(chunks).toString('utf8').trim();
      if (!text) return resolve({});
      try {
        const parsed = JSON.parse(text);
        resolve(parsed && typeof parsed === 'object' ? parsed : null);
      } catch {
        resolve(null);
      }
    });
    req.on('error', () => resolve(null));
  });
}

/** Coerce an incoming state into exactly the shape the client reads, so a bad client cannot inject
 *  types that would break everyone else's parser. */
function normaliseState(body) {
  const songId = Number(body.songId);
  const positionMs = Number(body.positionMs);
  return {
    songId: Number.isFinite(songId) && songId > 0 ? Math.trunc(songId) : 0,
    positionMs: Number.isFinite(positionMs) && positionMs >= 0 ? Math.trunc(positionMs) : 0,
    playing: body.playing === true,
    // The client's own timestamp, used to break ties when both members claim to be the source.
    updatedAt: Number.isFinite(Number(body.updatedAt)) ? Math.trunc(Number(body.updatedAt)) : Date.now(),
    // Bumped by the client every time the user acts, so peers can tell a fresh intent from a stale echo.
    seq: Number.isFinite(Number(body.seq)) ? Math.trunc(Number(body.seq)) : 0,
  };
}

function publicMember(member) {
  return {
    uid: member.uid,
    name: member.name,
    songId: member.songId,
    positionMs: member.positionMs,
    playing: member.playing,
    updatedAt: member.updatedAt,
    seq: member.seq,
  };
}

// ------------------------------------------------------------------------------------ routing

async function handle(req, res) {
  const url = new URL(req.url, 'http://localhost');
  const path = url.pathname.replace(/\/+$/, '');
  const parts = path.split('/').filter(Boolean);
  const now = Date.now();

  if (req.method === 'OPTIONS') return send(res, 204, {});

  // GET /health — deployment smoke test. Deliberately outside the token check so an operator can
  // verify the process is up with a plain curl.
  if (req.method === 'GET' && parts[0] === 'health') {
    return send(res, 200, { ok: true, rooms: rooms.size, now });
  }

  // Everything else needs the shared secret when one is configured.
  if (!tokenMatches(req.headers['x-relay-token'] || url.searchParams.get('token'))) {
    return send(res, 401, { error: 'unauthorized' });
  }

  // POST /room — create a room and become its host.
  if (req.method === 'POST' && parts.length === 1 && parts[0] === 'room') {
    const body = await readBody(req);
    if (!body) return send(res, 400, { error: 'bad_body' });
    const uid = String(body.uid || '');
    if (!uid) return send(res, 400, { error: 'uid_required' });
    const code = freshCode();
    const room = { code, createdAt: now, touchedAt: now, hostUid: uid, members: new Map() };
    rooms.set(code, room);
    room.members.set(uid, memberFrom(uid, body, now));
    return send(res, 200, { code, uid, host: true, members: 1 });
  }

  // Everything else is scoped to a room code.
  if (parts.length >= 2 && parts[0] === 'room') {
    const code = String(parts[1] || '').toUpperCase();
    const room = rooms.get(code);

    // GET /room/{code} — existence + member list, for a join attempt.
    if (req.method === 'GET' && parts.length === 2) {
      if (!room) return send(res, 404, { error: 'no_room' });
      pruneRoom(room, now);
      return send(res, 200, {
        code,
        members: [...room.members.values()].map((m) => ({ uid: m.uid, name: m.name })),
        count: room.members.size,
      });
    }

    // POST /room/{code}/join — register without a song yet (a member may join before playing).
    if (req.method === 'POST' && parts.length === 3 && parts[2] === 'join') {
      if (!room) return send(res, 404, { error: 'no_room' });
      const body = await readBody(req);
      if (!body) return send(res, 400, { error: 'bad_body' });
      const uid = String(body.uid || '');
      if (!uid) return send(res, 400, { error: 'uid_required' });
      room.members.set(uid, memberFrom(uid, body, now));
      room.touchedAt = now;
      return send(res, 200, { code, uid, members: room.members.size });
    }

    // POST /room/{code}/state — the heartbeat: this member's current song and position.
    if (req.method === 'POST' && parts.length === 3 && parts[2] === 'state') {
      if (!room) return send(res, 404, { error: 'no_room' });
      const body = await readBody(req);
      if (!body) return send(res, 400, { error: 'bad_body' });
      const uid = String(body.uid || '');
      if (!uid) return send(res, 400, { error: 'uid_required' });
      const state = normaliseState(body);
      const member = memberFrom(uid, body, now);
      Object.assign(member, state);
      room.members.set(uid, member);
      room.touchedAt = now;
      const others = [...room.members.values()].filter((m) => m.uid !== uid);
      return send(res, 200, { code, peers: others.map(publicMember) });
    }

    // GET /room/{code}/state?exclude={uid} — what the peer is playing. This is the read path
    // NetEase's HTTP API does not provide.
    if (req.method === 'GET' && parts.length === 3 && parts[2] === 'state') {
      if (!room) return send(res, 404, { error: 'no_room' });
      pruneRoom(room, now);
      const exclude = url.searchParams.get('exclude') || '';
      const peers = [...room.members.values()]
        .filter((m) => m.uid !== exclude)
        .map(publicMember);
      return send(res, 200, { code, peers, count: room.members.size });
    }

    // POST /room/{code}/leave — leave without closing the room for everyone.
    if (req.method === 'POST' && parts.length === 3 && parts[2] === 'leave') {
      if (!room) return send(res, 200, { ok: true });
      const body = await readBody(req);
      const uid = String((body || {}).uid || '');
      if (uid) room.members.delete(uid);
      room.touchedAt = now;
      return send(res, 200, { ok: true, members: room.members.size });
    }

    // DELETE /room/{code} — close the room (host).
    if (req.method === 'DELETE' && parts.length === 2) {
      rooms.delete(code);
      return send(res, 200, { ok: true });
    }
  }

  return send(res, 404, { error: 'not_found' });
}

function memberFrom(uid, body, now) {
  return {
    uid,
    name: String(body.name || '').slice(0, 40),
    songId: 0,
    positionMs: 0,
    playing: false,
    updatedAt: now,
    seq: 0,
    seenAt: now,
  };
}

const server = http.createServer((req, res) => {
  handle(req, res).catch((error) => {
    // A malformed request must not take the relay down for everyone in the room.
    try {
      send(res, 500, { error: 'internal', message: String(error && error.message) });
    } catch {
      /* response already gone */
    }
  });
});

server.listen(PORT, () => {
  console.log('[together-relay] listening on :%d  roomTtl=%dms memberTtl=%dms', PORT, ROOM_TTL_MS, MEMBER_TTL_MS);
});
