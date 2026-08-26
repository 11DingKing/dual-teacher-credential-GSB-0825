package com.dualteacher.service

import com.dualteacher.domain.QualificationResult
import com.dualteacher.persistence.DecisionRepository
import com.dualteacher.persistence.EventStore
import com.dualteacher.persistence.StoredDecision
import java.time.LocalDate
import java.util.UUID

class QualificationService(
    private val eventStore: EventStore,
    private val projector: EventProjector,
    private val evaluator: QualificationEvaluator,
    private val decisionRepository: DecisionRepository
) {

    suspend fun queryQualification(
        teacherId: String,
        courseId: String,
        asOf: LocalDate
    ): StoredDecision {
        val existing = decisionRepository.findByScope(teacherId, courseId, asOf)
        if (existing != null) {
            return existing
        }

        val teacherEvents = eventStore.loadEventsForTeacher(teacherId, asOf)
        val courseEvents = eventStore.loadEventsForCourse(courseId, asOf)

        val teacherState = projector.projectTeacherEvents(teacherId, teacherEvents)
        val courseState = projector.projectTeacherEvents(courseId, courseEvents)

        val result = evaluator.evaluate(
            teacherState = teacherState,
            courseMatrices = courseState.matrices,
            teacherId = teacherId,
            courseId = courseId,
            asOf = asOf
        )

        return decisionRepository.createOrGet(result)
    }

    suspend fun getDecision(decisionId: UUID): StoredDecision? {
        return decisionRepository.findById(decisionId)
    }
}
