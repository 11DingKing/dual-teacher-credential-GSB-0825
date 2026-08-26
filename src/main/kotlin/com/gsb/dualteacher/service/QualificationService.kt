package com.gsb.dualteacher.service

import com.gsb.dualteacher.db.DecisionRepository
import com.gsb.dualteacher.db.EventRepository
import com.gsb.dualteacher.domain.DecisionSnapshot
import com.gsb.dualteacher.domain.EventTypes
import com.gsb.dualteacher.domain.QualificationEvaluator
import kotlinx.serialization.json.decodeFromJsonElement
import org.slf4j.MDC
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class TeacherNotFoundException(teacherId: String) :
    RuntimeException("教师 $teacherId 不存在（缺少 teacher.registered 事件）")

class QualificationService(
    private val eventRepository: EventRepository,
    private val decisionRepository: DecisionRepository,
) {

    /**
     * 以 asOf 为判断基准评估教师对某课程的授课资格，并保存不可变快照。
     * 同一 (教师, 课程, asOf) 的并发/重复调用返回同一份 decision_id 快照；
     * 快照落库后，后续补录的事件不会改变它。
     */
    suspend fun evaluate(teacherId: String, courseCode: String, asOf: LocalDate): DecisionSnapshot {
        val events = eventRepository.findForTeacher(teacherId)
        val registered = events.any {
            it.eventType == EventTypes.TEACHER_REGISTERED &&
                QualificationEvaluator.json.decodeFromJsonElement<com.gsb.dualteacher.domain.TeacherRegisteredPayload>(it.payload)
                    .teacherId == teacherId
        }
        if (!registered) throw TeacherNotFoundException(teacherId)

        val state = QualificationEvaluator.project(events, asOf)
        val evaluation = QualificationEvaluator.evaluate(state, teacherId, courseCode, asOf)

        val candidate = DecisionSnapshot(
            decisionId = UUID.randomUUID().toString(),
            teacherId = teacherId,
            courseCode = courseCode,
            asOf = asOf.toString(),
            matrixVersion = evaluation.matrixVersion,
            result = evaluation.result,
            items = evaluation.items,
            evaluatedAt = Instant.now().toString(),
        )
        val stored = decisionRepository.saveOrGet(candidate)
        MDC.put("decisionId", stored.decisionId)
        return stored
    }

    suspend fun getDecision(decisionId: UUID): DecisionSnapshot? =
        decisionRepository.findById(decisionId)
}
