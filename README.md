# BidPulse · 广告竞价投放

**目录**：
· [核心特性](#一核心特性) 
· [技术栈](#二技术栈) 
· [架构总览](#三架构总览) 
· [快速开始](#四快速开始) 
· [接口一览](#五接口一览) 
· [核心设计](#六核心设计) 
· [关键配置](#七关键配置) 
· [代码结构](#八代码结构) 
· [测试与 CI](#九测试与-ci) 
· [造数与压测工具](#十造数与压测工具) 
· [数据库与升级](#十一数据库与升级) 
· [边界与诚实标注](#十二边界与诚实标注)

---

## 一、核心特性

- **候选检索下推 SQL**：`ad JOIN ad_target` 多维求交，`ORDER BY bid DESC LIMIT 50` 全部在 SQL 内完成，热路径只回传竞价必需字段。
- **Redis Lua 原子扣减**：余额校验、扣减、累计消耗收进一个脚本，消除并发超扣；金额统一以「分」为单位，成交价显式量化到分。
- **实时频次控制**：请求可携带可选 `userId`，按「用户 × 广告 × 自然小时」限频（默认 3 次/小时）；频次校验与预算扣减合并在同一个 Lua 内一次原子裁决，超限只跳过候选，不扣预算、不计数、不误标耗尽；匿名流量整段短路，不增加 Redis 操作。
- **二价（Vickrey）计费**：成交价 = `min(胜出价, 次高价 + 最小加价)`；预算不足的候选自动 fall-through。
- **超时 + 分层熔断 + 兜底**：总预算 1000ms；DB 超时下沉到 `MAX_EXECUTION_TIME(800)` + MyBatis timeout；Redis 用 Lettuce 200ms；DB / Redis 各自独立熔断；任何失败收敛到兜底广告，绝不返回空。
- **Kafka 旁路不可丢**：`acks=all` + 幂等生产者 + 手动 ack；三消费组共用 `processed_event` 幂等去重；失败按 1s→2s→4s→8s→16s 指数退避（约 31s）后进入 `bid_event.DLT`；DLT 消息由定时任务限量重放回主主题（带重放次数头，超过上限留待人工）。
- **可丢明细异步落库**：竞价明细走本地有界队列，500 条或 200ms 批量刷盘，队列满直接丢，绝不阻塞主链路。
- **预算生命周期完整**：检索阶段预热 key、Lua 惰性播种、日预算复位、管理端改预算当天同步 Redis、历史 key 定时清理。
- **可观测**：Actuator 健康检查、Metrics、Prometheus；消费组 lag 查询接口。

---

## 二、技术栈

| 分类 | 选型 | 用途 |
|---|---|---|
| 语言 / 框架 | Java 17 · Spring Boot 3.2.5 | Web、配置、调度、事务 |
| 数据库 | MySQL 8.0 | 广告、定向、竞价明细、计费事件、聚合表 |
| 持久层 | MyBatis 3.0.3 | 手写 SQL，精确控制执行计划 |
| 缓存 | Redis 7 + Lettuce（commons-pool2） | 预算余额、Lua 原子扣减、兜底广告 |
| 消息 | Kafka 3.7 单节点 KRaft | 计费事件旁路、三个消费组、死信主题 |
| 熔断 | Sentinel 1.8.6（`sentinel-core`） | 慢调用比例熔断，可切回本地实现做 A/B |
| 可观测 | Actuator + Micrometer + Prometheus | 健康检查与指标出口 |

---

## 三、架构总览

```
                         ┌──────────── 主链路（同步，SLA 1000ms）────────────┐
 流量方 ──POST /bid──► BidController ──► BidService
                                           │
                  ┌────────────────────────┼──────────────────────────────┐
                  ▼                        ▼                              ▼
        ① 候选检索（MySQL）     ② 预算扣减 + 频次控制（Redis Lua）  ③ 二价计费（Vickrey）
        ad JOIN ad_target          原子扣减 budget:{ad}:{date}        成交价 = min(胜出价,
        ORDER BY bid DESC          频次超限 → 下一个；余额不足 → 下一个   次高价 + 最小加价)
        LIMIT 50                   失败/超时 → 放弃剩余候选
                  │                        │
                  └────────────┬───────────┘
                               ▼
                    ④ 失败 / 耗尽 / 超时 ──► 兜底广告
                       Redis fallback:ad → 本地缓存（5s；失败退避 30s）→ 静态常量
                               │
                               ▼
                  返回 hasAd / winner / payPrice / fallback
                               │
               ┌───────────────┴────────────────┐
               ▼                                ▼
   Kafka bid_event（旁路，不可丢）       BidDetailBuffer（本地队列，可丢）
   acks=all + 幂等 + max.block.ms=100    容量 1 万，500 条或 200ms 批量刷盘
               │                                │
   ┌───────────┼────────────┐                   ▼
   ▼           ▼            ▼                 bid_log
hourly      billing      freq
preagg      recon        antifraud
   │           │            │
bid_stat    billing      ad_freq
hourly      event
   └───────────┴────────────┘
         processed_event(event_id, grp) 幂等去重
         指数退避 1s→16s → bid_event.DLT → 定时限量重放
```

### 数据分级（本项目最核心的取舍）

| 数据 | 通道 | 可靠性 | 说明 |
|---|---|---|---|
| `billing_event` 计费事实 | Kafka 旁路 | **不可丢**：至少一次 + 幂等去重 + 死信 | 涉及金钱，失败重试后进 DLT，绝不静默跳过 |
| `bid_log` 竞价明细 | 本地有界队列 | **可丢、可采样** | 队列满直接丢弃，绝不阻塞主链路 |
| `bid_stat_hourly` / `ad_freq` | Kafka 旁路 | 可从事件重放 | 小时聚合、离线频次报表（实时拦截由 Redis 承担） |
| 频次计数 `freq:u:*` | Redis | **可丢**：TTL 到期或 Redis 重启后计数清零 | 实时频控，只影响「是否继续投放」，不影响已发生的账 |
| `processed_event` 去重标记 | MySQL | 按保留期清理 | 三消费组共用，默认保留 8 天（须 ≥ Kafka retention） |

---

## 四、快速开始

### 1. 前置条件

- Docker Desktop（跑大规模造数/压测建议 ≥ 8GB 内存）
- JDK 17、Maven 3.9+

### 2. 启动中间件

```bash
docker compose up -d
docker compose ps      # bidpulse-mysql / bidpulse-redis / bidpulse-kafka 均为 healthy
```

> 容器名使用 `bidpulse-` 前缀；数据卷 key 为 `mysql-data-bidpulse` / `kafka-data-bidpulse`
> （`docker volume ls` 里实际显示为 `bidpulse_mysql-data-bidpulse` 等，前缀是 compose 项目名）。
> 服务使用独立的 `bidpulse` 数据库与数据卷，首次启动为空库，可直接建表 / 造数。
> 停止：`docker compose down`；连同数据卷删除：`docker compose down -v`。

### 3. 建表 + 演示造数

```bash
docker exec -i bidpulse-mysql mysql -uroot bidpulse < sql/all-schema.sql
docker exec -i bidpulse-mysql mysql -uroot bidpulse < sql/seed-demo.sql
```

- `all-schema.sql`：一次建好 7 张表。脚本以 `DROP TABLE` 开头，**重跑会清空数据**，只用于初始化/重置。
- `seed-demo.sql`：200 条广告 + 800 条定向条件，覆盖全部 **105 种「地域 × 年龄 × 性别」检索组合**（年龄 7 桶、性别含 `unknown`），保证任意请求都能命中候选，不会出现空广告。
- 重复灌数前先执行：`TRUNCATE ad_target; TRUNCATE ad;`。

### 4. 启动应用

```bash
mvn spring-boot:run
# 或
mvn -DskipTests package && java -jar target/bidpulse-engine-0.1.0-M0.jar
```

默认端口 `8080`。

### 5. 验证

```bash
curl http://127.0.0.1:8080/actuator/health
curl -X POST http://127.0.0.1:8080/bid -H "Content-Type: application/json" \
  -d '{"slotCode":"slot-01","region":"110000","age":28,"gender":1}'
```

首次运行该示例会命中 2 个候选：`adId=110` 出价 1.50 胜出，次高价 1.00，成交价 `1.01`（`priceMode=1`）。

---

## 五、接口一览

### 竞价

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/bid` | 一次竞价请求，返回胜出广告与成交价 |

请求体：

```json
{
  "slotCode": "slot-01",
  "region": "110000",
  "age": 28,
  "gender": 1,
  "categoryCode": "travel",
  "interests": "数码科技,游戏",
  "ageBucket": "25-30",
  "userId": "u-10086"
}
```

- `age` 与 `ageBucket` 二选一：显式传 `ageBucket` 时优先；只传 `age` 由服务端按 7 个桶换算。
- `gender`：1 男 / 2 女 / 0 或缺失 → `unknown`。
- `userId`（可选，≤ 64 字符）：传了才做实时频次控制；不传视为匿名流量，不读写频次 key。
- 响应统一为 `ApiResponse`：`code / msg / data / wallMs`；`data.priceMode` 为 0 一价 / 1 二价 / 2 兜底。

### 管理端

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/admin/ad` | 新建广告（ad 与 target 同事务，至少 1 条定向） |
| GET | `/admin/ad/{id}` | 详情（含定向条件） |
| GET | `/admin/ad` | 列表：`?advertiserId=&status=&pageNum=&pageSize=` |
| PUT | `/admin/ad/{id}` | 更新（**全量替换**；`targets=null` 不动定向，`[]` 清空） |
| DELETE | `/admin/ad/{id}` | 删除（级联清定向） |
| PUT | `/admin/ad/{id}/status` | 上下架：`?status=1` 在投 / `?status=2` 暂停 |
| POST | `/admin/consumer/pause` | 暂停全部 Kafka 监听容器（模拟消费端故障） |
| POST | `/admin/consumer/resume` | 恢复监听 |
| GET | `/admin/kafka/lag` | 查询消费组积压：`?group=billing-recon` |

注意：

- `status=3`（已耗尽）是系统状态，不接受手工指定。
- 修改 `dailyBudget` 且事务提交成功后，会自动按「新日预算 − 今日已消耗」重算当天 Redis 余额，**当天生效**。
- 把 `status` 从 3 改回 1 本身不会重置预算；需要同时修改日预算（或等次日 00:05 复位任务）。

### 运维 / 可观测

| 路径 | 说明 |
|---|---|
| `/actuator/health` | 健康检查 |
| `/actuator/metrics` | 指标 |
| `/actuator/prometheus` | Prometheus 抓取端点 |

---

## 六、核心设计

### 1. 候选检索：把排序与限流下推 SQL

`ad JOIN ad_target` 按地域 / 年龄段 / 性别求交，`ORDER BY a.bid DESC LIMIT 50` 在 SQL 内完成。
`ad_target` 的 `idx_dim_lookup(dim_type, dim_value, ad_id)` 承担「筛得准」，热路径只回传竞价必需字段；
`ad` 表在检索路径上刻意不建索引（已有的 `idx_adv` 只服务管理端列表），用于保留全表扫描基线做性能对照。

### 2. 二价定价：金额口径统一到「分」

`PricingPolicy` 是纯函数：`成交价 = min(胜出价, 次高价 + 最小加价)`。
成交价在进入计费前显式 `setScale(2, DOWN)` 量化到分，保证「返回给调用方的 `payPrice`、写 Kafka/`bid_log` 的金额、Redis 实际扣减的分」三者完全一致。
预算不足的候选沿出价降序继续尝试（fall-through），不会因为一个广告没钱就整单流拍。
「次高价」取的是**有效出价** `min(候选出价, 当日剩余预算)`：`nextPayableBid` 用一次只读 `MGET`
批量取回窗口内（最多向后 5 条）竞争者的剩余预算，避免拿一个当天已耗尽的候选出价把成交价抬高；
价格随预算连续变化，不会因为差 1 分钱就从「按次高出价」跳到「按自身出价」。
窗口内没有余额 > 0 的竞争者时退回自身出价，此时 `priceMode=0`（一价）。

### 3. 超时预算 + 独立熔断

- 主链路总预算 `bidpulse.timeout.total-ms=1000`，计费循环超期即兜底；
- DB 候选检索：MySQL 优化器 hint `MAX_EXECUTION_TIME(800)` + MyBatis `timeout="1"`；
- Redis 计费：Lettuce 命令超时 200ms；
- DB 与 Redis 各自独立熔断门（不同 Sentinel 资源），一个依赖故障不会把另一个也短路；
- 熔断实现可在 `sentinel`（慢调用比例）与 `local`（自写连续失败计数）之间切换，用于 A/B 对照。

### 4. 三级兜底

任何失败（超时 / 熔断 / 候选为空 / 预算耗尽）都收敛到兜底广告：

1. Redis `fallback:ad`（运营可改、无需发版）；
2. 本地缓存（正常 5s 刷新；读取失败退避 30s，期间不再触达 Redis）；
3. 代码静态常量（最后保底）。

`bidpulse.fallback.enabled=false` 仅用于 A/B 对照（复现「没有兜底机制的旧实现」）；`bidpulse.fallback.force=true` 可模拟依赖全面故障。

### 5. Kafka 旁路：至少一次 + 幂等 + 死信

- 生产者：`acks=all` + 幂等 + `max.block.ms=100`（broker 不可达时最多阻塞 100ms，绝不拖住 `/bid`）；
- 消费模板：`解析 → INSERT IGNORE processed_event(event_id, grp) → 业务写 → 事务提交后 ack`；
- 错误处理：可重试故障按 1s→2s→4s→8s→16s 指数退避（总窗口约 31s）后投递 `bid_event.DLT`；报文无法解析 / 缺少 `eventId` 直接进 DLT；
- DLT 重放：默认每 5 分钟拉取一批（单轮上限 1000 条 / 10s），可解析且未超过重放上限的消息同步投回主主题并写入 `x-replay-count` 头；发不出去就不提交位点，下一轮重试；不可解析 / 超限消息跳过并保留在 DLT 待人工处理；
- 消费组：`hourly-preagg`（小时聚合）、`billing-recon`（计费流水）、`freq-antifraud`（频次反作弊）；
- 去重表按保留期（默认 8 天，须不短于 Kafka `retention.ms`）每日分批清理。

### 6. 竞价明细异步落库

`BidDetailBuffer` 使用容量 1 万的有界队列，后台单线程「攒够 500 条或停留 200ms」批量刷入 `bid_log`。
队列满直接丢弃低价值明细，主链路只做一次 `offer()`，写入吞吐由后台线程决定。

### 7. 预算生命周期

- **扣减**：Lua 原子完成「校验 → 扣减 → 累计消耗」，金额以分存储；`budget` 与 `spent` 两个 key 用同一个日期一次算出，跨零点不会分裂到两天；
- **冷启动重建**：两个 key 同时丢失（Redis 重启 / FLUSH）时，Lua 返回 `NEEDS_SEED` 而不是按整笔日预算放款；调用方用 `billing_event` 当日汇总 `SET NX` 重建后再重试扣减（DB 查询在 Redis 熔断器之外，避免误开闸）；
- **预热**：检索阶段对候选批量 `SET NX`，本地按「广告 + 日期」去重，避免每个请求重复播种；
- **耗尽**：余额不足的广告 id 投递本地缓冲，后台批量回写 `ad.status=3`，后续检索直接排除；
- **复位**：每天 00:05 把昨天及更早标记为 3 的广告恢复为 1；
- **管理端改预算**：事务提交后按「新日预算 − 今日已消耗」原子重算当天余额，当天生效；
- **对账**：每小时对比 Redis `spent` 与 `billing_event` 当日汇总。Redis 高于账单 → 只告警（钱确实扣了，不能下调）；DB 高于账单 → 用原子脚本按账单重算（只升不降）；
- **清理**：预算 key 播种即带 TTL，另每日 01:00 用 `SCAN + UNLINK` 清理超保留期的历史分桶。

### 8. 实时频次控制

- **维度与窗口**：`freq:u:{userId}:{yyyyMMddHH}` 是一个用户当前小时的 Hash，field 为 `adId`，value 为成功次数；默认上限 3 次，首次写入时设置 2 小时 TTL，旧小时自动淘汰。
- **一次裁决**：频次检查在预算之前，扣减成功后在同一脚本内 `HINCRBY`；超限返回 `2`，余额不足返回 `0`，双 key 丢失返回 `-1`，成功返回 `1`。整段只占一次 RTT。
- **失败语义**：频次超限的候选直接 fall-through，**不扣预算、不计数、不写 `ad.status=3`**；只有真正余额不足才进入耗尽回写缓冲。
- **匿名短路**：未传 `userId` 时 Lua 跳过频次分支，热路径不增加任何 Redis 命令。
- **已知近似**：被限频的候选仍按出价参与排序；若它排第一且后面有可支付候选，二价计算仍会把它当作「有效次高价」。
- **与离线报表的关系**：`freq-antifraud` 消费组继续按 `ad_id` 在 MySQL `ad_freq` 表累加触达报表，实时拦截由 Redis 完成，两者互不阻塞。

### 9. 定向分桶与演示数据

`TargetingResolver` 把原始画像映射为 `ad_target.dim_value`：7 个年龄桶（`18-24 … 51+`）、`male/female/unknown`。
`seed-demo.sql` 与 `tools/gen_ad_data.py` 使用同一套桶定义；演示造数覆盖全部 105 种检索组合。

---

## 七、关键配置

所有业务配置集中在 `bidpulse.*`，由 `BidPulseProperties` 绑定。

| 配置 | 默认值 | 说明 |
|---|---|---|
| `bidpulse.min-increment` | `0.01` | 二价最小加价（元） |
| `bidpulse.timeout.total-ms` | `1000` | 主链路总超时预算 |
| `bidpulse.bid.candidate-limit` | `50` | 单次竞价候选上限（SQL LIMIT） |
| `bidpulse.circuit-breaker.impl` | `sentinel` | `sentinel` / `local`（A/B 基线） |
| `bidpulse.circuit-breaker.cooldown-ms` | `5000` | 熔断持续时长 |
| `bidpulse.circuit-breaker.db-slow-rt-ms` | `800` | DB 慢调用判定阈值，与 SQL hint 对齐 |
| `bidpulse.circuit-breaker.redis-slow-rt-ms` | `100` | Redis 慢调用判定阈值 |
| `bidpulse.fallback.enabled` | `true` | 兜底总开关（`false` 仅用于 A/B） |
| `bidpulse.fallback.force` | `false` | 强制兜底，模拟依赖全面故障 |
| `bidpulse.fallback.cache-ttl-ms` | `5000` | 兜底广告本地缓存时长 |
| `bidpulse.fallback.retry-backoff-ms` | `30000` | Redis 读取失败后的退避时长 |
| `bidpulse.detail.sync-insert` | `false` | 测量专用：同步单条写 `bid_log`（M1 基线） |
| `bidpulse.kafka.topic` | `bid_event` | 竞价事件主题 |
| `bidpulse.kafka.dlt-topic` | `bid_event.DLT` | 死信主题 |
| `bidpulse.kafka.partitions` | `3` | 主题分区数 |
| `bidpulse.kafka.retry-max-attempts` | `5` | 消费端最大重试次数（1s→16s 指数退避） |
| `bidpulse.kafka.dlt-replay-enabled` | `true` | 是否启用 DLT 定时重放 |
| `bidpulse.kafka.dlt-replay-cron` | `0 */5 * * * ?` | DLT 重放周期（每 5 分钟） |
| `bidpulse.kafka.dlt-replay-max-attempts` | `3` | 同一事件最多重放次数 |
| `bidpulse.kafka.processed-event-retention-days` | `8` | 去重表保留天数（须 ≥ Kafka retention） |
| `bidpulse.kafka.processed-event-cleanup-batch` | `10000` | 去重表单批删除行数 |
| `bidpulse.kafka.processed-event-cleanup-max-per-run` | `1000000` | 单次清理任务删除上限 |
| `bidpulse.budget.key-ttl-seconds` | `172800` | 预算 key TTL（2 天） |
| `bidpulse.budget.cleanup.retention-days` | `2` | 预算 key 保留天数 |
| `bidpulse.budget.reset.cron` | `0 5 0 * * ?` | 日预算复位时间（00:05） |
| `bidpulse.budget.recon.enabled` | `true` | 是否启用预算对账 |
| `bidpulse.budget.recon.cron` | `0 15 * * * ?` | 预算对账周期（每小时第 15 分钟） |
| `bidpulse.budget.recon.tolerance-cents` | `1` | 对账容差（分），吸收在途事件 |
| `bidpulse.frequency.enabled` | `true` | 实时频次控制总开关 |
| `bidpulse.frequency.max-per-hour` | `3` | 每用户每广告每自然小时最大成交次数 |
| `bidpulse.frequency.key-ttl-seconds` | `7200` | 频次 key TTL（2 小时） |

完整配置项、默认值与注释见 `src/main/resources/application.yml` 与 `BidPulseProperties`。

---

## 八、代码结构

```
src/main/java/com/bidpulse
├─ api/                 HTTP 边界：controller、dto、统一响应与异常
├─ bid/                 竞价领域：BidService、PricingPolicy、FallbackAdProvider、TargetingResolver
├─ admin/               管理端服务
├─ event/               Kafka 事件契约（BidEvent）
├─ infra/
│  ├─ persistence/      MyBatis Mapper
│  ├─ redis/            预算 Lua 扣减 / 日预算同步
│  ├─ kafka/            事件发布、消费模板、三个消费组、DLT 错误处理
│  └─ buffer/           竞价明细 / 耗尽状态本地缓冲
├─ breaker/             熔断门（Sentinel + local 基线）
├─ job/                 定时任务（预算 key 清理、日预算复位、预算对账、DLT 重放、去重表保留期清理）
├─ config/              BidPulseProperties 与各组件装配（含 Kafka 主题/错误处理/AdminClient）
└─ entity/              ad / bid_log 实体

src/test/java/com/bidpulse         测试包与主代码一一对应
├─ BidFlowTest          端到端（真连 MySQL / Redis / Kafka）
├─ api/                 HTTP 边界错误（404 / 415）
├─ bid/                 二价定价、兜底广告缓存
├─ breaker/             Sentinel 熔断门
├─ config/              配置绑定、Kafka 重试策略
├─ job/                 预算对账、DLT 重放
└─ infra/
   ├─ kafka/            消费模板错误分类
   └─ redis/            预算扣减、冷启动重建与预算同步
```

其它目录：

- `sql/`：`all-schema.sql`（统一 DDL）+ `seed-demo.sql`（轻量演示造数）
- `tools/`：大规模造数脚本、SQL 基准、兜底率压测脚本

---

## 九、测试与 CI

### 纯单测（不依赖中间件）

```bash
mvn test
# 等价于只跑下列 8 个类（@Tag("integration") 的用例默认被排除）
mvn -Dtest='PricingPolicyTest,BidPulsePropertiesTest,SentinelBreakerGateTest,AbstractBidEventConsumerTest,ApiErrorHandlingTest,FallbackAdProviderTest,KafkaErrorHandlingConfigTest,DltReplayJobTest' test
```

> 注意：用 `-Dtest=<类名>` 单独指定**集成测试类**时，也必须同时激活全量 profile，
> 否则 surefire 会跑 0 个用例并返回 BUILD SUCCESS：
> `mvn -Dtest=BidFlowTest -Pfull-tests test`。

覆盖：二价定价边界、配置绑定、Sentinel 熔断语义、消费模板错误分类、HTTP 404/415、兜底广告缓存、Kafka 指数退避参数、DLT 重放头/跳过/位点语义，共 29 个用例。

### 集成测试（需要中间件）

`BidFlowTest`（5，竞价端到端 + 三消费组落库 + 频次超限不误标耗尽）、`BudgetServiceTest`（19，Lua 扣减、冷启动重建、预算同步 + 7 个频次用例）与 `BudgetReconJobTest`（3，预算对账）都带 `@Tag("integration")`，共 27 个用例，会真实连接 MySQL / Redis / Kafka：

```bash
docker compose up -d
docker exec -i bidpulse-mysql mysql -uroot bidpulse < sql/all-schema.sql
mvn test -Pfull-tests      # 跑全量（单测 + 集成）
```

> 用 `-Pfull-tests` 而不是 `-Dsurefire.excludedGroups=none`：Windows 下由 PowerShell 调用 `mvn.cmd` 时，
> 带点的 `-D` 参数会在第一个点处被拆开，Maven 报 `Unknown lifecycle phase '.excludedGroups=none'`。
> 非 Docker 的本机 MySQL 若给 root 设了密码，用 `MYSQL_PASSWORD=xxx` 环境变量覆盖（`application.yml` 已支持）；
> 缺 Redis / Kafka 时，27 个集成用例会报 `Could not open JDBC Connection` / 连接拒绝。

### CI

`.github/workflows/ci.yml` 在 GitHub Actions 中启动 MySQL / Redis / Kafka services，初始化 schema 后执行 `mvn -B test -Pfull-tests`（全量）。

---

## 十、造数与压测工具

### 1. 大规模造数：`tools/gen_ad_data.py`

生成 `data/ad.csv`、`data/ad_target.csv`、`data/dim_dict.csv`（默认 50 万广告，约 600 万定向行）：

```bash
python tools/gen_ad_data.py --quick                  # 10 万广告，快速试参数
python tools/gen_ad_data.py --out data --seed 42     # 完整规模
python tools/gen_ad_data.py --region-level city      # 337 个地市（默认区县约 2500 个）
```

脚本末尾会打印 `docker cp` + `LOAD DATA LOCAL INFILE` 的导入命令（容器名 `bidpulse-mysql`）。
维度字典 `data/dim_dict.csv` 同时也是 `bench_fallback.py` 的输入。

### 2. 候选检索基准：`tools/bench_candidates.sql`

在 MySQL 内直接测量 `findCandidates` 等价 SQL 的服务端耗时（含 PREPARE 开销拆解）：

```bash
docker exec -i bidpulse-mysql mysql -uroot bidpulse < tools/bench_candidates.sql
```

样本数在文件末尾 `CALL bench_candidates(50)` 中调整。

### 3. 空广告率 / 兜底率压测：`tools/bench_fallback.py`

先跑 `gen_ad_data.py` 生成 `data/dim_dict.csv`，再按场景启动服务：

```bash
python tools/bench_fallback.py --port 8081 --n 2000 --mode normal --tag "S1-正常"
python tools/bench_fallback.py --port 8081 --n 2000 --mode nocand --tag "S2-候选为空"
```

结果同时打印到 stdout 并追加到 `data/bench_fallback_history.csv`，便于 A/B 对照。

其余故障场景按脚本头部说明操作：服务以 `--server.port=8081` 启动后，用 `--spring.data.redis.port=6399` 模拟 Redis 不可达；DB 慢查询可临时 `ALTER TABLE ad_target DROP INDEX idx_dim_lookup`（测完用 `ADD KEY idx_dim_lookup (dim_type, dim_value, ad_id)` 重建）。

### 4. 测量开关（A/B 实验）

| 启动参数 | 作用 |
|---|---|
| `--bidpulse.fallback.enabled=false` | 关闭兜底，返回空广告，测量改造前空广告率 |
| `--bidpulse.fallback.force=true` | 强制走兜底，模拟依赖全面故障 |
| `--bidpulse.detail.sync-insert=true` | `bid_log` 退化为同步单条写（M1 基线） |
| `--bidpulse.circuit-breaker.impl=local` | 切回自写熔断器做对照 |
| `--spring.data.redis.port=6399` | 模拟 Redis 不可达 |

---

## 十一、数据库与升级

7 张表：`ad`、`ad_target`、`bid_log`、`billing_event`、`bid_stat_hourly`、`processed_event`、`ad_freq`。
详细字段、索引与设计取舍见 `sql/all-schema.sql` 的建表注释。

> `all-schema.sql` 含 `DROP TABLE`，只能用于初始化/重置。存量库升级请执行文件头部注释中的 `ALTER`：
>
> ```sql
> -- bid_log.age 放宽为可空：只传 ageBucket 的请求不再被伪造成 age=0
> ALTER TABLE bid_log MODIFY age TINYINT NULL DEFAULT NULL
>   COMMENT '年龄，请求未提供（仅传 ageBucket）时为 NULL';
>
> -- processed_event 增加保留期字段与索引（ProcessedEventCleaner 依赖）
> ALTER TABLE processed_event
>   ADD COLUMN created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
>     COMMENT '首次处理时间，供保留期清理',
>   ADD KEY idx_created (created_at);
> ```

---

## 十二、边界与诚实标注

- **单实例假设**：本地熔断器、预算预热缓存均为实例级；多实例部署需改为共享状态（如 Redis / Sentinel 集群规则）。
- **单节点 Kafka**：demo 使用 RF=1；生产应为 RF=3 + `min.insync.replicas=2`。
- **频次维度简化**：实时频次控制覆盖「用户 × 广告 × 自然小时」，但 `userId` 由调用方传入，匿名流量不控频；固定窗口在整点边界存在突刺；被限频候选仍参与二价估价。`freq-antifraud` 消费组在 MySQL 的 `ad_freq` 表按 `ad_id` 累加的是离线触达报表，不承担实时拦截。
- **死信有限自动重放**：`bid_event.DLT` 由定时任务每 5 分钟限量重放（可解析且未超过 3 次的消息），报文非法或超限的消息保留在 DLT 等人工处理；重放依靠 `processed_event` 幂等，不会重复计费。
- **去重表清理是安全网**：极高事件量下应改为按时间分区 / `DROP PARTITION`，逐行删除只适合本项目的数据规模。
- **金额取整**：成交价向下取整到分（对广告主不超额收费），与 Redis 实扣和账单口径一致。
- **管理端点无鉴权**：`/admin/**` 仅用于本地演示，部署到任何非本地环境前必须加认证。
