# 项目结构说明

> 目标：第一次打开仓库时，能在 10 分钟内知道「每个目录放什么、一次请求经过哪里、改一个功能该动哪些文件」。
>
> 运行方式、接口和配置见仓库根目录的 [`README.md`](../README.md)；这里只讲结构与边界。

---

## 1. 一分钟认识

BidPulse 是一个前后端分离的 RTB 竞价演示项目：

- **后端**：Spring Boot 单体，按「HTTP 边界 / 竞价领域 / 基础设施 / 定时任务 / 控制台 BFF」分层；
- **前端**：独立的 `console-ui/`，Vue 3 + Vite，只通过 `/console-api/v1` 访问后端；
- **数据**：MySQL 存广告与账单，Redis 存预算/频次，Kafka 承载异步计费事件；
- **认证**：控制台与 `/admin/**` 使用 Bearer token + 角色；写操作写审计表。

---

## 2. 根目录

```text
bidpulse-engine/
├─ README.md                项目总览、快速开始、接口、配置、测试
├─ pom.xml                  Maven 后端构建
├─ docker-compose.yml       MySQL / Redis / Kafka 本地依赖
├─ src/
│  ├─ main/java/com/bidpulse   后端主代码
│  ├─ main/resources/          application.yml、MyBatis XML
│  └─ test/java/com/bidpulse   单元测试 + 集成测试
├─ console-ui/              独立前端工程（Vue 3 + Vite + Element Plus）
├─ sql/
│  ├─ all-schema.sql         唯一权威 DDL（含 audit_log）
│  └─ seed-demo.sql          轻量演示数据
├─ tools/                    造数、候选检索基准、兜底率压测脚本
├─ data/                     本地生成的数据（gitignored，不入库）
├─ docs/                     文档
│  ├─ README.md              文档导航
│  ├─ architecture.md        本文件：结构与改动地图
│  ├─ 重构计划书.md          P0/P1/P2 记录与取舍
│  ├─ images/                README 截图
│  └─ local/                 本地深度文档（gitignored，不随仓库分发）
└─ .github/workflows/ci.yml  CI：后端全量 + 前端 lint/build/test/E2E
```

### 目录职责边界

| 目录 | 放什么 | 不放什么 |
|---|---|---|
| `src/main/java/com/bidpulse/api` | HTTP controller、请求 DTO、统一响应/异常 | 业务计算、Redis/Kafka 细节 |
| `src/main/java/com/bidpulse/bid` | 竞价领域：检索、二价、预算裁决、兜底 | Controller、SQL 文本 |
| `src/main/java/com/bidpulse/infra` | MyBatis、Redis、Kafka、本地缓冲 | 业务编排 |
| `src/main/java/com/bidpulse/job` | 定时任务：复位、对账、DLT 重放、清理 | 在线请求逻辑 |
| `src/main/java/com/bidpulse/console` | 控制台 BFF：只读聚合 + 运维动作 | 竞价主链路 |
| `console-ui/src/views` | 页面级组合 | 可复用组件、HTTP 细节 |
| `console-ui/src/components` | 可复用 UI 块 | 页面路由、全局状态 |
| `sql/` | DDL 与轻量 seed | 运行时数据文件 |
| `tools/` | 可重复执行的脚本 | 业务代码 |

---

## 3. 后端包结构

```text
com.bidpulse
├─ api/                 HTTP 边界
│  ├─ controller/       BidController、AdAdminController、ConsumerAdminController
│  ├─ dto/              请求/查询参数
│  ├─ ApiResponse       统一响应
│  ├─ BizException      业务异常 + HTTP 状态码
│  └─ GlobalExceptionHandler
├─ bid/                 竞价领域
│  ├─ BidService        检索 → 预算裁决 → 二价 → 兜底 → 事件旁路
│  ├─ PricingPolicy     纯函数：二价、最小加价、分单位量化
│  ├─ FallbackAdProvider 三级兜底缓存
│  └─ TargetingResolver 年龄段/性别等请求参数归一化
├─ admin/               广告管理服务（CRUD + 预算同步）
├─ auth/                登录、token、角色、安全过滤链
├─ audit/               写操作审计（filter + service）
├─ console/             控制台 BFF
│  ├─ api/              /console-api/v1 controller
│  ├─ command/          运维写动作（DLT 单条重放等）
│  ├─ query/            只读聚合（概览、Kafka lag、DLT 浏览）
│  ├─ dto/              控制台响应模型
│  └─ config/           CORS 等控制台装配
├─ event/               Kafka 事件契约（BidEvent）
├─ infra/
│  ├─ persistence/      AdMapper、AuditLogMapper
│  ├─ redis/            BudgetService（Lua 原子扣减/频次/播种/对账）
│  ├─ kafka/            发布器、消费模板、三个消费组、DLT 错误处理
│  └─ buffer/           竞价明细 / 耗尽状态本地缓冲
├─ breaker/             熔断门（Sentinel + local 基线）
├─ job/                 BudgetKeyCleaner、BudgetResetJob、BudgetReconJob、
│                       DltReplayJob、ProcessedEventCleaner
├─ config/              BidPulseProperties + Kafka/Redis/AdminClient 装配
└─ entity/              ad / bid_log 等持久化实体
```

### 一条 `/bid` 请求的归属

```text
BidController
  → BidService（编排）
     → AdMapper.findCandidates（检索）
     → BudgetService.deduct（Redis Lua 原子预算 + 频次）
     → PricingPolicy（二价）
     → FallbackAdProvider（失败收敛）
     → Kafka publisher + BidDetailBuffer（异步旁路）
```

排查时优先看 `BidService` 的编排顺序；定价只改 `PricingPolicy`；预算/频次只改 `BudgetService` 的 Lua 与 key 约定。

---

## 4. 前端结构

```text
console-ui/src
├─ api/
│  ├─ client.ts          fetch 封装、401 跳登录、错误映射
│  ├─ console.ts         控制台 REST 调用
│  ├─ auth.ts            登录调用
│  ├─ types.ts           响应类型
│  └─ mocks/             mock 模式的接口数据（按域拆分）
├─ auth.ts               token/角色状态（sessionStorage）
├─ router.ts             路由 + 登录/角色守卫
├─ views/                页面：概览、竞价、广告、预算、运维、数据、实验、审计、登录
├─ components/           页面内可复用块（ads/ops/overview）
├─ composables/          usePolling、useAsyncAction、useDirtyForm
├─ styles/               base / components / pages 三段式 CSS
└─ e2e/                  Playwright 端到端
```

前端只访问 `/console-api/v1`；Vite 开发态代理到 8080，生产态可用 `VITE_API_BASE` 指向独立后端。

---

## 5. 三条数据流

### 5.1 同步竞价

```text
流量方 → POST /bid → BidController → BidService
  → MySQL 候选
  → Redis Lua（频次 + 预算 + 扣减）
  → 二价计费
  → 兜底（任何失败）
  → 返回 + Kafka 事件旁路
```

### 5.2 控制台读/写

```text
前端 → /console-api/v1/** → TokenAuthFilter → controller
  → query/ 只读聚合（概览、预算、Kafka、DLT、数据）
  → command/ 运维写动作（重放、对账、暂停消费）
  → AuditLogFilter 记录写操作
```

### 5.3 异步计费

```text
billing-event / hourly-preagg / freq-antifraud 三个消费组
  → processed_event(event_id, grp) 幂等
  → billing_event / bid_stat_hourly / ad_freq
  → 失败指数退避 → DLT → DltReplayJob 限量重放
```

---

## 6. 改功能时看哪里

| 想改什么 | 先看 | 备注 |
|---|---|---|
| 二价规则、最小加价 | `bid/PricingPolicy` | 纯函数，优先补单测 |
| 预算/频次原子逻辑 | `infra/redis/BudgetService` | Lua 是权威；先看 key 约定与返回码 |
| 候选检索 SQL | `infra/persistence/AdMapper` + XML | 注意热路径超时与索引基线 |
| 兜底行为 | `bid/FallbackAdProvider` | 三级缓存/退避，不是简单静态常量 |
| Kafka 重试/DLT | `infra/kafka` + `job/DltReplayJob` | 位点与幂等语义不要混 |
| 定时任务 | `job/` | `@Scheduled` 与配置项在 `application.yml` |
| 控制台只读接口 | `console/query` + `console/api` | 避免把聚合写进 controller |
| 控制台运维写操作 | `console/command` + `console/api` | 需要角色校验与审计 |
| 登录/角色 | `auth/` | 密码从环境变量注入 |
| 审计 | `audit/` + `sql/all-schema.sql` | 表缺失时只打日志 |
| 前端页面 | `console-ui/src/views` | 数据请求放 `api/console.ts` |
| 前端可复用块 | `console-ui/src/components` | 不要在这里做路由跳转 |
| 建表/索引 | `sql/all-schema.sql` | 唯一权威 DDL |

---

## 7. 测试布局

```text
src/test/java/com/bidpulse
├─ *Test                   不依赖中间件的单元测试
├─ BidFlowTest             真连 MySQL + Redis + Kafka 的端到端
├─ admin/                  AdAdminService、事务回滚/提交
├─ auth/                   登录、角色矩阵、安全过滤链
├─ audit/                  审计过滤
├─ console/                控制台 REST 契约、查询、CORS
├─ infra/redis/            预算 Lua、冷启动重建、频次
├─ infra/kafka/            消费模板错误分类
└─ job/                    预算对账、DLT 重放
```

- 默认 `mvn test` 只跑无中间件用例；
- `mvn test -Pfull-tests` 跑全量（含 `@Tag("integration")`）；
- 前端：`npm run lint`、`npm test`、`npm run test:e2e`；
- 真实后端 E2E 需要 `E2E_BASE_URL` + `E2E_USERNAME` / `E2E_PASSWORD`。

---

## 8. 推荐阅读顺序

1. 根目录 [`README.md`](../README.md)：先跑起来，知道项目解决什么问题；
2. 本文件：知道目录边界与改动入口；
3. `docs/local/项目学习指南.md`：按 3 天路线理解主链路；
4. `docs/local/项目知识文档.md`：深挖设计取舍、实测口径、面试问答；
5. `docs/local/数据库设计说明.md`：表结构、索引与数据量；
6. [`重构计划书.md`](重构计划书.md)：看已经做过什么、哪些明确后置。

> `docs/local/` 是本地深度文档，`.gitignore` 不随仓库分发；仓库内以 `README.md`、本文件、`docs/重构计划书.md` 为准。
