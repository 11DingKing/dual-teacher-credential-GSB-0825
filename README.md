# 双师型教师资历服务（dual-teacher-credential）

面向教务排课场景的“双师型”教师授课资格判定服务。以**事件溯源**方式记录教师、企业实践区间、
认证签发/吊销、产业项目角色与带版本的课程能力矩阵；排课人员指定 `as_of` 日期即可查询某位教师
是否真正具备授课资格，并看到每一项通过/不通过的证据与缺口。每次判定以 `decision_id` 保存
**不可变快照**——之后补录的材料不会回溯修改旧决定。

## 技术栈

Kotlin 2.1 · JDK 21 · Ktor 3 · Exposed · PostgreSQL 16 · Flyway（迁移）· Gradle Wrapper ·
Logback + logstash-encoder（结构化 JSON 日志）· JUnit 5 + Ktor TestHost

## 业务口径

| 主题 | 规则 |
| --- | --- |
| 企业实践 | 区间起止日均计入；重叠/相接区间合并后只算一次；只累计 `as_of` 当天及之前的部分 |
| 认证 | `as_of` 当天须已签发、未到期（到期日当天仍有效），且不存在 `effective_date <= as_of` 的吊销 |
| 产业项目 | 必须 `accepted_by_enterprise = true` 且验收日期不晚于 `as_of` 才算证据 |
| 矩阵版本 | 取 `effective_from <= as_of` 中版本号最大者 |
| 事件幂等 | `event_id` 全局唯一；重复提交返回 `duplicate`，投影不重复写入 |
| 乱序事件 | 吊销可先于签发到达、矩阵可乱序发布；判定只依据业务时间，与到达顺序无关 |
| 决定快照 | `(teacher_id, course_id, as_of)` 唯一；get-or-create；并发创建由唯一约束仲裁出同一 `decision_id` |

## 前置条件

- JDK 21（构建用了 toolchain；若本机没有 JDK 21，Gradle 会通过 foojay-resolver 自动下载）
- PostgreSQL 16（本地或远程均可，不需要 Docker）

## 本地数据库初始化

```bash
# macOS（Homebrew）安装并启动 PostgreSQL 16
brew install postgresql@16
brew services start postgresql@16

# 建库（应用库 + 集成测试专用库）
createdb dualteacher
createdb dualteacher_test
```

如需指定账号密码，可自行建用户：

```sql
CREATE USER app WITH PASSWORD 'secret';
GRANT ALL PRIVILEGES ON DATABASE dualteacher TO app;
```

`DATABASE_URL` 支持两种形式，凭据也可放在 URL 里或用 `DATABASE_USER` / `DATABASE_PASSWORD`：

```bash
# 形式一：JDBC URL
export DATABASE_URL='jdbc:postgresql://localhost:5432/dualteacher'
# 形式二：Heroku 风格（自动转换）
export DATABASE_URL='postgresql://app:secret@localhost:5432/dualteacher'
```

未设置 `DATABASE_URL` 时默认 `jdbc:postgresql://localhost:5432/dualteacher`。
首次启动会自动执行 Flyway 迁移（`src/main/resources/db/migration/V1__init.sql`）。

## 构建、测试与启动

```bash
./gradlew test     # 单元测试始终运行；集成测试在设置 DATABASE_URL 时启用
./gradlew build    # 完整构建（含 test）
DATABASE_URL='jdbc:postgresql://localhost:5432/dualteacher' ./gradlew run
```

> 注意：集成测试会对 `DATABASE_URL` 指向的库执行 `TRUNCATE`，**请务必指向专用测试库**，例如
> `DATABASE_URL='jdbc:postgresql://localhost:5432/dualteacher_test' ./gradlew test`。
> 未设置 `DATABASE_URL` 时集成测试自动跳过（单元测试不受影响）。

服务默认监听 `8080`（可用 `PORT` 环境变量修改）：

- `GET /health` 健康检查（含数据库连通性）
- `POST /events` 摄入来源事件（幂等）
- `GET /teachers/{teacherId}/qualifications?course_id=...&as_of=YYYY-MM-DD` 资格判定（get-or-create 快照）
- `GET /teachers/{teacherId}/portfolio` 教师资历档案（证据清单，含 `event_id` 溯源）
- `GET /decisions/{decisionId}` 读取不可变判定快照
- `GET /swagger` Swagger UI；`GET /openapi.yaml` OpenAPI 规范

## T1 验证脚本

启动服务后执行（注意吊销事件 e-004 **先于** 签发事件 e-005 到达）：

```bash
B=http://localhost:8080
post() { curl -s -X POST $B/events -H 'Content-Type: application/json' -d "$1"; echo; }

post '{"event_id":"e-001","event_type":"TEACHER_REGISTERED","payload":{"teacher_id":"T1","name":"张三"}}'
post '{"event_id":"e-002","event_type":"ENTERPRISE_PRACTICE_RECORDED","payload":{"teacher_id":"T1","company":"ACME","start_date":"2025-01-01","end_date":"2025-03-01"}}'
post '{"event_id":"e-003","event_type":"ENTERPRISE_PRACTICE_RECORDED","payload":{"teacher_id":"T1","company":"ACME","start_date":"2025-02-15","end_date":"2025-04-01"}}'
post '{"event_id":"e-004","event_type":"CERTIFICATION_REVOKED","payload":{"cert_code":"C1","effective_date":"2026-05-01","reason":"annual audit"}}'
post '{"event_id":"e-005","event_type":"CERTIFICATION_ISSUED","payload":{"teacher_id":"T1","cert_code":"C1","issued_date":"2025-06-01","expiry_date":"2026-06-30"}}'
post '{"event_id":"e-006","event_type":"INDUSTRY_PROJECT_RECORDED","payload":{"teacher_id":"T1","project_name":"P-100","role":"MENTOR","accepted_by_enterprise":true,"acceptance_date":"2025-09-01"}}'
post '{"event_id":"e-007","event_type":"COURSE_MATRIX_PUBLISHED","payload":{"course_id":"COURSE-1","version":1,"effective_from":"2025-01-01","requirements":{"min_practice_days":90,"required_certifications":["C1"],"required_project_roles":["MENTOR"]}}}'

curl -s "$B/teachers/T1/qualifications?course_id=COURSE-1&as_of=2026-04-30"
curl -s "$B/teachers/T1/qualifications?course_id=COURSE-1&as_of=2026-05-01"
curl -s "$B/teachers/T1/qualifications?course_id=COURSE-1&as_of=2026-07-01"
```

预期结果：

| as_of | qualified | 说明 |
| --- | --- | --- |
| 2026-04-30 | `true` | 实践合并为 2025-01-01..2025-04-01 共 **91 天**（重叠只算一次）；吊销尚未生效 |
| 2026-05-01 | `false` | 认证项 `FAIL`，`reason_code=CERT_REVOKED`，证据含吊销记录 ID |
| 2026-07-01 | `false` | 认证项 `FAIL`，`reason_code=CERT_EXPIRED`（2026-06-30 到期，且已被吊销） |

同一 `as_of` 再次查询返回相同 `decision_id` 且 `created=false`；即使之后补录业务时间更早的吊销，
旧决定也不会改变（新的 `as_of` 才会反映补录材料）。

## 事件类型一览

| event_type | payload 关键字段 |
| --- | --- |
| `TEACHER_REGISTERED` | `teacher_id`, `name` |
| `ENTERPRISE_PRACTICE_RECORDED` | `teacher_id`, `company`, `start_date`, `end_date` |
| `CERTIFICATION_ISSUED` | `teacher_id`, `cert_code`, `issued_date`, `expiry_date?` |
| `CERTIFICATION_REVOKED` | `cert_code`, `effective_date`, `reason?`（无需等待签发，可先到达） |
| `INDUSTRY_PROJECT_RECORDED` | `teacher_id`, `project_name`, `role`, `accepted_by_enterprise`, `acceptance_date?` |
| `COURSE_MATRIX_PUBLISHED` | `course_id`, `version`, `effective_from`, `requirements{min_practice_days, required_certifications[], required_project_roles[]}` |

## 测试覆盖

- 单元（无需数据库）：区间合并（重叠/相接/包含/乱序输入）、实践天数边界（89/90/91 天、as_of 裁剪）、
  认证签发/到期/吊销各边界、乱序吊销、项目验收口径、T1 三日期时间线、DATABASE_URL 解析
- 集成（需 `DATABASE_URL`）：T1 端到端 HTTP 场景、事件幂等与冲突载荷、矩阵版本乱序发布与 404、
  16 路并发创建同一决定（同一 `decision_id`、恰好一行快照）、补录吊销后旧快照不可变、
  健康检查 / OpenAPI / 参数校验冒烟

## 目录结构

```
src/main/kotlin/com/example/dualteacher/
  Application.kt                 # 入口与模块装配（CIO、ContentNegotiation、CallLogging、StatusPages、Swagger）
  config/AppConfig.kt            # 环境变量与 DATABASE_URL 解析
  config/DatabaseFactory.kt      # Flyway 迁移 + Hikari 连接池
  db/Tables.kt                   # Exposed 表定义（events/投影/decisions）
  domain/Models.kt               # 事件载荷、DTO、序列化器
  service/IntervalMerger.kt      # 区间合并（纯函数）
  service/QualificationEvaluator.kt  # 资格评估（纯函数，可单测）
  service/IngestionService.kt    # 事件摄入：幂等 + 同事务投影
  service/QualificationService.kt    # 判定 get-or-create、不可变快照、档案查询
  routes/Routes.kt               # 路由与错误映射
src/main/resources/
  db/migration/V1__init.sql      # schema 迁移
  openapi/openapi.yaml           # OpenAPI 3.0 规范
  logback.xml                    # 结构化 JSON 日志
src/test/                        # 单元测试 + 集成测试（DATABASE_URL 门控）
```
