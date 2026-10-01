# 一起听 · 部署到 Cloudflare Workers（免费、常驻、不占用你的电脑）

> 目标：得到一个长期有效、**不依赖任何电脑开机**的地址，两台不同网络的手机都能连上。

## 为什么是这条路

两台不同网络的设备要互相找到，中间**必须有一个双方都能访问、且一直开着的点**。
你的网络实测**封锁了 MQTT 的 1883/8883**（但 443 正常），所以第三方 MQTT broker 走不通；
免费的 HTTP 服务则有每日消息配额。Cloudflare Workers 满足全部条件：
走 443、永久在线、免费额度 10 万请求/天（两人房间按 5 秒心跳约 3.4 万/天，余量充足）。

## 部署（约 5 分钟，浏览器操作即可）

### 1. 注册 Cloudflare（免费）

打开 <https://dash.cloudflare.com/sign-up>，用邮箱注册并验证（不需要绑卡）。

### 2. 创建 Worker 并粘贴代码

1. 左侧 **Workers & Pages** → **Create** → **Create Worker**
2. 名字随便取，例如 `yunyin-together` → **Deploy**（先建一个空的）
3. 点 **Edit code**，把编辑器里的示例代码**全部删掉**，粘贴本目录 `worker.js` 的全部内容
4. 右上角 **Deploy**

### 3. 启用 Durable Objects（房间状态用）

1. 回到该 Worker → **Settings** → **Runtime**（或 Bindings）
2. 找到 **Durable Objects** → **Add binding**
3. **Variable name** 填 `ROOMS`，**Class name** 填 `Room`，保存
   > 名字必须完全一致——代码里就是按这两个名字取的。

### 4. 设置访问令牌（重要）

地址是公开的，**没有令牌任何人都能建房**。

1. 该 Worker → **Settings** → **Variables and Secrets** → **Add**
2. Type 选 **Secret**，Name 填 `RELAY_TOKEN`，Value 自己定一个（例如一串随机字母数字）
3. 保存并 **Deploy**

### 5. 找到你的地址

形如：

```
https://yunyin-together.你的用户名.workers.dev
```

### 6. 自检

浏览器直接打开：

```
https://yunyin-together.你的用户名.workers.dev/health
```

看到 `{"ok":true,...}` 就成功了。

## 把地址填进 App

在你自己电脑的 `local.properties` 里加两行（这个文件不进仓库）：

```properties
together.base.url=https://yunyin-together.你的用户名.workers.dev
together.token=你在第 4 步设置的那串
```

然后重新构建：

```bash
./gradlew :app:assembleDebug
```

**代码一行都不用改**——App 早先就支持自建中继，接口与本地版完全一致。

## 验证部署是否正确

在 `server/` 目录下：

```bash
node test_relay.js https://yunyin-together.你的用户名.workers.dev 你的令牌
```

全部 `ok` 即部署正确（17 项）。

## 命令行部署（可选，等价于上面第 2–4 步）

如果你更习惯命令行：

```bash
cd server/cloudflare
npx wrangler login          # 浏览器授权一次
npx wrangler deploy         # 读取 wrangler.toml，含 Durable Object 绑定
npx wrangler secret put RELAY_TOKEN   # 交互式输入令牌
```

## 常见问题

| 现象 | 原因 |
|---|---|
| `/health` 能开，但建房返回 `unauthorized` | 令牌没设，或 App 里的令牌与第 4 步不一致 |
| 建房返回 500，日志提到 Durable Object | 第 3 步的绑定没加，或名字不是 `ROOMS` / `Room` |
| 两人房间都只有自己 | 两人的 `together.base.url` 不一致（服务不同 → 房间不互通） |
| 超过免费额度 | 只有极高频使用才会；两人房间远低于 10 万/天 |
