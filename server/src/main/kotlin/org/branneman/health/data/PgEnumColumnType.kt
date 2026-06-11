package org.branneman.health.data

import org.jetbrains.exposed.sql.ColumnType
import org.postgresql.util.PGobject

class PgEnumColumnType(private val enumTypeName: String) : ColumnType<String>() {
    override fun sqlType(): String = enumTypeName

    override fun valueFromDB(value: Any): String = when (value) {
        is PGobject -> value.value ?: ""
        else        -> value.toString()
    }

    override fun notNullValueToDB(value: String): Any = PGobject().apply {
        type = enumTypeName
        this.value = value
    }
}
