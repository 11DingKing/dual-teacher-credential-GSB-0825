package com.dualteacher.service

import com.dualteacher.domain.EventProjector
import com.dualteacher.domain.EventEnvelope
import com.dualteacher.domain.QualificationDecision
import com.dualteacher.domain.QualificationEvaluator
import com.dualteacher.repo.DecisionRepository
import com.dualteacher.repo.EventStore
import java.time.Clock
import java.time.LocalDate

/**
 * Orchestrates a qualification query: replay events in business-time order, project state,
 * evaluate against the matrix effective at `as_of`, and persist an immutable decision
 * snapshot keyed deterministically by (teacher, course, as_of).
 */
class QualificationService(
    private val events: EventStore,
    private val decisions: DecisionRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    /** Ingests a source event. Idempotent on event_id; @return true if newly stored. */
    fun append(env: EventEnvelope): Boolean = events.append(env)

    fun evaluate(teacherId: String, courseId: String, asOf: LocalDate): QualificationDecision {
        val decisionId = decisions.decisionIdFor(teacherId, courseId, asOf)

        // If a snapshot already exists for this coordinate, return it unchanged — later
        // material must not alter a past decision.
        decisions.findById(decisionId)?.let { return it }

        val ordered = events.loadFor(teacherId, courseId)
        val state = EventProjector.project(teacherId, courseId, ordered)
        val (qualified, outcome) = QualificationEvaluator.evaluate(courseId, asOf, state)

        val fresh = QualificationDecision(
            decisionId = decisionId,
            teacherId = teacherId,
            courseId = courseId,
            asOf = asOf,
            qualified = qualified,
            matrixVersion = outcome.matrixVersion,
            checks = outcome.checks,
            decidedAt = clock.instant(),
        )
        // saveIfAbsent resolves races: the first writer wins and every caller gets that row.
        return decisions.saveIfAbsent(fresh)
    }

    fun getDecision(decisionId: String): QualificationDecision? = decisions.findById(decisionId)
}
