# 双师型教师资历服务 (Dual-Teacher Credential Service)

基于事件溯源的教师授课资格判定服务。排课人员可以指定日期（`as_of`）查询某位教师是否具备某门高风险实践课的授课资格，并查看每一项要求的通过/不通过证据。

## 技术栈

| 组件 | 版本 |
|------|------|
| Kotlin | 2.0.21 |
| JDK | 21 |
| Ktor | 3.0.1 (Netty) |
| Exposed | 0.53.0 |
| PostgreSQL | 16 |
| Flyway | 10.x（数据库迁移） |
| Gradle Wrapper | 8.10.2 |

## 前置条件

- **JDK 21**（确保 `JAVA_HOME` 指向 JDK 21）
- **PostgreSQL 16** 本地实例或可连接的远程实例
- Docker（仅运行集成测试时需要，用于启动测试数据库容器）

## 本地数据库初始化

### 方式一：本地 PostgreSQL

```bash
# 创建数据库和用户
psql -U postgres -c "CREATE USER dualteacher WITH PASSWORD 'dualteacher';"
psql -U postgres -c "CREATE DATABASE dualteacher OWNER dualteacher;"
psql -U postgres -c "GRANT ALL PRIVILEGES ON DATABASE dualteacher TO dualteacher;"
```

数据库表结构由 Flyway 在应用启动时自动迁移（`src/main/resources/db/migration/V1__init.sql`），无需手动建表。

### 方式二：使用标准 DATABASE_URL

```bash
export DATABASE_URL="postgresql://user:password@localhost:5432/dualteacher"
```

应用同时支持 `DATABASE_URL`（标准格式）和 `JDBC_DATABASE_URL`（JDBC 格式）。

## 构建与启动

```bash
# 确保使用 JDK 21
export JAVA_HOME=$(/usr/libexec/java_home -v 21)

# 构建（不运行测试）
./gradlew build -x test

# 启动服务（使用默认本地数据库）
./gradlew run

# 或指定 DATABASE_URL 启动
DATABASE_URL="postgresql://user:pass@localhost:5432/dualteacher" ./gradlew run
```

服务启动后监听 `http://localhost:8080`。

- 健康检查：`GET /health`
- Swagger UI：`http://localhost:8080/swagger`

## 运行测试

```bash
# 运行全部测试（单元测试 + 集成测试）
./gradlew test

# 仅运行单元测试
./gradlew test --tests "com.dualteacher.unit.*"

# 仅运行集成测试（需要 Docker）
./gradlew test --tests "com.dualteacher.integration.*"
```

测试覆盖：
- **单元测试**（34 个）：区间合并、日期边界、认证有效期、乱序吊销投影、矩阵版本选择
- **集成测试**（15 个）：事件幂等写入、并发决定创建、快照不可变、HTTP API 端到端、T1 完整时间线场景

## API 概览

### POST /api/v1/events — 接入来源事件（幂等）

每个事件拥有全局唯一 `event_id`，重复提交返回 `200 OK` 而非 `201 Created`。
事件按 `occurred_at`（业务发生时间）投影，而非服务器接收时间，因此乱序到达不影响判定结果。

```json
{
  "eventId": "evt-001",
  "eventType": "CERTIFICATION_ISSUED",
  "aggregateId": "T1",
  "occurredAt": "2025-06-15",
  "payload": {
    "certificationId": "C1",
    "certType": "EQUIPMENT_OPERATION",
    "expiryDate": "2026-06-30",
    "issuer": "国家装备制造认证中心"
  }
}
```

### GET /api/v1/teachers/{teacherId}/qualification — 查询资格

```
GET /api/v1/teachers/T1/qualification?course_id=COURSE_HIGH_RISK&as_of=2026-04-30
```

返回逐项结论、证据事件 ID 和缺失项，并以 `decision_id` 保存不可变快照。

### GET /api/v1/decisions/{decisionId} — 获取历史决定快照

快照一经创建不可修改，后续补录的材料不会影响旧决定。

## 事件类型

| eventType | aggregateId | payload 关键字段 |
|-----------|-------------|-----------------|
| `TEACHER_CREATED` | 教师 ID | name |
| `PRACTICE_RECORDED` | 教师 ID | intervalId, startDate, endDate, company |
| `CERTIFICATION_ISSUED` | 教师 ID | certificationId, certType, expiryDate, issuer |
| `CERTIFICATION_REVOKED` | 教师 ID | certificationId, reason |
| `PROJECT_RECORDED` | 教师 ID | projectId, projectName, role, company |
| `PROJECT_ACCEPTED` | 教师 ID | projectId |
| `MATRIX_PUBLISHED` | 课程 ID | matrixId, courseId, version, requirements |

## 业务规则

1. **企业实践时长**：重叠的实践区间合并后计算天数（含首尾），区间在 `as_of` 当天仍有效则计入
2. **认证有效性**：签发日 ≤ `as_of` ≤ 到期日，且没有生效日 ≤ `as_of` 的吊销记录
3. **产业项目**：必须有企业验收记录（`PROJECT_ACCEPTED` 事件），验收日 ≤ `as_of`
4. **能力矩阵**：取 `effective_date ≤ as_of` 的最新版本
5. **不可变快照**：同一 (教师, 课程, as_of) 只生成一个决定，并发请求通过唯一约束保证安全

## T1 验证场景

教师 T1 拥有：
- 两段重叠企业实践：2025-01-01~2025-03-01 和 2025-02-15~2025-04-01（合并后 91 天）
- 认证 C1，2026-06-30 到期；一条吊销事件 `occurred_at = 2026-05-01`（先到达、后生效）
- 1 个已验收产业项目

| as_of | 预期结果 | 原因 |
|-------|---------|------|
| 2026-04-30 | **PASS** | 认证未过期、吊销尚未生效 |
| 2026-05-01 | **FAIL** | 吊销于当日生效 |
| 2026-07-01 | **FAIL** | 认证已过期且已被吊销 |

对应测试见 `T1QualificationScenarioTest`。
