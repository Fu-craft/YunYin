# 一起听 · 部署到 Cloudflare Workers（免费、常驻、不占用你的电脑）

> 目标：得到一个长期有效、**不依赖任何电脑开机**的地址，两台不同网络的手机都能连上。

## 为什么是这条路

两台不同网络的设备要互相找到，中间**必须有一个双方都能访问、且一直开着的点**。
你的网络实测**封锁了 MQTT 的 1883/8883**（但 443 正常），所以第三方 MQTT broker 走不通；
免费的 HTTP 服务则有每日消息配额。Cloudflare Workers 满足全部条件：
走 443、永久在线、免费额度 10 万请求/天（两人房间按 5 秒心跳约 3.4 万/天，余量充足）。

---

## 方式 A：命令行（推荐，3 条命令）

`wrangler.toml` 已经写好了 Durable Object 的绑定与迁移声明，所以这一步不用在面板里点任何东西：

```bash
cd server/cloudflare
npx wrangler login                     # 浏览器授权一次
npx wrangler deploy                    # 自动创建 Worker、Durable Object 命名空间、绑定
npx wrangler secret put RELAY_TOKEN    # 交互式输入一个令牌（自己定）
```

`deploy` 结束时会打印你的地址，形如：

```
https://yunyin-together.<你的子域>.workers.dev
```

**为什么推荐这条**：Durable Object 需要在部署时声明迁移（`wrangler.toml` 里的
`new_sqlite_classes`）。命令行会自动处理；面板则要**先手动创建命名空间**再绑定（见方式 B 第 4 步），
多了几步且容易点错。

---

## 方式 B：纯浏览器面板

### 1. 注册

<https://dash.cloudflare.com/sign-up>，邮箱注册即可（**不需要绑卡**）。

### 2. 创建 Worker 并粘贴代码

1. **Workers & Pages** → **Create** → **Create Worker**
2. 名字随意（如 `yunyin-together`）→ **Deploy**（先建一个默认的）
3. **Edit code** → **全选删掉**示例代码 → 粘贴本目录 `worker.js` 的**全部内容**
4. **Deploy**

### 3. 先创建 Durable Object 命名空间

> 这一步是我漏掉过的：面板不会替你创建，必须先有命名空间才能绑定。

**Workers & Pages** → **Durable Objects** → **Create** →
- Class name 填 **`Room`**（必须与代码里 `export class Room` 一致）
- 名字随便取，例如 `yunyin-rooms`

### 4. 绑定到 Worker

回到该 Worker → **Settings** → **Bindings**（有的界面叫 Runtime / Variables）
→ **Add** → **Durable Object**：
- **Variable name**：`ROOMS`（必须与代码里 `env.ROOMS` 一致）
- **Durable Object namespace**：选第 3 步建的那个

### 5. 设置访问令牌（重要）

地址是公开的，**没有令牌任何人都能建房**。

同一页 → **Add** → 类型选 **Secret**：
- Name：`RELAY_TOKEN`
- Value：自己定一串

### 6. 重新 Deploy

改动绑定后需要再次 **Deploy** 才生效。

### 7. 自检

浏览器打开：

```
https://yunyin-together.<你的子域>.workers.dev/health
```

看到 `{"ok":true,...}` 就成功了。

---

## 把地址填进 App

在你自己电脑的 `local.properties` 里加两行（这个文件不进仓库）：

```properties
together.base.url=https://yunyin-together.<你的子域>.workers.dev
together.token=你设置的令牌
```

然后重新构建：

```bash
./gradlew :app:assembleDebug
```

**App 代码一行都不用改**——它早就支持自建中继，接口与本地版完全一致。

## 验证部署是否正确

在 `server/` 目录下：

```bash
node test_relay.js https://yunyin-together.<你的子域>.workers.dev 你的令牌
```

全部 `ok` 即部署正确（17 项）。这个测试同时也用于本地中继，所以两种部署跑同一套断言。

## 常见问题

| 现象 | 原因 |
|---|---|
| `/health` 能开，但建房返回 `unauthorized` | 令牌没设，或 App 里的令牌与设置的不一致 |
| 建房 500，或日志提到 Durable Object / `Room` | 第 3–4 步没做：命名空间未创建，或 Variable name 不是 `ROOMS`、Class name 不是 `Room` |
| 两人房间都只有自己 | 两人的 `together.base.url` 不一致（服务不同 → 房间不互通） |
| `wrangler deploy` 报 `class Room is not exported` | `worker.js` 粘贴不完整，或 `main` 指向的文件名不对 |
| 超过免费额度 | 只有极高频使用才会；两人房间远低于 10 万/天 |
