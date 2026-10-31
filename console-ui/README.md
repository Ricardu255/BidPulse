# BidPulse Console

前后端分离的演示控制台：首页、模拟竞价、广告管理、预算与频次、运行监控、数据查询、演示开关。

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
    allowed-origins: "http://localhost:5173,http://127.0.0.1:5173"
```

## 页面

| 路由 | 内容 |
|---|---|
| `#/overview` | 首页：KPI、健康/熔断、结果分布、趋势、最近竞价 |
| `#/playground` | 模拟竞价：候选列表、二价结果、连打 4 次 |
| `#/ads` | 广告管理：列表、详情/定向、新建/编辑、暂停恢复、调日预算 |
| `#/budget` | 广告当日预算、Redis TTL、当前小时频次 |
| `#/ops` | 运行监控：消费组 lag、DLT 单条/批量重放、预算对账与差异明细 |
| `#/data` | 数据查询：白名单表浏览、CSV 导出 |
| `#/experiments` | 演示开关：兜底、强制兜底、频次开关与每小时上限 |

## 未实现（后续）

| 功能 | 状态 | 依赖 |
|---|---|---|
| 故障注入 / demo 重置 | 未做 | 需要更严格的环境隔离与二次确认 |
| 告警规则 / 通知 | 未做 | 目前只有概览里的熔断状态与对账告警数 |
| 登录 / 权限 / 审计 | 未做 | 对外部署前必须补 |
| SSE / WebSocket 实时推送 | 未做 | 当前为 2–5s 轮询，接口层可替换 |

## 常见问题

| 现象 | 原因 | 处理 |
|---|---|---|
| 预算页提示“广告不存在” | adId 不在 `ad` 表；兜底广告 999999 不是真实广告；广告已被删除 | 使用演示广告 `110`，或从“广告管理”复制有效 ID |
| 编辑广告提示 403 | `allow-write=false`，或前端来源不在 `allowed-origins` | 检查 `bidpulse.console.allow-write` 与 `bidpulse.console.allowed-origins`（同时放行 localhost / 127.0.0.1） |
| 最近结果出现“兜底 999999” | 没有可投放真实广告（无候选/预算耗尽/依赖故障/超时） | 保底行为，不是错误；兜底不计费，`payPrice` 为空 |
| 频次超限后广告还在 | 频次超限只跳过当前候选，不标 `status=3` | 正常业务结果；换 userId 或等下一小时 |
| 请求返回 HTML 或 502/503 | 后端未启动 / 代理不可达 / 依赖故障 | 检查 8080 后端与 5173 Vite 代理 |

## 测试

```bash
npm test          # Vitest：格式化、mock 频率上限/分页/数据/实验开关
npm run test:e2e  # Playwright：mock 模式，自动起 Vite（4173）
```

对真实后端联调：

```bash
# 先启动后端 8080 和 npm run dev（5173）
E2E_BASE_URL=http://127.0.0.1:5173 npm run test:e2e
```

## 边界

- 当前支持只读展示、广告新建/编辑、暂停/恢复、调日预算、DLT 重放、手动对账、消费组暂停/恢复、运行时开关。
- 打开预算页不会播种预算或创建频次 key。
- `bidpulse.console.allow-write=false` 时写操作返回 403。
- 控制台接口默认只适合本地演示；对外部署前必须加鉴权。
