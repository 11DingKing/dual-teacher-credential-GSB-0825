package com.dualteacher

import com.dualteacher.persistence.DecisionRepository
import com.dualteacher.persistence.EventStore
import com.dualteacher.service.EventProjector
import com.dualteacher.service.QualificationEvaluator
import com.dualteacher.service.QualificationService
import org.koin.dsl.module

val appModule = module {
    single { EventStore() }
    single { DecisionRepository() }
    single { EventProjector() }
    single { QualificationEvaluator() }
    single {
        QualificationService(
            eventStore = get(),
            projector = get(),
            evaluator = get(),
            decisionRepository = get()
        )
    }
}
