# 双师型教师资历服务（dual-teacher-credential）

教务处安排高风险实践课前，用本服务按指定基准日期（`as_of`）综合判断教师是否真正具备授课资格：
**企业实践时长、设备认证、产业项目经历、带版本的课程能力矩阵**一起核查，逐项给出通过/不通过结论、
采用的证据 ID 和仍缺什么，并以 `decision_id` 保存一份**不可变快照**。

- 技术栈：Kotlin 2.0 · JDK 21 · Ktor 3 · Exposed 0.56 · PostgreSQL 16 · Flyway · Gradle Wrapper
- 不依赖 Docker：集成测试用 H2（PostgreSQL 兼容模式）+ 同一套 Flyway 迁移 + Ktor 测试引擎。

## 1. 本地数据库初始化（PostgreSQL 16）

```bash
# 任选一种创建数据库和账号（默认配置：库名/用户/密码均为 dualteacher）
createdb dualteacher
# 或：
psql postgres -c "CREATE USER dualteacher WITH PASSWORD 'dualteacher'; \
                  CREATE DATABASE dualteacher OWNER dualteacher;"
```

数据库表结构由 Flyway 在服务启动时自动迁移（`src/main/resources/db/migration/V1__init.sql`），无需手工建表。

## 2. 启动

```bash
# 用 JDBC URL
DATABASE_URL='jdbc:postgresql://localhost:5432/dualteacher' \
DB_USER=dualteacher DB_PASSWORD=dualteacher \
./gradlew run

# 或用 Heroku 风格 URL（用户名密码写在 URL 里）
DATABASE_URL='postgres://dualteacher:dualteacher@localhost:5432/dualteacher' ./gradlew run
```

不设置环境变量时，默认连接 `jdbc:postgresql://localhost:5432/dualteacher`（用户/密码 `dualteacher`）。
端口可用 `PORT` 覆盖（默认 8080）。服务启动后会先执行 Flyway 迁移，再启动 HTTP 服务。

日志为 JSON 结构化日志（stdout，logstash-logback-encoder）。

## 3. 测试与构建

```bash
./gradlew test    # 单元测试 + 集成测试（H2 内存库，无需 Docker、无需本机 PostgreSQL）
./gradlew build   # 完整构建（含测试、distTar/distZip 分发包）
```

测试覆盖：区间合并、日期边界（到期日/吊销生效日）、乱序吊销、矩阵版本切换、
重复事件幂等、并发创建同一决定、快照不可变（补录材料不改变旧决定）。

## 4. API

OpenAPI 规范：`GET /openapi.json`（完整字段与示例见该文档）。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/v1/events` | 接入来源事件；`eventId` 全局唯一，重复投递幂等（返回 `duplicate=true`） |
| GET | `/api/v1/teachers/{teacherId}/qualification?course=X&asOf=yyyy-MM-dd` | 基准日资格评估，生成并返回不可变决定快照 |
| GET | `/api/v1/decisions/{decisionId}` | 读取历史决定快照（内容永不改变） |
| GET | `/health` `/health/ready` | 存活检查 / 含数据库探测的就绪检查 |

`asOf` 参数也兼容下划线写法 `as_of`。

### 事件类型（payload 字段）

| eventType | 关键字段 |
| --- | --- |
| `teacher.registered` | `teacherId`, `name` |
| `practice.recorded` | `teacherId`, `startDate`, `endDate`（闭区间），`enterprise` |
| `cert.issued` | `teacherId`, `certId`, `certType`, `issuedOn`, `expiresOn` |
| `cert.revoked` | `teacherId`, `certId`, `effectiveOn`（吊销生效日，可早于事件到达顺序） |
| `project.role.assigned` | `teacherId`, `projectId`, `role`, `enterprise` |
| `project.accepted` | `teacherId`, `projectId`, `acceptedOn`（企业验收日） |
| `matrix.published` | `version`, `courseCode`, `effectiveFrom`, `requiredPracticeDays`, `requiredCertTypes[]`, `requiredAcceptedProjects` |

每个事件信封包含：`eventId`（UUID，幂等键）、`eventType`、`occurredAt`（业务发生时间，
支持 `yyyy-MM-dd` 或 ISO-8601 时刻）、`payload`。

## 5. 资格判定规则（基准日 as_of）

1. **能力矩阵**：取 `effectiveFrom <= as_of` 的最新版本；没有生效矩阵则直接不通过。
2. **企业实践**：只统计业务时间不晚于 `as_of` 的事件；未开始的区间丢弃，跨基准日的区间截断；
   重叠/相邻区间合并后计天（**重叠只算一次**，闭区间含首尾）；天数 ≥ 矩阵要求即通过。
3. **设备认证**：基准日当天必须已签发、未到期（**到期日当天仍有效**）、
   且未被已生效的吊销覆盖（**吊销生效日当天即失效**；吊销事件乱序/先到达时，
   仍按 `effectiveOn` 业务时间判断）。
4. **产业项目**：只有持有企业验收记录且 `acceptedOn <= as_of` 的项目才算证据；
   尚未验收或验收日在基准日之后的不计入。
5. 每项结论带 `status`(PASS/FAIL)、`message`、`evidenceIds`（事件/证件 ID）和 `missing`（还缺什么）。
6. 每次评估写入 `decisions` 表：`(teacherId, courseCode, asOf)` 有唯一约束，
   并发或重复请求返回同一份 `decision_id`；**后续补录的事件不会改变已落库的快照**
   （新事实只影响之后对新基准日的查询）。

## 6. T1 验证示例

T1 有两段重叠实践（2025-01-01~2025-03-01 与 2025-02-15~2025-04-01，合并 91 天）；
设备认证 C1 于 2026-06-30 到期；一条先到达的吊销事件实际自 2026-05-01 起生效。

```bash
# 1) 注册教师
curl -X POST localhost:8080/api/v1/events -H 'Content-Type: application/json' -d '{
  "eventId":"11111111-1111-4111-8111-111111111111","eventType":"teacher.registered",
  "occurredAt":"2024-12-01","teacherId":"T1",
  "payload":{"teacherId":"T1","name":"张老师"}}'

# 2) 发布能力矩阵（90 天实践 + EQUIPMENT 认证 + 1 个验收项目）
curl -X POST localhost:8080/api/v1/events -H 'Content-Type: application/json' -d '{
  "eventId":"88888888-8888-4888-8888-888888888888","eventType":"matrix.published",
  "occurredAt":"2024-12-15T00:00:00Z",
  "payload":{"version":"v1","courseCode":"HRP-101","effectiveFrom":"2025-01-01",
             "requiredPracticeDays":90,"requiredCertTypes":["EQUIPMENT"],"requiredAcceptedProjects":1}}'

# 3) 两段重叠企业实践
curl -X POST localhost:8080/api/v1/events -H 'Content-Type: application/json' -d '{
  "eventId":"22222222-2222-4222-8222-222222222222","eventType":"practice.recorded",
  "occurredAt":"2025-03-02T09:00:00Z","teacherId":"T1",
  "payload":{"teacherId":"T1","startDate":"2025-01-01","endDate":"2025-03-01","enterprise":"华东数控有限公司"}}'
curl -X POST localhost:8080/api/v1/events -H 'Content-Type: application/json' -d '{
  "eventId":"33333333-3333-4333-8333-333333333333","eventType":"practice.recorded",
  "occurredAt":"2025-04-02T09:00:00Z","teacherId":"T1",
  "payload":{"teacherId":"T1","startDate":"2025-02-15","endDate":"2025-04-01","enterprise":"华东数控有限公司"}}'

# 4) 吊销事件先于签发事件录入（乱序到达），实际 2026-05-01 生效
curl -X POST localhost:8080/api/v1/events -H 'Content-Type: application/json' -d '{
  "eventId":"55555555-5555-4555-8555-555555555555","eventType":"cert.revoked",
  "occurredAt":"2026-04-20T08:00:00Z","teacherId":"T1",
  "payload":{"teacherId":"T1","certId":"C1","effectiveOn":"2026-05-01","reason":"设备安全抽查不合格"}}'

# 5) 证书签发（2025-06-01 生效，2026-06-30 到期）
curl -X POST localhost:8080/api/v1/events -H 'Content-Type: application/json' -d '{
  "eventId":"44444444-4444-4444-8444-444444444444","eventType":"cert.issued",
  "occurredAt":"2025-06-01T10:00:00Z","teacherId":"T1",
  "payload":{"teacherId":"T1","certId":"C1","certType":"EQUIPMENT",
             "issuedOn":"2025-06-01","expiresOn":"2026-06-30","issuingBody":"装备制造行业协会"}}'

# 6) 产业项目角色 + 企业验收
curl -X POST localhost:8080/api/v1/events -H 'Content-Type: application/json' -d '{
  "eventId":"66666666-6666-4666-8666-666666666666","eventType":"project.role.assigned",
  "occurredAt":"2025-08-01T09:00:00Z","teacherId":"T1",
  "payload":{"teacherId":"T1","projectId":"P1","role":"技术顾问","enterprise":"华东数控有限公司"}}'
curl -X POST localhost:8080/api/v1/events -H 'Content-Type: application/json' -d '{
  "eventId":"77777777-7777-4777-8777-777777777777","eventType":"project.accepted",
  "occurredAt":"2025-09-02T10:00:00Z","teacherId":"T1",
  "payload":{"teacherId":"T1","projectId":"P1","acceptedOn":"2025-09-01"}}'

# 7) 三个基准日查询
curl 'localhost:8080/api/v1/teachers/T1/qualification?course=HRP-101&asOf=2026-04-30'
#   -> QUALIFIED：吊销 5/1 才生效，证书 6/30 才到期，实践合并 91 天
curl 'localhost:8080/api/v1/teachers/T1/qualification?course=HRP-101&asOf=2026-05-01'
#   -> NOT_QUALIFIED：CERT_EQUIPMENT FAIL，证据同时含签发(4444...)与吊销(5555...)事件
curl 'localhost:8080/api/v1/teachers/T1/qualification?course=HRP-101&asOf=2026-07-01'
#   -> NOT_QUALIFIED：证书已过 2026-06-30 到期日且已吊销
```

健康检查：

```bash
curl localhost:8080/health        # {"status":"UP"}
curl localhost:8080/health/ready  # {"status":"UP","database":"UP"}
```

## 7. 目录结构

```
src/main/kotlin/com/gsb/dualteacher/
  Application.kt              # Ktor 装配（插件、错误处理、日志）
  config/DatabaseConfig.kt    # DATABASE_URL 解析、Flyway 迁移、Hikari 连接池
  domain/Models.kt            # 事件/投影/快照领域模型
  domain/DateRanges.kt        # 日期区间合并与 as-of 截断（纯函数）
  domain/QualificationEvaluator.kt  # 业务时间投影 + 逐项资格判定（纯函数）
  db/Tables.kt                # Exposed 表定义、唯一冲突识别
  db/EventRepository.kt       # 事件幂等写入与读取
  db/DecisionRepository.kt    # 决定快照唯一写入/读取
  service/EventService.kt     # 事件接入校验
  service/QualificationService.kt   # 评估 + 快照保存
  web/                        # 事件/资格/决定/健康/OpenAPI 路由
src/main/resources/
  db/migration/V1__init.sql   # Flyway 迁移（events / decisions）
  openapi/openapi.json        # OpenAPI 3 规范
  logback.xml                 # JSON 结构化日志
src/test/kotlin/...           # 单元测试（domain）+ 集成测试（integration，H2）
```
