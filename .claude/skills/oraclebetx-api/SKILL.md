# OracleBetX 前端 API 接口规范

> 本文档是 OracleBetX 平台前端接口的唯一权威来源。
> 包含所有 REST API 和 WebSocket 推送的完整定义。

---

## 使用说明

本文件是一个 AI Skill，供前端开发者配合 Claude Code 使用。AI 读取本文件后，可以直接根据接口定义生成正确的前端调用代码。

### 方式一：放入前端项目（推荐）

将本文件复制到前端项目的 `.claude/` 目录下：

```bash
# 在前端项目根目录执行
mkdir -p .claude
cp /path/to/OracleBetx-Skill/oraclebetx-api-skill/SKILL.md .claude/oraclebetx-api.md
```

然后在前端项目根目录的 `CLAUDE.md` 中添加引用：

```markdown
## 后端接口
所有后端 API 接口定义见 .claude/oraclebetx-api.md，编写接口调用代码时请严格参照该文件。
```

这样用 Claude Code 打开前端项目时，AI 会自动加载接口规范，帮你生成正确的 API 调用代码。

### 方式二：注册为 Claude Code Skill

```bash
# 在前端项目根目录执行
mkdir -p .claude/skills/oraclebetx-api
cp /path/to/OracleBetx-Skill/oraclebetx-api-skill/SKILL.md .claude/skills/oraclebetx-api/SKILL.md
```

注册后可通过 `/oraclebetx-api` 命令手动调用。

### 方式三：全局共享（多个前端项目共用）

```bash
mkdir -p ~/.claude/skills/oraclebetx-api
cp /path/to/SKILL.md ~/.claude/skills/oraclebetx-api/SKILL.md
```

所有项目的 Claude Code 都能访问。

### 使用效果

配置完成后，你可以直接对 Claude Code 说：

- "帮我写一个登录页面，调用登录接口"
- "写一个赛事列表组件，用 live-v2 接口"
- "封装一个下单函数，包含轮询执行状态"
- "写一个 WebSocket hook，订阅报价和订单簿"

AI 会自动参照本文件中的接口定义、请求参数、响应类型和示例代码，生成准确的前端代码。

---

## 架构概览

OracleBetX 是体育竞猜/交易平台。前端**只与 TradeGateway（统一网关）通信**，所有请求走同一个网关地址，由网关转发到后端各服务。

```
                    ┌─── Auth (18084)         认证服务
                    ├─── AccountEngine (18080) 账务引擎
前端 ──→ Gateway (18090) ──┤─── MatchEngine (18081)   撮合引擎
                    ├─── Quote (18083)        行情服务
                    ├─── BetData (9090)       赛事数据
                    ├─── LPBot (10020)        做市机器人
                    └─── Portal (19080)       LP 运营
```

所有服务通过 **Nacos** 统一管理：
- **Nacos Discovery**：节点注册 + 健康检查（自动发现实例 IP:Port）
- **Nacos Config**：公共配置（GLOBAL_COMMON）+ 节点配置（NODE_CONFIG）+ 路由规则（GATEWAY）

### 网关路由说明

- **网关地址**：`http://{host}:18090`（端口可通过 `server.port` 配置）
- **路由实现**：`GatewayProxyFilter`（Servlet Filter）从 Nacos Config（`gateway-routes.json`，GATEWAY 组）动态匹配路由
- **路由发现**：每条路由指定 `service` 字段，网关从 Nacos Discovery 查找实例地址
- **路径不改写**：网关转发时不修改请求路径
- **本地路由**：`local=true` 的路由放行给 Spring MVC 本地 Controller（如 `/tradegateway/**`、`/api/events`）
- **WebSocket 代理**：`protocol=ws` 的路由由 `GatewayWebSocketProxyHandler` 动态代理

### 后端服务端口（默认值）

| 服务 | 端口 | Nacos 服务名 | 职责 |
|------|------|-------------|------|
| Gateway (Edge) | 18090 | node-discovery-trade-gateway | 统一入口网关 + 交易逻辑 |
| AccountEngine | 18080 | node-discovery-account-engine | 账户引擎（账本/余额/订单） |
| MatchEngine | 18081 | node-discovery-match-engine | 撮合引擎（订单撮合/成交） |
| Quote | 18083 | node-discovery-quote | 行情服务（K 线/推送） |
| Auth | 18084 | node-discovery-auth-node | 用户认证（登录/注册） |
| BetData | 9090 | node-discovery-betdata-job | 赛事数据（Polymarket 同步/管理） |
| LPBot | 10020 | node-discovery-lp | 做市机器人（Bot 管理/库存/下单） |
| Portal | 19080 | — | LP 运营（初始化/结算/订单查询） |

### 网关路由表（gateway-routes.json）

| 路径 | 目标服务 | 协议 | 鉴权 | 说明 |
|------|---------|------|------|------|
| `/api/users/accessToken` | auth-node | HTTP | 否 | 用户登录 |
| `/api/users` | auth-node | HTTP | 否 | 用户注册 |
| `/api/users/self` | auth-node | HTTP | 需登录 | 获取用户信息 |
| `/api/accounts/**` | account-engine | HTTP | 需登录 | 账户查询 |
| `/api/orders/**` | account-engine | HTTP | 需登录 | 订单查询 |
| `/api/account/**` | account-engine | HTTP | 否 | 账务引擎内部 API |
| `/api/lp/**` | portal | HTTP | 否 | LP 运营 |
| `/api/line/**` | quote | HTTP | 否 | K 线数据 |
| `/api/events` | 本地 | HTTP | 否 | 赛事列表（local=true） |
| `/data/polymarket/**` | betdata-job | HTTP | 否 | Polymarket 数据管理 |
| `/data/events/**` | betdata-job | HTTP | 否 | 事件数据管理 |
| `/debug/polymarket/**` | betdata-job | HTTP | 否 | Polymarket 调试 |
| `/admin/bots/**` | lp-bot | HTTP | 否 | Bot 管理 |
| `/admin/trading/**` | account-engine | HTTP | 否 | 交易管理 |
| `/tradegateway/**` | 本地 | HTTP | 否 | 交易网关（local=true） |
| `/ws` | quote | WS | 否 | 实时行情推送 |

路由管理 API：
- `GET /admin/gateway/routes` — 查看所有路由
- `POST /admin/gateway/routes` — 新增路由
- `PUT /admin/gateway/routes/{id}` — 更新路由
- `DELETE /admin/gateway/routes/{id}` — 删除路由
- `POST /admin/gateway/routes/reload` — 强制刷新
- `GET /admin/gateway/routes/instances/{service}` — 查看服务实例（自动订阅）

Nacos 配置管理 API：
- `GET /admin/nacos/node-types` — 节点类型定义
- `GET /admin/nacos/node-configs` — 所有节点配置
- `GET /admin/nacos/global-config-index` — 公共配置索引

## 基础配置

```typescript
// 前端只需配置网关地址，所有请求都通过 TradeGateway 转发
const BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL || 'http://localhost:18090';
```

## 认证方式

- 登录接口返回 `token` 字符串
- 所有需要认证的接口必须携带请求头：`Authorization: Bearer <token>`
- WebSocket 连接通过 `accessToken` 查询参数传递令牌
- 收到 401 响应时，跳转到登录页

---

## 统一响应封装

大部分接口返回统一的 `ApiResponse<T>` 封装（Quote 行情接口例外，返回裸数据）。

```typescript
interface ApiResponse<T> {
  ok: boolean;                  // 请求是否成功
  meta: {
    requestId: string;          // 请求唯一 ID
    traceId: string;            // 链路追踪 ID
    operationId: string;        // 操作 ID
    idempotencyKey?: string;    // 幂等键（可选）
    serverTs: number;           // 服务端时间戳（毫秒）
    latencyMs: number;          // 处理耗时（毫秒）
    version: string;            // API 版本
    extra?: Record<string, unknown>;
  };
  data: T | null;               // 业务数据（成功时有值）
  error: {
    code: string;               // 错误码
    message: string;            // 错误信息
    httpStatus: number;         // HTTP 状态码
    retryable: boolean;         // 是否可重试
    details?: Record<string, unknown>;
  } | null;                     // 错误信息（失败时有值）
}

// 分页数据结构
interface PagedList<T> {
  items: T[];                   // 当前页数据列表
  count: number;                // 总数
}
```

---

## REST API 接口列表

### 一、用户认证模块

#### POST /api/users/accessToken — 用户登录

无需认证。

```typescript
// 请求体
interface SignInRequest {
  email: string;      // 必填，邮箱
  password: string;   // 必填，密码
  code?: string;      // 选填，两步验证码
}

// 响应：ApiResponse<TokenDto>
interface TokenDto {
  token: string;                    // 访问令牌，用于后续请求认证
  twoStepVerification?: string;     // 两步验证类型（未开启则为 null）
}
```

**调用示例：**
```typescript
const res = await fetch(`${BASE_URL}/api/users/accessToken`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ email: 'user@example.com', password: 'pass123' }),
});
const { data } = await res.json(); // data.token 即为访问令牌
```

---

#### POST /api/users — 用户注册

无需认证。

```typescript
// 请求体
interface SignUpRequest {
  email: string;      // 必填，有效邮箱格式
  password: string;   // 必填，密码
  code?: string;      // 选填，邀请码
}

// 响应：ApiResponse<UserDto>
interface UserDto {
  id: string;                          // 用户 ID
  email: string;                       // 邮箱
  name: string | null;                 // 昵称
  profilePhoto: string | null;         // 头像 URL
  isBand: boolean;                     // 是否被封禁
  createdAt: string | null;            // 注册时间
  twoStepVerificationType: string | null;  // 两步验证类型
}
```

---

#### GET /api/users/self — 获取当前用户信息

**需要认证。**

```typescript
// 响应：ApiResponse<UserDto>
// UserDto 结构同上
```

**调用示例：**
```typescript
const res = await fetch(`${BASE_URL}/api/users/self`, {
  headers: { 'Authorization': `Bearer ${token}` },
});
const { data: user } = await res.json(); // user.id, user.email
```

---

### 二、赛事模块

#### GET /api/events/live — 获取实时赛事列表（v1，旧版）

无需认证。

```typescript
// 响应：ApiResponse<ProductEntity[]>
// ProductEntity 为旧版格式，推荐使用 v2 接口
```

---

#### GET /api/events/live-v2 — 获取实时赛事列表（v2，推荐）

无需认证。

| 查询参数 | 类型   | 默认值 | 说明             |
|---------|--------|--------|-----------------|
| limit   | number | 20     | 返回赛事最大数量   |

```typescript
// 响应：ApiResponse<ProductV2Dto[]>

interface ProductV2Dto {
  id: string;                   // 产品唯一 ID
  productRootKey: string;       // 产品根键
  displayName: string;          // 显示名称，如 "湖人 vs 凯尔特人"
  eventId: string;              // 赛事 ID
  productId: string;            // 产品 ID
  productType: string;          // 产品类型
  markets: MarketV2Dto[];       // 市场列表
}

interface MarketV2Dto {
  id: string;                   // 市场唯一 ID
  marketKey: string;            // 市场键
  displayName: string;          // 显示名称，如 "胜负盘"
  marketId: string;             // 市场 ID
  outcomes: OutcomeV2Dto[];     // 结果选项列表
}

interface OutcomeV2Dto {
  id: string;                   // 选项唯一 ID
  instrumentCode: string;       // 合约代码
  displayName: string;          // 显示名称，如 "湖人"、"凯尔特人"
  selectionId: string;          // 选项 ID
}
```

**调用示例：**
```typescript
const res = await fetch(`${BASE_URL}/api/events/live-v2?limit=20`);
const { data: events } = await res.json();
// events[0].displayName — 赛事名称
// events[0].markets[0].outcomes[0].displayName — 选项名称
```

---

### 三、交易模块

#### POST /tradegateway/orders — 下单

**需要认证。**

```typescript
// 请求体
interface PlaceOrderRequest {
  clientOid?: string;           // 客户端订单 ID（用于幂等性去重）
  productId: string;            // 必填，产品/合约 ID
  side: 'BUY' | 'SELL' | 'BACK' | 'LAY';  // 必填，方向
  type: 'LIMIT' | 'MARKET';    // 必填，订单类型（限价/市价）
  size: string;                 // 必填，数量（十进制字符串）
  funds?: string;               // 市价单时的最大花费金额
  price?: string;               // 限价单必填，价格（十进制字符串）
  timeInForce?: 'GTC' | 'GTT' | 'IOC' | 'FOK';  // 有效期策略，默认 GTC
  eventId?: string;             // 赛事 ID
  marketId?: string;            // 市场 ID
  outcomeId?: string;           // 结果选项 ID
  source?: string;              // 客户端标识
  ext?: string;                 // 扩展数据
}

// 响应：ApiResponse<OrderDto>
interface OrderDto {
  id: string;                   // 订单 ID
  price: string;                // 价格
  size: string;                 // 数量
  funds: string | null;         // 金额
  productId: string;            // 产品 ID
  side: string;                 // 方向：BUY, SELL, BACK, LAY
  type: string;                 // 类型：LIMIT, MARKET
  createdAt: string;            // 创建时间（ISO 格式）
  fillFees: string | null;      // 手续费
  filledSize: string | null;    // 已成交数量
  executedValue: string | null; // 已成交金额
  status: string;               // 状态：PENDING, OPEN, FILLED, CANCELLED, REJECTED
  eventId: string | null;       // 赛事 ID
  marketId: string | null;      // 市场 ID
  outcomeId: string | null;     // 选项 ID
  marketType: string | null;    // 市场类型
  outcomeName: string | null;   // 选项名称
  sportName: string | null;     // 运动类型名称
  eventName: string | null;     // 赛事名称
  tournamentName: string | null;// 锦标赛名称
  odds: string | null;          // 赔率
  productType: string | null;   // 产品类型
  userId: string;               // 用户 ID
  clientOid: string | null;     // 客户端订单 ID
}
```

**调用示例：**
```typescript
const res = await fetch(`${BASE_URL}/tradegateway/orders`, {
  method: 'POST',
  headers: {
    'Content-Type': 'application/json',
    'Authorization': `Bearer ${token}`,
  },
  body: JSON.stringify({
    productId: 'prod-123',
    side: 'BUY',
    type: 'LIMIT',
    size: '10',
    price: '1.85',
    eventId: 'evt-456',
    marketId: 'mkt-789',
    outcomeId: 'out-001',
  }),
});
const { data: order } = await res.json(); // order.id, order.status
```

---

#### DELETE /tradegateway/orders/{orderId} — 撤单

**需要认证。**

```typescript
// 路径参数：orderId（字符串）

// 响应：ApiResponse<CancelOrderAcceptedData>
interface CancelOrderAcceptedData {
  accepted: boolean;            // 是否被接受
  orderId: string;              // 订单 ID
  productId: string | null;     // 产品 ID
}
```

**调用示例：**
```typescript
const res = await fetch(`${BASE_URL}/tradegateway/orders/${orderId}`, {
  method: 'DELETE',
  headers: { 'Authorization': `Bearer ${token}` },
});
const { data } = await res.json(); // data.accepted
```

---

#### GET /tradegateway/orders — 查询订单列表

**需要认证。**

| 查询参数     | 类型   | 默认值 | 说明                              |
|-------------|--------|--------|----------------------------------|
| eventId     | string | -      | 按赛事筛选                         |
| marketId    | string | -      | 按市场筛选                         |
| selectionId | string | -      | 按选项筛选                         |
| status      | string | -      | 按状态筛选：PENDING, OPEN, FILLED, CANCELLED |
| page        | number | 0      | 页码（从 0 开始）                   |
| pageSize    | number | 50     | 每页数量                           |

```typescript
// 响应：ApiResponse<PagedList<OrderDto>>
// OrderDto 结构同上
```

**调用示例：**
```typescript
const params = new URLSearchParams({ status: 'OPEN', page: '0', pageSize: '20' });
const res = await fetch(`${BASE_URL}/tradegateway/orders?${params}`, {
  headers: { 'Authorization': `Bearer ${token}` },
});
const { data } = await res.json(); // data.items[] 订单列表, data.count 总数
```

---

#### GET /tradegateway/accounts — 查询账户余额

**需要认证。**

| 查询参数   | 类型   | 必填 | 说明                  |
|-----------|--------|------|----------------------|
| currency  | string | 是   | 币种，如 "USDT", "USD" |

```typescript
// 响应：ApiResponse<AccountDto[]>
interface AccountDto {
  id: string;                   // 账户 ID
  currency: string;             // 币种
  currencyIcon: string | null;  // 币种图标
  available: string;            // 可用余额（十进制字符串）
  hold: string;                 // 冻结金额（十进制字符串）
}
```

---

#### GET /tradegateway/acceptances — 查询指令接受状态

**需要认证。** 用于轮询下单/撤单指令是否被系统接受。

| 查询参数    | 类型   | 说明                    |
|------------|--------|------------------------|
| commandId  | string | 指令 ID（二选一）         |
| requestId  | string | 请求 ID（二选一）         |

提供 `commandId` 或 `requestId` 其中之一即可。

```typescript
// 响应：ApiResponse<GatewayAcceptanceView>
interface GatewayAcceptanceView {
  status: string;               // 接受状态
  opType: string;               // 操作类型：PLACE（下单）, CANCEL（撤单）
  requestId: string;            // 请求 ID
  traceId: string;              // 链路 ID
  tenantId: string;             // 租户 ID
  userId: string;               // 用户 ID
  accountEngineId: string;      // 账户引擎节点 ID
  matchEngineId: string;        // 撮合引擎节点 ID
  routingTableVersion: string;  // 路由表版本
  accountShardKey: string;      // 账户分片键
  matchShardKey: string;        // 撮合分片键
  pairPolicy: string;           // 配对策略
  commandId: string;            // 指令 ID
  acceptedAt: number;           // 接受时间（毫秒时间戳）
  appendedAt: number;           // 追加时间（毫秒时间戳）
}
```

---

#### GET /tradegateway/executions — 查询指令执行结果

**需要认证。** 用于轮询下单/撤单指令的执行进度和结果。

| 查询参数    | 类型   | 说明                    |
|------------|--------|------------------------|
| commandId  | string | 指令 ID（二选一）         |
| requestId  | string | 请求 ID（二选一）         |

```typescript
// 响应：ApiResponse<TradeGatewayExecutionStateView>
interface TradeGatewayExecutionStateView {
  commandId: string;            // 指令 ID
  requestId: string;            // 请求 ID
  traceId: string;              // 链路 ID
  userId: string;               // 用户 ID
  status: string;               // 执行状态：PENDING, EXECUTING, COMPLETED, FAILED, COMPENSATED
  retryCount: number;           // 重试次数
  nextRetryAt: number;          // 下次重试时间（毫秒时间戳）
  accountEngineId: string;      // 账户引擎节点 ID
  matchEngineId: string;        // 撮合引擎节点 ID
  errorCode: string | null;     // 错误码（失败时有值）
  errorMessage: string | null;  // 错误信息（失败时有值）
  projectionState: string | null; // 投影状态
  acceptedAt: number | null;    // 接受时间（毫秒时间戳）
  updatedAt: number | null;     // 更新时间（毫秒时间戳）
}
```

---

### 四、行情模块

> **注意：** 行情接口返回**裸数据**，不使用 `ApiResponse` 封装。

#### GET /api/line/1m — 获取 1 分钟 K 线数据

无需认证。

| 查询参数         | 类型    | 必填 | 说明                    |
|-----------------|---------|------|------------------------|
| sid             | string  | 是   | 合约/产品 ID             |
| from            | number  | 是   | 起始时间（毫秒时间戳）      |
| to              | number  | 是   | 结束时间（毫秒时间戳）      |
| includeCurrent  | boolean | 否   | 是否包含当前未完结的 K 线   |

```typescript
// 响应：LinePoint1m[]（原始数组，无 ApiResponse 封装！）
interface LinePoint1m {
  sid: string;                  // 合约 ID
  minuteStart: number;          // 分钟起始时间（毫秒时间戳）
  close: string;                // 收盘价（十进制字符串，BigDecimal）
  eventId: string;              // 赛事 ID
}
```

**调用示例：**
```typescript
const params = new URLSearchParams({
  sid: 'instrument-123',
  from: String(Date.now() - 3600000), // 1 小时前
  to: String(Date.now()),
  includeCurrent: 'true',
});
const res = await fetch(`${BASE_URL}/api/line/1m?${params}`);
const points: LinePoint1m[] = await res.json(); // 直接是数组！
```

---

#### POST /api/replay/start — 启动行情回放

无需认证。

```typescript
// 请求体
interface ReplayRequest {
  sid: string;          // 合约 ID
  from: number;         // 起始时间（毫秒时间戳）
  to: number;           // 结束时间（毫秒时间戳）
  speed: number;        // 回放速度倍数（如 1.0, 2.0, 5.0）
}

// 响应：string（原始字符串，无 ApiResponse 封装！）
```

---

### 五、赛事运营模块 (BetData)

> 前端通过 `/proxy/api/data/*` 代理访问，网关转发到 BetData 服务 (9090)。

#### 事件管理

```
GET  /data/polymarket/manage/events?page=1&size=15&keyword=&matchStatus=
POST /data/polymarket/manage/events/{eventId}/enable     ?operator=admin&reason=
POST /data/polymarket/manage/events/{eventId}/disable    ?operator=admin&reason=
POST /data/polymarket/manage/events/{eventId}/assign-match   ?operator=admin&reason=
POST /data/polymarket/manage/events/{eventId}/unassign-match ?operator=admin&reason=
POST /data/polymarket/manage/backfill/manage-defaults    ?operator=admin&reason=
```

#### 市场管理

```
GET  /data/polymarket/manage/events/{eventId}/markets?page=1&size=20
POST /data/polymarket/manage/events/{eventId}/markets/{marketId}/enable   ?operator=admin&reason=
POST /data/polymarket/manage/events/{eventId}/markets/{marketId}/disable  ?operator=admin&reason=
GET  /data/polymarket/manage/markets?page=1&size=20&keyword=&eventId=
```

#### 产品绑定

```
GET  /data/polymarket/manage/products?page=1&size=20
POST /data/polymarket/manage/products/{eventId}/bind-engine    Body: { engineId, operator, reason }
POST /data/polymarket/manage/products/{eventId}/unbind-engine  Body: { operator, reason }
POST /data/polymarket/manage/products/{eventId}/bind-lp        Body: { lpNodeId, operator, reason }
POST /data/polymarket/manage/products/{eventId}/unbind-lp      Body: { operator, reason }
```

#### 数据同步

```
POST /data/polymarket/sync          ?pageSize=100&offset=0&pages=1&closed=false
POST /data/polymarket/sync-active   ?limit=500&offset=0&forceFull=false
POST /data/events/refresh
```

#### 价格 WebSocket 管理

```
GET  /data/polymarket/ws/market/state
GET  /data/polymarket/ws/market/latest?assetId=xxx
GET  /data/polymarket/ws/market/events?limit=50
POST /data/polymarket/ws/market/connect       Body: { assetIds: [...] }
POST /data/polymarket/ws/market/subscribe     Body: { assetIds: [...] }
POST /data/polymarket/ws/market/unsubscribe   Body: { assetIds: [...] }
POST /data/polymarket/ws/market/stop
```

#### 价格协调

```
GET  /data/polymarket/ws/coord/tokens?limit=100
POST /data/polymarket/ws/coord/price/refresh       ?maxTokens=&tokensPerConn=&maxWorkers=
POST /data/polymarket/ws/coord/price/reconcile     ?maxScanTokens=&sampleSize=
GET  /data/polymarket/ws/coord/external-markets    ?limit=100
POST /data/polymarket/ws/coord/external-markets/register    Body: { marketIds, source, operator, reason }
POST /data/polymarket/ws/coord/external-markets/unregister  Body: { marketIds, source, operator, reason }
```

---

### 六、做市管理模块 (LPBot)

> 前端通过 `/proxy/lpbot/admin/bots/*` 代理访问，网关转发到 LPBot 服务 (10020)。

```
POST /admin/bots/login?eventId=xxx&marketId=xxx&selectionId=xxx    — Bot 创建+登录+缓存 token
GET  /admin/bots?page=1&size=20&status=                            — Bot 账户列表
POST /admin/bots/{sid}/toggle?enabled=true                         — 启用/禁用 Bot
GET  /admin/bots/lp-bindings?eventId=xxx&nodeId=xxx                — LP 节点绑定列表
GET  /admin/bots/inventory?page=1&size=20                          — 库存列表
GET  /admin/bots/orders?page=1&size=20&userId=&status=             — 订单列表
```

---

### 七、LP 运营模块 (Portal)

> 前端通过 `/proxy/portal/api/lp/*` 代理访问，网关转发到 Portal 服务 (19080)。

#### LP 用户管理

```
POST /api/lp/user/create?eventId=xxx&marketId=xxx&outcomeId=xxx   — 创建 LP 机器人用户
GET  /api/lp/user/check?eventId=xxx&marketId=xxx&outcomeId=xxx    — 检查 LP 用户是否存在
```

#### LP 初始化

```
POST /api/lp/init          Body: LpInitRequest            — LP 初始化（注资+冻结+扣款）
POST /api/lp/init-batch    Body: LpBatchInitRequest       — 批量初始化
POST /api/lp/fix-positions ?eventId=xxx                   — 补充持仓
POST /api/lp/fix-bindings  ?eventId=xxx                   — 修复绑定
GET  /api/lp/bindings      ?eventId=xxx                   — 查看绑定数据
```

#### 结算

```
POST /api/settlement/prepare-settle  Body: PrepareSettleMarketRequest  — 预结算
POST /api/settlement/settle          Body: SettleMarketRequest         — 执行结算
```

#### 订单查询

```
GET /admin/orders?page=1&pageSize=20&userId=&status=&eventId=     — 管理后台订单列表
```

---

### 八、网关管理模块 (Edge 本地)

> 前端通过 `/proxy/api/admin/*` 代理访问。

#### 路由管理

```
GET    /admin/gateway/routes                          — 路由列表
GET    /admin/gateway/routes/{id}                     — 路由详情
POST   /admin/gateway/routes          Body: route     — 创建路由
PUT    /admin/gateway/routes/{id}     Body: route     — 更新路由
DELETE /admin/gateway/routes/{id}                     — 删除路由
POST   /admin/gateway/routes/reload                   — 刷新路由
GET    /admin/gateway/routes/instances/{service}       — 服务实例列表
```

#### Nacos 配置管理

```
GET /admin/nacos/global-config-index                  — 全局配置索引
GET /admin/nacos/global-configs                       — 全局配置列表
GET /admin/nacos/global-configs/{name}                — 获取配置
PUT /admin/nacos/global-configs/{name}  Body: config  — 更新配置
GET /admin/nacos/node-types                           — 节点类型列表
PUT /admin/nacos/node-types/{type}      Body: config  — 更新节点类型
GET /admin/nacos/node-configs                         — 节点配置列表
GET /admin/nacos/node-configs/{nodeId}                — 获取节点配置
POST/PUT/DELETE /admin/nacos/node-configs/{nodeId}    — 节点配置 CRUD
```

#### 引擎管理

```
GET  /tradegateway/admin/engines?domain=              — 引擎列表
POST /tradegateway/admin/engines/health     Body: { engineId, healthy }  — 设置健康状态
POST /tradegateway/admin/engines/enabled    Body: { engineId, enabled }  — 启用/禁用
POST /tradegateway/admin/engines/weight     Body: { engineId, weight }   — 设置权重
POST /tradegateway/admin/engines/mode       Body: { engineId, mode }     — 设置模式
```

#### 运维控制

```
GET  /tradegateway/admin/ops/controls                 — 当前运维控制状态
POST /tradegateway/admin/ops/controls/modes           — 设置运行模式
POST /tradegateway/admin/ops/controls/limits          — 设置限流
POST /tradegateway/admin/ops/controls/markets         — 设置市场控制
POST /tradegateway/admin/ops/controls/users           — 设置用户控制
GET  /tradegateway/admin/ops/circuit-breaker          — 熔断器状态
POST /tradegateway/admin/ops/circuit-breaker/open     — 开启熔断
POST /tradegateway/admin/ops/circuit-breaker/close    — 关闭熔断
```

#### 恢复与审计

```
POST /tradegateway/admin/recovery/run                 — 执行恢复
POST /tradegateway/admin/recovery/replay              — 重放 WAL
POST /tradegateway/admin/recovery/retry               — 重试失败命令
POST /tradegateway/admin/recovery/compensate          — 补偿
POST /tradegateway/admin/recovery/skip                — 跳过命令
GET  /tradegateway/admin/recovery/dead-letters        — 死信列表
GET  /tradegateway/wal/audit?commandId=&requestId=    — WAL 审计
```

### 九、AccountEngine 交易管理

> 前端通过 `/proxy/api/admin/trading/*` 代理访问，网关转发到 AccountEngine (18080)。

#### 交易投影

```
GET  /admin/trading/projections                              — 待处理事件列表
GET  /admin/trading/projections/state/event/{eventId}        — 事件投影状态
GET  /admin/trading/projections/state/command/{commandId}    — 命令投影状态
GET  /admin/trading/projections/{eventId}                    — 事件待投影列表
POST /admin/trading/projections/{eventId}/retry              — 重试投影
GET  /admin/trading/projections/dead-letters                 — 死信投影
```

#### 对账与补偿

```
POST /admin/trading/reconciliation/redis-db/run              — 执行 Redis-DB 对账
GET  /admin/trading/reconciliation/redis-db/last             — 最近一次对账结果
POST /admin/trading/reconciliation/kafka-db/run              — 执行 Kafka-DB 对账
GET  /admin/trading/reconciliation/kafka-db/last             — 最近一次对账结果
POST /admin/trading/compensation/run                         — 执行补偿
GET  /admin/trading/compensation/jobs                        — 补偿任务列表
```

#### 恢复控制台

```
GET  /admin/trading/recovery/console/dashboard               — 恢复仪表盘
POST /admin/trading/recovery/console/run                     — 执行恢复
GET  /admin/trading/recovery/console/failures                — 失败列表
GET  /admin/trading/recovery/console/consistency/sample       — 一致性抽样
GET  /admin/trading/recovery/wal/cursor                      — WAL 游标位置
POST /admin/trading/recovery/wal/replay                      — WAL 重放
```

---

## 常用前端模式

### 错误处理

```typescript
async function apiCall<T>(url: string, options?: RequestInit): Promise<T> {
  const res = await fetch(url, options);
  const json = await res.json();

  // ApiResponse 封装的接口
  if ('ok' in json && !json.ok) {
    throw new Error(json.error?.message || '接口错误');
  }

  return json;
}
```

### 下单完整流程

1. `POST /tradegateway/orders` — 提交下单，从响应 meta 中获取 `commandId`
2. `GET /tradegateway/acceptances?commandId=X` — 轮询直到状态不再是 PENDING
3. `GET /tradegateway/executions?commandId=X` — 轮询直到状态为 COMPLETED 或 FAILED
4. `GET /tradegateway/orders?status=OPEN` — 刷新订单列表

### 金额数据类型说明

所有金额相关字段（`price`, `size`, `available`, `hold`, `close` 等）都是**十进制字符串**而非数字类型。前端必须使用精度库（如 `decimal.js` 或 `big.js`）进行运算，避免浮点精度丢失。

---

## WebSocket 实时推送

平台使用 **WebSocket** 进行所有实时数据推送。无 SSE、无 STOMP。

### 连接方式

```
ws://{BASE_URL}/ws?accessToken={token}
```

- 路径：`/ws`（通过 Edge Gateway 代理）
- 认证：通过 `accessToken` 查询参数或 Cookie 传递
- 协议：JSON 文本消息
- 心跳：客户端发 `ping`，服务端回 `pong`

### 客户端发送消息

#### 订阅频道

```json
{
  "type": "subscribe",
  "productIds": ["prod-123", "prod-456"],
  "currencyIds": ["USDT"],
  "channels": ["ticker", "level2", "order", "funds"]
}
```

#### 取消订阅

```json
{
  "type": "unsubscribe",
  "productIds": ["prod-123"],
  "channels": ["ticker"]
}
```

#### 心跳保活

```json
{ "type": "ping" }
```

服务端回复：
```json
{ "type": "pong" }
```

### 频道列表

| 频道 | 作用范围 | 需要认证 | 说明 |
|------|---------|---------|------|
| `ticker` | 按 productId | 否 | 实时报价（最新价、24h 统计） |
| `level2` | 按 productId | 否 | 订单簿深度数据（买卖盘） |
| `match` | 按 productId | 否 | 成交推送 |
| `candle_{秒数}` | 按 productId | 否 | K 线推送（如 `candle_60` = 1分钟线） |
| `order` | 按 userId+productId | 是 | 用户订单状态变更 |
| `funds` | 按 userId+currency | 是 | 账户余额变动 |
| `ws:event:live` | 全局广播 | 否 | 赛事上下线状态更新 |
| `ws:event:state` | 按 eventId | 否 | 赛事价格/状态变化 |
| `ws:event:level2` | 按 productId | 否 | 赛事级别订单簿 |
| `ws:line` | 按 sid | 否 | 1 分钟线实时更新 |
| `ws:system:message` | 全局广播 | 否 | 系统消息（结算通知、公告、风控提醒） |

### 服务端推送消息类型

#### ticker — 实时报价

```typescript
interface TickerMessage {
  type: 'ticker';
  productId: string;        // 产品 ID
  price: string;            // 最新成交价
  side: string;             // 成交方向
  lastSize: string;         // 最新成交量
  open24h: string;          // 24h 开盘价
  close24h: string;         // 24h 收盘价
  high24h: string;          // 24h 最高价
  low24h: string;           // 24h 最低价
  volume24h: string;        // 24h 成交量
  volume30d: string;        // 30d 成交量
  tradeId: string;          // 成交 ID
  sequence: number;         // 序列号
  time: string;             // 时间
}
```

#### l2update — 订单簿增量更新

```typescript
interface L2UpdateMessage {
  type: 'l2update';
  productId: string;        // 产品 ID
  time: string;             // 时间
  changes: [string, string, string][];  // [方向, 价格, 数量]
}
```

#### snapshot — 订单簿全量快照（首次订阅时推送）

```typescript
interface L2SnapshotMessage {
  type: 'snapshot';
  productId: string;        // 产品 ID
  bids: [string, string][]; // 买盘 [价格, 数量]
  asks: [string, string][]; // 卖盘 [价格, 数量]
}
```

#### match — 成交推送

```typescript
interface MatchMessage {
  type: 'match';
  productId: string;        // 产品 ID
  price: string;            // 成交价格
  size: string;             // 成交数量
  side: string;             // 方向
  takerOrderId: string;     // Taker 订单 ID
  makerOrderId: string;     // Maker 订单 ID
  tradeId: string;          // 成交 ID
  sequence: number;         // 序列号
  time: string;             // 时间
}
```

#### order — 用户订单状态变更

```typescript
interface OrderFeedMessage {
  type: 'order';
  userId: string;           // 用户 ID
  productId: string;        // 产品 ID
  id: string;               // 订单 ID
  price: string;            // 价格
  size: string;             // 数量
  funds: string;            // 金额
  side: string;             // 方向
  orderType: string;        // 订单类型
  status: string;           // 状态：PENDING, OPEN, FILLED, CANCELLED
  filledSize: string;       // 已成交数量
  executedValue: string;    // 已成交金额
}
```

#### funds — 账户余额变动

```typescript
interface FundsFeedMessage {
  type: 'funds';
  userId: string;           // 用户 ID
  currencyCode: string;     // 币种代码
  available: string;        // 可用余额
  hold: string;             // 冻结金额
}
```

#### candle — K 线推送

```typescript
interface CandleFeedMessage {
  type: 'candle';
  productId: string;        // 产品 ID
  granularity: number;      // 粒度（秒），60 = 1分钟, 300 = 5分钟
  time: string;             // 时间
  open: string;             // 开盘价
  close: string;            // 收盘价
  high: string;             // 最高价
  low: string;              // 最低价
  volume: string;           // 成交量
}
```

### WebSocket 前端使用完整示例

```typescript
const ws = new WebSocket(`ws://${location.host}/ws?accessToken=${token}`);

ws.onopen = () => {
  // 订阅某个产品的报价和订单簿
  ws.send(JSON.stringify({
    type: 'subscribe',
    productIds: ['prod-123'],
    channels: ['ticker', 'level2'],
  }));

  // 订阅用户的订单变更和余额变动
  ws.send(JSON.stringify({
    type: 'subscribe',
    productIds: ['prod-123'],
    currencyIds: ['USDT'],
    channels: ['order', 'funds'],
  }));
};

ws.onmessage = (event) => {
  const msg = JSON.parse(event.data);
  switch (msg.type) {
    case 'ticker':
      // 更新报价显示
      updatePrice(msg.productId, msg.price);
      break;
    case 'l2update':
      // 增量更新订单簿
      updateOrderBook(msg.changes);
      break;
    case 'snapshot':
      // 初始化订单簿
      initOrderBook(msg.bids, msg.asks);
      break;
    case 'order':
      // 更新用户订单状态
      updateOrderStatus(msg.id, msg.status);
      break;
    case 'funds':
      // 更新余额显示
      updateBalance(msg.currencyCode, msg.available, msg.hold);
      break;
    case 'pong':
      break; // 心跳响应，忽略
  }
};

// 心跳保活：每 30 秒发送一次 ping
setInterval(() => {
  if (ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify({ type: 'ping' }));
  }
}, 30000);
```
