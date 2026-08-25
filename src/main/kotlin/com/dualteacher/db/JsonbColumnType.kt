package com.dualteacher.db

import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.ColumnType
import org.jetbrains.exposed.sql.Table
import org.postgresql.util.PGobject

/** Exposed column type that reads/writes a PostgreSQL `jsonb` column as a raw JSON string. */
class JsonbColumnType : ColumnType<String>() {
    override fun sqlType(): String = "jsonb"

    override fun valueFromDB(value: Any): String = when (value) {
        is PGobject -> value.value ?: ""
        is String -> value
        else -> value.toString()
    }

    override fun notNullValueToDB(value: String): Any = PGobject().apply {
        type = "jsonb"
        this.value = value
    }
}

/** Declares a non-null `jsonb` column bound as a JSON string. */
fun Table.jsonb(name: String): Column<String> = registerColumn(name, JsonbColumnType())
