package cl.tablero.core.data

import androidx.sqlite.SQLITE_DATA_FLOAT
import androidx.sqlite.SQLITE_DATA_INTEGER
import androidx.sqlite.SQLITE_DATA_NULL
import androidx.sqlite.SQLITE_DATA_TEXT
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.execSQL

/*
 * The handful of helpers that make androidx.sqlite read like Python's sqlite3:
 * positional parameters, executemany, rows as maps. Every SQL string in this
 * package is bound through here -- never interpolated.
 */

typealias SqlRow = Map<String, Any?>

fun SQLiteStatement.bindAll(args: List<Any?>) {
    args.forEachIndexed { i, value ->
        val index = i + 1
        when (value) {
            null -> bindNull(index)
            is String -> bindText(index, value)
            is Long -> bindLong(index, value)
            is Int -> bindLong(index, value.toLong())
            is Double -> bindDouble(index, value)
            is Boolean -> bindLong(index, if (value) 1 else 0)
            else -> throw IllegalArgumentException("Cannot bind ${value::class.simpleName}")
        }
    }
}

/** Column value as Python's sqlite3 would return it: Long, Double, String, ByteArray or null. */
fun SQLiteStatement.value(index: Int): Any? = when (getColumnType(index)) {
    SQLITE_DATA_NULL -> null
    SQLITE_DATA_INTEGER -> getLong(index)
    SQLITE_DATA_FLOAT -> getDouble(index)
    SQLITE_DATA_TEXT -> getText(index)
    else -> getBlob(index)
}

fun SQLiteConnection.exec(sql: String, vararg args: Any?) {
    prepare(sql).use { it.bindAll(args.asList()); it.step() }
}

/** `executemany`: one prepared statement, many parameter rows. */
fun SQLiteConnection.execMany(sql: String, rows: List<List<Any?>>) {
    prepare(sql).use { st ->
        for (row in rows) {
            st.bindAll(row)
            st.step()
            st.reset()
            st.clearBindings()
        }
    }
}

fun SQLiteConnection.query(sql: String, vararg args: Any?): List<SqlRow> =
    prepare(sql).use { st ->
        st.bindAll(args.asList())
        buildList {
            while (st.step()) add((0 until st.getColumnCount()).associate { st.getColumnName(it) to st.value(it) })
        }
    }

/**
 * Run `block` in one write transaction. Python's sqlite3 opens these
 * implicitly; here it has to be asked for, and a failure rolls back rather
 * than leaving the transaction open the way the Python code would.
 */
inline fun <T> SQLiteConnection.transaction(block: () -> T): T {
    execSQL("BEGIN IMMEDIATE")
    try {
        return block().also { execSQL("COMMIT") }
    } catch (e: Throwable) {
        execSQL("ROLLBACK")
        throw e
    }
}
