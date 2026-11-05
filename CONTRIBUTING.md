# 贡献指南

> 目标：让每次改动都能被测试、CI 和后来的人理解。规则尽量交给可执行检查，
> 而不是靠口头约定；本文件只解释「为什么」和「怎么做」。

## 一、环境

| 工具 | 版本 | 说明 |
|---|---|---|
| JDK | 17+ | `maven-enforcer-plugin` 会在构建开始时校验 |
| Maven | 3.8+ | 同样由 enforcer 校验 |
| Node.js | 20+ | 控制台 `console-ui/` |
| MySQL / Redis / Kafka | 见 `docker-compose.yml` | 只有跑集成测试 / 真实后端 E2E 时需要 |

首次启动：

```bash
cp .env.example .env        # 按需填写；.env 已被 gitignore，Spring Boot 不会自动加载
docker compose up -d mysql redis kafka
mvn spring-boot:run
```

真实密码、token 密钥只通过环境变量或 `/run/secrets` 注入，**不要写进仓库**。
提交前跑一次 `bash tools/check-secrets.sh`。

## 二、分支与提交

- 从 `main` 拉短分支：`feat/<topic>`、`fix/<topic>`、`test/<topic>`、`docs/<topic>`。
- 提交信息用 `<type>: <一句话>`，type 与现有历史保持一致：
  `feat` / `fix` / `test` / `docs` / `refactor` / `chore`。
- 一个提交只做一件事；重构与行为变更分开，方便回滚和 review。
- 作者信息使用本机 Git 配置的真实身份，不添加工具生成署名。
- 合并前保持工作区干净：没有 `target/`、`node_modules/`、`.env`、临时脚本或调试输出。

## 三、提交前验证

### 后端

```bash
mvn test                                  # 纯单测，不需要中间件
bash tools/check-secrets.sh               # 高置信度密钥扫描
git diff --check                          # 行尾空白 / 冲突标记

# 全量（MySQL + Redis + Kafka）
tools/init-it-db.sh
BIDPULSE_IT=true mvn verify -Pfull-tests
```

集成测试默认被 tag 排除；`-Pfull-tests` 下如果缺少 `BIDPULSE_IT=true` 会直接失败，
不会静默跳过。测试库必须是本机 `*_it` 库、Redis database `1..15`、Kafka 主题以
`bidpulse_it_` 开头，守卫会二次校验。

### 前端

```bash
cd console-ui
npm ci
npm run lint
npm test
npm run test:e2e        # mock 后端，不需要基础设施
npm run build
```

真实后端 E2E 另跑：

```bash
E2E_BASE_URL=http://127.0.0.1:5173 E2E_USERNAME=admin E2E_PASSWORD='<密码>' npm run test:e2e
```

## 四、代码分层

```text
api/controller         HTTP 契约：参数校验、响应包装、鉴权入口
bid/ event/ breaker/   竞价热路径与纯领域逻辑
infra/                 MySQL / Redis / Kafka 适配与持久化
console/api            控制台 HTTP 入口
console/query          只读查询（只依赖只读连接池）
console/command        写操作（鉴权、幂等、审计、事务）
admin/ auth/ audit/    管理端、身份、审计
```

必须遵守的边界由 `ArchitectureRulesTest`（ArchUnit）自动检查：

- 热路径（`bid` / `event` / `breaker` / `job`）不得依赖控制台、管理端、身份或审计；
- `infra` 不得依赖控制台、管理端、身份；
- `console.query` 与 `console.command` 不互相依赖；
- controller 不直接依赖 `JdbcTemplate` 或 `infra.persistence`；
- `api.dto` 不依赖服务/持久化类型；
- `event` / `entity` 不依赖 Spring、Kafka、Jackson；
- 生产代码统一构造器注入，禁止字段 `@Autowired`。

新增分层规则时，先确认现有代码已经满足，再写进 `ArchitectureRulesTest`；
不要让规则长期 `@Disabled`。

## 五、数据库迁移

- 文件名：`YYYYMMDD_NN_snake_case.sql`，放在 `sql/migrations/`，按字典序执行。
- **append-only**：已合入的迁移不再修改；要修正就新增版本。
- 必须幂等：用 `information_schema` 判断列/索引是否存在，重复执行不报错。
- 禁止 `DROP TABLE`、`TRUNCATE`、无 `WHERE` 的 `DELETE`；删列/改类型要写清备份与回滚方案。
- 加索引使用 `ALGORITHM=INPLACE, LOCK=NONE`。
- 每个脚本末尾写 `INSERT IGNORE INTO schema_migration`，版本号与文件名一致。
- 同步更新：
  1. `sql/all-schema.sql` 的 `schema_migration` 种子（baseline 声称包含全部迁移）；
  2. `sql/migrations/README.md` 的版本表。
- 上述约定由 `MigrationConventionTest` 检查；「重复执行是否幂等」由 CI 重跑
  baseline + 全部迁移覆盖。

## 六、控制台写接口

控制台的 POST / PUT / DELETE 必须同时满足：

1. 调用 `requireWrite()`（OPERATOR / ADMIN 才放行），拒绝事件写入审计；
2. 接收可选的 `Idempotency-Key`，通过 `IdempotencyService` 做 Redis `SET NX PX`；
   重复请求返回 409，失败不释放 key；
3. 写操作落在 `console.command`，读写都走审计；
4. controller 只做 HTTP 契约，SQL 放在 `console.query` / `console.command`；
5. 只读查询使用 `consoleReadJdbcTemplate`，不要占用主连接池。

## 七、热路径约束

`/bid` 的预算与延迟是硬约束：

- 新增依赖前先问「是否在每次竞价都执行」；能异步、能采样、能只在控制台路径做的，不要放进热路径；
- 外部 `/bid` 不生成决策轨迹、不写额外表；
- 新增 Redis key 必须带业务前缀与 TTL，并在 `docs/architecture.md` 记录；
- 修改 Lua 脚本要补并发/幂等测试，并确认金额单位仍是「分」；
- 新增 Kafka 消费逻辑要复用 `processed_event` 去重与载荷指纹冲突处理，不能覆盖已有账单。

## 八、Review 检查清单

- [ ] 行为变化有对应测试；修 bug 的测试在修复前会失败；
- [ ] 没有新增字段注入、跨层依赖、controller 里的 SQL；
- [ ] 迁移幂等，baseline 种子与迁移 README 已同步；
- [ ] 控制台写接口有鉴权 + 幂等 + 审计；
- [ ] 没有密钥、真实账号、内部地址进入仓库；
- [ ] 文档（README / architecture / 重构计划书）与实际行为一致；
- [ ] 本地验证矩阵全绿，CI `release-gate` 通过。

## 九、发布门禁

CI 的 `release-gate` 聚合以下检查，分支保护只需勾选这一个：

- 后端：空白检查、密钥扫描、时区校验、`mvn verify -Pfull-tests`、健康检查冒烟；
- 前端：lint、单测、构建、Playwright mock E2E；
- 任一失败即阻断合并。
