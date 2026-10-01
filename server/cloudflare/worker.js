/**
 * 一起听中继 · Cloudflare Workers 版
 *
 * 为什么需要它：两台不同网络的设备要互相找到，中间必须有一个双方都能访问、且一直开着的点。
 * 自己电脑关掉后，这个点只能放在云端。Workers 免费版正是为此合适：
 *   - 走 443（HTTPS），几乎所有网络都放行（实测用户的运营商封锁 MQTT 的 1883/8883，但 443 正常）
 *   - 永久在线，不需要任何机器常开
 *   - 免费额度 10 万请求/天，两人房间按 5 秒一次心跳约 3.4 万/天，余量充足
 *
 * 接口与 server/together-relay.js 完全一致，所以 App 端一行代码都不用改，只换地址。
 *
 * 房间状态放在 Durable Object 的内存里（每个房间一个对象）。刻意不写存储：
 * 房间本身是短生命周期的临时的状态，心跳每几秒重报一次；即使对象被回收，
 * 两端下一次上报就会把房间重建出来。这样也不产生存储读写计费。
 */

const ALLOW_ORIGIN = '*';
const ROOM_TTL_MS = 30 * 60 * 1000;
const MEMBER_TTL_MS = 15 * 1000;
const CODE_ALPHABET = 'ABCDEFGHJKMNPQRSTUVWXYZ23456789';
const CODE_LENGTH = 6;

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const path = url.pathname.replace(/\/+$/, '');
    const parts = path.split('/').filter(Boolean);

    if (request.method === 'OPTIONS') return json({}, 204);

    // 健康检查刻意免令牌：运维一条 curl 就能判断服务是否活着。
    if (parts[0] === 'health') {
      return json({ ok: true, now: Date.now() });
    }

    // 除健康检查外，都需要令牌。地址是公开的（*.workers.dev），没有令牌任何人都能建房间。
    if (!tokenMatches(env, request, url)) {
      return json({ error: 'unauthorized' }, 401);
    }

    // 建房：先生成一个房间码，再由该房间自己的 Durable Object 初始化。
    if (parts.length === 1 && parts[0] === 'room' && request.method === 'POST') {
      const body = await readJson(request);
      if (!body) return json({ error: 'bad_body' }, 400);
      const uid = String(body.uid || '');
      if (!uid) return json({ error: 'uid_required' }, 400);
      // 撞码概率极低；万一撞上（对象里已有房间），换一个重试。
      for (let attempt = 0; attempt < 5; attempt++) {
        const code = freshCode();
        const stub = env.ROOMS.get(env.ROOMS.idFromName(code));
        const res = await stub.fetch('https://room/create', {
          method: 'POST',
          body: JSON.stringify({ code, uid, name: String(body.name || '') }),
        });
        if (res.status !== 409) return res;
      }
      return json({ error: 'no_free_code' }, 503);
    }

    // 其余都是房间内操作，交给该房间的对象处理。
    if (parts.length >= 2 && parts[0] === 'room') {
      const code = String(parts[1] || '').toUpperCase();
      const stub = env.ROOMS.get(env.ROOMS.idFromName(code));
      // The query string must be re-attached explicitly: `new Request(url, init)` takes the URL from
      // the first argument, and `parts` came from `pathname` alone — so building the target from the
      // path alone silently dropped `?exclude=…`, and every member received a peer list containing
      // itself. (Caught by running test_relay.js against `wrangler dev` before deploying.)
      const suffix = parts.slice(2).join('/');
      const forwarded = new Request(
        `https://room/${suffix || 'room'}${url.search}`,
        request,
      );
      return stub.fetch(forwarded);
    }

    return json({ error: 'not_found' }, 404);
  },
};

/**
 * 一个房间。
 *
 * 一个对象只管一个房间，因此内部状态就是"这个房间的成员表"——没有房间注册表，
 * 也就不存在跨房间的锁和键空间。
 */
export class Room {
  constructor(state, env) {
    this.state = state;
    this.env = env;
    this.code = null;
    this.createdAt = 0;
    this.touchedAt = 0;
    this.hostUid = null;
    /** uid -> { uid, name, songId, positionMs, playing, updatedAt, seq, seenAt } */
    this.members = new Map();
  }

  async fetch(request) {
    const url = new URL(request.url);
    const action = url.pathname.replace(/\/+$/, '').split('/').filter(Boolean)[0] || 'room';
    const now = Date.now();

    // 创建：只有空对象才能建，否则说明房间码撞了（Worker 会换一个重试）。
    if (action === 'create' && request.method === 'POST') {
      if (this.code) return json({ error: 'code_taken' }, 409);
      const body = await readJson(request);
      if (!body) return json({ error: 'bad_body' }, 400);
      this.code = String(body.code || '');
      this.createdAt = now;
      this.touchedAt = now;
      this.hostUid = String(body.uid || '');
      this.members.set(this.hostUid, blankMember(this.hostUid, body.name, now));
      return json({ code: this.code, uid: this.hostUid, host: true, members: 1 });
    }

    // 房间是否存在 + 成员名单（加入前先看看）。
    if (action === 'room' && request.method === 'GET') {
      if (!this.code) return json({ error: 'no_room' }, 404);
      this.prune(now);
      return json({
        code: this.code,
        members: [...this.members.values()].map((m) => ({ uid: m.uid, name: m.name })),
        count: this.members.size,
      });
    }

    // 加入（可能还没选歌）。
    if (action === 'join' && request.method === 'POST') {
      if (!this.code) return json({ error: 'no_room' }, 404);
      const body = await readJson(request);
      if (!body) return json({ error: 'bad_body' }, 400);
      const uid = String(body.uid || '');
      if (!uid) return json({ error: 'uid_required' }, 400);
      this.members.set(uid, blankMember(uid, body.name, now));
      this.touchedAt = now;
      return json({ code: this.code, uid, members: this.members.size });
    }

    // 上报自己的状态，同时把对方的状态回给对方——一次往返完成，省一半请求。
    if (action === 'state' && request.method === 'POST') {
      if (!this.code) return json({ error: 'no_room' }, 404);
      const body = await readJson(request);
      if (!body) return json({ error: 'bad_body' }, 400);
      const uid = String(body.uid || '');
      if (!uid) return json({ error: 'uid_required' }, 400);
      const member = blankMember(uid, body.name, now);
      Object.assign(member, normaliseState(body));
      this.members.set(uid, member);
      this.touchedAt = now;
      this.prune(now);
      const peers = [...this.members.values()].filter((m) => m.uid !== uid);
      return json({ code: this.code, peers: peers.map(publicMember) });
    }

    // 只读地取对方状态（App 的轮询路径）。
    if (action === 'state' && request.method === 'GET') {
      if (!this.code) return json({ error: 'no_room' }, 404);
      this.prune(now);
      const exclude = url.searchParams.get('exclude') || '';
      return json({
        code: this.code,
        peers: [...this.members.values()].filter((m) => m.uid !== exclude).map(publicMember),
        count: this.members.size,
      });
    }

    // 离开：清掉自己（不留墓碑——房间是临时的，成员表就是真相）。
    if (action === 'leave' && request.method === 'POST') {
      if (!this.code) return json({ ok: true });
      const body = await readJson(request);
      const uid = String((body || {}).uid || '');
      if (uid) this.members.delete(uid);
      this.touchedAt = now;
      return json({ ok: true, members: this.members.size });
    }

    // 结束房间（房主）。
    if (action === 'room' && request.method === 'DELETE') {
      this.code = null;
      this.members.clear();
      return json({ ok: true });
    }

    return json({ error: 'not_found' }, 404);
  }

  /**
   * 丢掉太久没上报的成员。
   *
   * 按"我多久没听到他"计算（本机时钟），所以两端时钟不需要一致。房间空置太久也一并清掉，
   * 免得对象一直占着。
   */
  prune(now) {
    for (const [uid, m] of this.members) {
      if (now - m.seenAt > MEMBER_TTL_MS) this.members.delete(uid);
    }
    if (this.members.size === 0 && now - this.touchedAt > MEMBER_TTL_MS * 4) {
      this.code = null;
    }
    if (this.code && now - this.touchedAt > ROOM_TTL_MS) {
      this.code = null;
      this.members.clear();
    }
  }
}

// ---------------------------------------------------------------------------- 工具

function blankMember(uid, name, now) {
  return {
    uid,
    name: String(name || '').slice(0, 40),
    songId: 0,
    positionMs: 0,
    playing: false,
    updatedAt: now,
    seq: 0,
    seenAt: now,
  };
}

/** 把请求体规整成客户端读取的那种形状，坏数据不会污染别人。 */
function normaliseState(body) {
  const songId = Number(body.songId);
  const positionMs = Number(body.positionMs);
  const updatedAt = Number(body.updatedAt);
  const seq = Number(body.seq);
  return {
    songId: Number.isFinite(songId) && songId > 0 ? Math.trunc(songId) : 0,
    positionMs: Number.isFinite(positionMs) && positionMs >= 0 ? Math.trunc(positionMs) : 0,
    playing: body.playing === true,
    updatedAt: Number.isFinite(updatedAt) ? Math.trunc(updatedAt) : Date.now(),
    seq: Number.isFinite(seq) ? Math.trunc(seq) : 0,
  };
}

/** 只暴露客户端读的字段——`seenAt` 是服务端的本机判断依据，不必外传。 */
function publicMember(m) {
  return {
    uid: m.uid,
    name: m.name,
    songId: m.songId,
    positionMs: m.positionMs,
    playing: m.playing,
    updatedAt: m.updatedAt,
    seq: m.seq,
  };
}

function freshCode() {
  const bytes = new Uint8Array(CODE_LENGTH);
  crypto.getRandomValues(bytes);
  let out = '';
  for (let i = 0; i < CODE_LENGTH; i++) out += CODE_ALPHABET[bytes[i] % CODE_ALPHABET.length];
  return out;
}

/** 常量时间比较，避免令牌被逐字节试探出来。 */
function tokenMatches(env, request, url) {
  const expected = String(env.RELAY_TOKEN || '');
  if (!expected) return true;
  const supplied = request.headers.get('x-relay-token') || url.searchParams.get('token') || '';
  if (supplied.length !== expected.length) return false;
  let diff = 0;
  for (let i = 0; i < expected.length; i++) {
    diff |= supplied.charCodeAt(i) ^ expected.charCodeAt(i);
  }
  return diff === 0;
}

async function readJson(request) {
  try {
    const text = (await request.text()).trim();
    if (!text) return {};
    const parsed = JSON.parse(text);
    return parsed && typeof parsed === 'object' ? parsed : null;
  } catch {
    return null;
  }
}

function json(payload, status = 200) {
  return new Response(JSON.stringify(payload), {
    status,
    headers: {
      'Content-Type': 'application/json; charset=utf-8',
      'Access-Control-Allow-Origin': ALLOW_ORIGIN,
      'Access-Control-Allow-Headers': 'Content-Type, X-Relay-Token',
      'Access-Control-Allow-Methods': 'GET, POST, DELETE, OPTIONS',
      'Cache-Control': 'no-store',
    },
  });
}
