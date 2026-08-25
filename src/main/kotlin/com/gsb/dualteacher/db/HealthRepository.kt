package com.gsb.dualteacher.db

import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

class HealthRepository {
    suspend fun ping(): Boolean = newSuspendedTransaction {
        val connection = connection
        connection.prepareStatement("SELECT 1", false).executeQuery().use { rs ->
            rs.next() && rs.getInt(1) == 1
        }
    }
}
