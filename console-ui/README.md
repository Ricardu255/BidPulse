# BidPulse Console

前后端分离的演示控制台（P0）：竞价实验室、预算与频次、Kafka 异步运维。

- 前端：Vue 3 + TypeScript + Vite + Element Plus
- 后端：Spring Boot `/console-api/v1`，代码在 `src/main/java/com/bidpulse/console`
- 前端不依赖 Thymeleaf，也不由后端渲染页面

## 开发

后端先启动（默认 8080）：

```bash
cd ..
mvn spring-boot:run
```

前端独立启动（默认 5173，`/console-api` 代理到 8080）：

```bash
cd console-ui
npm install
npm run dev
```

打开 <http://localhost:5173/>。

### 纯前端预览（不启动后端）

```bash
npm run dev:mock
```

数据来自 `src/api/mock.ts`，可以单独查看页面。打开
`http://localhost:5173/#/playground?autobid=1` 会自动跑一次竞价，方便截图。

## 构建

```bash
npm run build            # 产出独立 dist/，交给 Nginx/任意静态服务器
npm run build:embedded   # 仅单 jar 演示：产物放入后端 static/console
```

独立部署时，用 `VITE_API_BASE` 指向后端完整地址：

```bash
VITE_API_BASE=http://127.0.0.1:8080/console-api/v1 npm run build
```

同时在后端配置允许的前端来源：

```yaml
bidpulse:
  console:
    enabled: true
    allow-write: true
    allowed-origins: "http://localhost:5173"
```

## 页面

| 路由 | 内容 |
|---|---|
| `#/overview` | KPI、健康状态、近 30 分钟趋势、最近竞价 |
| `#/playground` | 竞价模拟、候选列表、二价结果、连打 4 次 |
| `#/ads` | 广告列表、详情/定向、暂停恢复、调日预算 |
| `#/budget` | 广告当日预算、Redis TTL、当前小时频次 |
| `#/ops` | 三个消费组 lag、DLT 浏览/重放、预算对账、暂停/恢复消费 |

## 未实现（后续）

| 功能 | 状态 | 依赖 |
|---|---|---|
| 广告新建/编辑与定向编辑 | 未做 | 新建/编辑表单，复用 `/admin/ad` |
| 数据浏览（bid_log / billing_event 等） | 未做 | `/console-api/v1/data/{table}` |
| 实验开关 / 故障场景 / demo 重置 | 未做 | `/console-api/v1/experiments/**`，需危险操作二次确认 |
| 熔断器状态 / 告警规则 | 未做 | `/console-api/v1/health` 扩展 Sentinel 状态 |
| 登录 / 权限 / 审计 | 未做 | 对外部署前必须补 |
| SSE / WebSocket 实时推送 | 未做 | 当前为 2–5s 轮询，接口层可替换 |
| 前端单测 / E2E | 未做 | Vitest + Playwright |
| CSV 导出 | 未做 | 数据浏览页 |

## 边界

- 当前支持只读展示、广告暂停/恢复、调日预算、DLT 重放、手动对账、消费组暂停/恢复。
- 打开预算页不会播种预算或创建频次 key。
- `bidpulse.console.allow-write=false` 时写操作返回 403。
- 控制台接口默认只适合本地演示；对外部署前必须加鉴权。
