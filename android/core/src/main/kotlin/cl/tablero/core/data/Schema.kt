package cl.tablero.core.data

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/** Must equal `SCHEMA_VERSION` in scripts/db.py; a test holds them together. */
const val SCHEMA_VERSION = 8

/** db._UPGRADES: in-place steps keyed by the version they move a database to. */
val UPGRADES: Map<Int, List<String>> = mapOf(
    4 to listOf(
        "ALTER TABLE price_obs ADD COLUMN in_stock INTEGER",
        "ALTER TABLE price_obs ADD COLUMN price_original REAL",
    ),
    5 to listOf("ALTER TABLE product ADD COLUMN image_url TEXT"),
    6 to listOf(
        "ALTER TABLE game ADD COLUMN bgg_id INTEGER",
        "CREATE TABLE IF NOT EXISTS bgg ( id INTEGER PRIMARY KEY, name TEXT NOT NULL, norm TEXT NOT NULL, yearpublished INTEGER, rank INTEGER, bayesaverage REAL, average REAL, usersrated INTEGER NOT NULL, is_expansion INTEGER NOT NULL, abstracts_rank INTEGER, cgs_rank INTEGER, childrensgames_rank INTEGER, familygames_rank INTEGER, partygames_rank INTEGER, strategygames_rank INTEGER, thematic_rank INTEGER, wargames_rank INTEGER )",
        "CREATE INDEX IF NOT EXISTS idx_bgg_norm ON bgg(norm)",
    ),
    7 to listOf("CREATE TABLE IF NOT EXISTS bgg_alias ( norm TEXT PRIMARY KEY, bgg_id INTEGER NOT NULL )"),
    8 to listOf(
        "CREATE TABLE IF NOT EXISTS owned ( game_id INTEGER PRIMARY KEY REFERENCES game(id) ON DELETE CASCADE, added_at INTEGER NOT NULL )",
        "CREATE TABLE IF NOT EXISTS store_shipping ( store TEXT PRIMARY KEY, cost REAL NOT NULL )",
    ),
)

/** Versions a database can be at and still be opened, upgrading on the way. */
fun canOpen(version: Int): Boolean =
    version == 0 || version == SCHEMA_VERSION ||
        (version in 1 until SCHEMA_VERSION && (version + 1..SCHEMA_VERSION).all { it in UPGRADES })

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
 * Create the schema if absent, apply pending in-place upgrades, stamp the
 * version. Safe to call repeatedly. Mirrors db.init_db + db.upgrade: a
 * database newer than the app, or one with no path forward, is refused.
 */
fun initDb(conn: SQLiteConnection) {
    val existing = userVersion(conn)
    if (!canOpen(existing)) {
        throw SchemaVersionError("Database at schema v$existing, app expects v$SCHEMA_VERSION")
    }
    if (existing != 0) {
        for (version in existing + 1..SCHEMA_VERSION) {
            UPGRADES.getValue(version).forEach(conn::execSQL)
            conn.execSQL("PRAGMA user_version = $version")
        }
    }
    schemaStatements().forEach(conn::execSQL)
    conn.execSQL("PRAGMA user_version = $SCHEMA_VERSION")
}
