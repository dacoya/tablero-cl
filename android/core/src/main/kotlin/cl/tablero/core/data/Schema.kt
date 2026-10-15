package cl.tablero.core.data

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/** Must equal `SCHEMA_VERSION` in scripts/db.py; a test holds them together. */
const val SCHEMA_VERSION = 4

private val LINE_COMMENT = Regex("--[^\n]*")

/** scripts/schema.sql, copied into resources at build time. */
fun loadSchema(): String =
    checkNotNull(SchemaVersionError::class.java.getResource("/cl/tablero/core/schema.sql")) {
        "schema.sql missing from resources"
    }.readText()

/**
 * schema.sql as individual statements, without the FTS table.
 *
 * FTS5 is not guaranteed in Android's SQLite build, and search on the phone
 * does not use it. A database imported from the laptop still carries the
 * table; it is simply never touched.
 */
fun schemaStatements(sql: String = loadSchema()): List<String> =
    sql.replace(LINE_COMMENT, "")
        .split(';')
        .map { it.trim() }
        .filter { it.isNotEmpty() && "fts5" !in it.lowercase() }

class SchemaVersionError(message: String) : RuntimeException(message)

fun userVersion(conn: SQLiteConnection): Int =
    conn.prepare("PRAGMA user_version").use { it.step(); it.getInt(0) }

/**
 * Create the schema if absent and stamp the version. Safe to call repeatedly.
 *
 * Unlike db.py there are no in-place upgrade steps yet: the phone starts at v4
 * or imports a v4 database. Anything else is refused rather than guessed at.
 */
fun initDb(conn: SQLiteConnection) {
    val existing = userVersion(conn)
    if (existing != 0 && existing != SCHEMA_VERSION) {
        throw SchemaVersionError("Database at schema v$existing, app expects v$SCHEMA_VERSION")
    }
    schemaStatements().forEach(conn::execSQL)
    conn.execSQL("PRAGMA user_version = $SCHEMA_VERSION")
}
