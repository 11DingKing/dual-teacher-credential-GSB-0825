package com.example.dualteacher.service

import com.example.dualteacher.db.CertificationRevocations
import com.example.dualteacher.db.Certifications
import com.example.dualteacher.db.CourseMatrices
import com.example.dualteacher.db.Decisions
import com.example.dualteacher.db.EnterprisePractices
import com.example.dualteacher.db.IndustryProjects
import com.example.dualteacher.db.Teachers
import com.example.dualteacher.domain.DecisionResponse
import com.example.dualteacher.domain.PortfolioResponse
import com.example.dualteacher.domain.PracticeEvidence
import com.example.dualteacher.domain.CertificationEvidence
import com.example.dualteacher.domain.RevocationEvidence
import com.example.dualteacher.domain.ProjectEvidence
import io.ktor.server.plugins.NotFoundException
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/**
 * 资格判定服务。
 *
 * 判定语义：decide = get-or-create。
 * - 以 (teacher_id, course_id, as_of) 为唯一键保存不可变快照；
 * - 快照一旦写入，之后补录的材料（哪怕是业务时间更早的吊销）不会回溯修改旧决定；
 * - 并发创建同一决定时由数据库唯一约束仲裁，所有请求拿到同一个 decision_id。
 */
class QualificationService(private val db: Database) {

    fun decide(teacherId: String, courseId: String, asOf: LocalDate): DecisionResponse = transaction(db) {
        findExisting(teacherId, courseId, asOf)?.let { return@transaction it }

        if (!teacherExists(teacherId)) {
            throw NotFoundException("teacher $teacherId not found")
        }

        // 选择在 as_of 当天已生效的最新版本矩阵（版本号最大者）
        val matrix = CourseMatrices.selectAll()
            .where { (CourseMatrices.courseId eq courseId) and (CourseMatrices.effectiveFrom lessEq asOf) }
            .orderBy(CourseMatrices.version to SortOrder.DESC, CourseMatrices.effectiveFrom to SortOrder.DESC)
            .limit(1)
            .firstOrNull()
            ?: throw NotFoundException("no capability matrix for course $courseId effective at $asOf")
        val requirements = matrix[CourseMatrices.requirements]

        val practices = EnterprisePractices.selectAll()
            .where { EnterprisePractices.teacherId eq teacherId }
            .map { Practice(it[EnterprisePractices.id], it[EnterprisePractices.startDate], it[EnterprisePractices.endDate]) }

        val certifications = Certifications.selectAll()
            .where { Certifications.teacherId eq teacherId }
            .map { Cert(it[Certifications.id], it[Certifications.certCode], it[Certifications.issuedDate], it[Certifications.expiryDate]) }

        // 吊销按 cert_code 关联：覆盖该教师持有的认证与矩阵要求的认证
        val relevantCodes = (certifications.map { it.code } + requirements.requiredCertifications).toSet()
        val revocations = if (relevantCodes.isEmpty()) {
            emptyList()
        } else {
            CertificationRevocations.selectAll()
                .where { CertificationRevocations.certCode inList relevantCodes }
                .map { Revocation(it[CertificationRevocations.id], it[CertificationRevocations.certCode], it[CertificationRevocations.effectiveDate]) }
        }

        val projects = IndustryProjects.selectAll()
            .where { IndustryProjects.teacherId eq teacherId }
            .map { Project(it[IndustryProjects.id], it[IndustryProjects.role], it[IndustryProjects.acceptedByEnterprise], it[IndustryProjects.acceptanceDate]) }

        val items = QualificationEvaluator.evaluate(requirements, practices, certifications, revocations, projects, asOf)
        val qualified = items.all { it.status == "PASS" }

        val decisionId = UUID.randomUUID()
        val inserted = Decisions.insertIgnore {
            it[Decisions.decisionId] = decisionId
            it[Decisions.teacherId] = teacherId
            it[Decisions.courseId] = courseId
            it[Decisions.asOf] = asOf
            it[Decisions.matrixVersion] = matrix[CourseMatrices.version]
            it[Decisions.qualified] = qualified
            it[Decisions.items] = items
            it[Decisions.createdAt] = OffsetDateTime.now()
        }
        if (inserted.insertedCount == 0) {
            // 并发请求已创建同一决定：ON CONFLICT 会等待对方事务提交，此处直接读取其不可变结果
            return@transaction findExisting(teacherId, courseId, asOf)
                ?: error("concurrent decision insert conflict but row not visible")
        }

        DecisionResponse(
            decisionId = decisionId.toString(),
            teacherId = teacherId,
            courseId = courseId,
            asOf = asOf,
            matrixVersion = matrix[CourseMatrices.version],
            qualified = qualified,
            items = items,
            createdAt = OffsetDateTime.now().toString(),
            created = true,
        )
    }

    fun getDecision(decisionId: UUID): DecisionResponse = transaction(db) {
        Decisions.selectAll()
            .where { Decisions.decisionId eq decisionId }
            .firstOrNull()
            ?.toResponse(created = false)
            ?: throw NotFoundException("decision $decisionId not found")
    }

    fun getPortfolio(teacherId: String): PortfolioResponse = transaction(db) {
        if (!teacherExists(teacherId)) {
            throw NotFoundException("teacher $teacherId not found")
        }
        PortfolioResponse(
            teacherId = teacherId,
            practices = EnterprisePractices.selectAll()
                .where { EnterprisePractices.teacherId eq teacherId }
                .orderBy(EnterprisePractices.startDate to SortOrder.ASC)
                .map {
                    PracticeEvidence(
                        id = it[EnterprisePractices.id].toString(),
                        eventId = it[EnterprisePractices.eventId],
                        company = it[EnterprisePractices.company],
                        startDate = it[EnterprisePractices.startDate],
                        endDate = it[EnterprisePractices.endDate],
                    )
                },
            certifications = Certifications.selectAll()
                .where { Certifications.teacherId eq teacherId }
                .map {
                    CertificationEvidence(
                        id = it[Certifications.id].toString(),
                        eventId = it[Certifications.eventId],
                        certCode = it[Certifications.certCode],
                        issuedDate = it[Certifications.issuedDate],
                        expiryDate = it[Certifications.expiryDate],
                    )
                },
            revocations = CertificationRevocations.selectAll()
                .where {
                    CertificationRevocations.certCode inList (
                        Certifications.select(Certifications.certCode)
                            .where { Certifications.teacherId eq teacherId }
                            .map { it[Certifications.certCode] }
                            .ifEmpty { listOf("") }
                        )
                }
                .map {
                    RevocationEvidence(
                        id = it[CertificationRevocations.id].toString(),
                        eventId = it[CertificationRevocations.eventId],
                        certCode = it[CertificationRevocations.certCode],
                        effectiveDate = it[CertificationRevocations.effectiveDate],
                        reason = it[CertificationRevocations.reason],
                    )
                },
            projects = IndustryProjects.selectAll()
                .where { IndustryProjects.teacherId eq teacherId }
                .map {
                    ProjectEvidence(
                        id = it[IndustryProjects.id].toString(),
                        eventId = it[IndustryProjects.eventId],
                        projectName = it[IndustryProjects.projectName],
                        role = it[IndustryProjects.role],
                        acceptedByEnterprise = it[IndustryProjects.acceptedByEnterprise],
                        acceptanceDate = it[IndustryProjects.acceptanceDate],
                    )
                },
        )
    }

    private fun findExisting(teacherId: String, courseId: String, asOf: LocalDate): DecisionResponse? =
        Decisions.selectAll()
            .where {
                (Decisions.teacherId eq teacherId) and
                    (Decisions.courseId eq courseId) and
                    (Decisions.asOf eq asOf)
            }
            .firstOrNull()
            ?.toResponse(created = false)

    private fun teacherExists(teacherId: String): Boolean =
        Teachers.select(Teachers.teacherId).where { Teachers.teacherId eq teacherId }.count() > 0

    private fun ResultRow.toResponse(created: Boolean) = DecisionResponse(
        decisionId = this[Decisions.decisionId].toString(),
        teacherId = this[Decisions.teacherId],
        courseId = this[Decisions.courseId],
        asOf = this[Decisions.asOf],
        matrixVersion = this[Decisions.matrixVersion],
        qualified = this[Decisions.qualified],
        items = this[Decisions.items],
        createdAt = this[Decisions.createdAt].toString(),
        created = created,
    )
}
