package cl.tablero.app

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import cl.tablero.core.data.SCHEMA_VERSION
import cl.tablero.core.data.canOpen
import cl.tablero.core.data.exec
import cl.tablero.core.data.initDb
import cl.tablero.core.data.query
import cl.tablero.core.data.userVersion
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors

// The first 16 bytes of every SQLite 3 file: "SQLite format 3" and a NUL.
private val SQLITE_HEADER = "SQLite format 3".toByteArray(Charsets.US_ASCII) + byteArrayOf(0)

/** Whether `file` starts with the SQLite 3 header. */
private fun hasSqliteHeader(file: File): Boolean = file.inputStream().use { input ->
    val head = ByteArray(SQLITE_HEADER.size)
    var read = 0
    while (read < head.size) {
        val n = input.read(head, read, head.size - read)
        if (n < 0) return@use false
        read += n
    }
    head.contentEquals(SQLITE_HEADER)
}

/**
 * The app's one database connection, confined to one thread.
 *
 * A SQLite connection is not safe to share across threads, and the Python
 * tool already settled on "scrape concurrently, write serially". Every read
 * and write goes through [read] or [write] on this single thread; scrapes run
 * elsewhere and only hand their records over to be ingested.
 */
class Db(private val file: File) {
    private val thread = Executors.newSingleThreadExecutor { Thread(it, "tablero-db") }.asCoroutineDispatcher()
    private var conn: SQLiteConnection? = null

    private val _version = MutableStateFlow(0)
    private val _updates = MutableStateFlow(0)

    /** Bumped after every write, so screens know to query again. */
    val version: StateFlow<Int> = _version

    /**
     * Bumped once per finished update. For lists where re-querying on every
     * store's write would keep yanking the scroll position away.
     */
    val updates: StateFlow<Int> = _updates

    private fun open(): SQLiteConnection = conn ?: AndroidSQLiteDriver().open(
        file.apply { parentFile?.mkdirs() }.path,
    ).also {
        // Schema-defined functions in views and triggers do not run with
        // the connection's privileges. Unknown on SQLite < 3.31, which ignores it.
        it.execSQL("PRAGMA trusted_schema = OFF")
        initDb(it)
        conn = it
    }

    suspend fun <T> read(block: (SQLiteConnection) -> T): T = withContext(thread) { block(open()) }

    suspend fun <T> write(block: (SQLiteConnection) -> T): T =
        withContext(thread) { block(open()) }.also { _version.value++ }

    fun <T> readBlocking(block: (SQLiteConnection) -> T): T = runBlocking { read(block) }

    /** For the update worker, which is already on a background thread. */
    fun <T> writeBlocking(block: (SQLiteConnection) -> T): T = runBlocking { write(block) }

    fun updated() { _updates.value++ }

    /**
     * Held by an update for its whole run, and by an import while it replaces
     * the file. An import in the middle of an update would leave the rest of
     * that update writing its stores into the freshly imported database.
     */
    val busy = Mutex()

    /**
     * Replace the database with a copy made on the laptop (`tablero snapshot`)
     * or exported from another phone. Refused while an update runs.
     */
    suspend fun import(source: InputStream, scratch: File): Int = source.use { input ->
        check(busy.tryLock()) { "Hay una actualización en curso. Detenla en Ajustes → Tiendas y vuelve a intentarlo." }
        try {
            withContext(thread) {
                val incoming = scratch.resolve("import.db").apply { delete() }
                incoming.outputStream().use { input.copyTo(it) }
                validated(incoming).also { swapIn(incoming) }
            }
        } finally {
            busy.unlock()
        }
    }

    /**
     * The product count of a candidate copy, once it is known to be safe to
     * use; a copy that is not is deleted. It must pass SQLite's integrity
     * check, be at a schema this app can open, and carry no triggers or
     * views. The schema defines none, so one in an imported file could only
     * have been put there to run inside our queries.
     */
    private fun validated(incoming: File): Int = try {
        // Checked before opening: Android's SQLite treats a non-database as
        // corrupt, and its default handler deletes the file and hands back a
        // fresh empty database, which would then be refused for the wrong reason.
        require(hasSqliteHeader(incoming)) { "No es una base de tablero: el archivo no es SQLite." }
        AndroidSQLiteDriver().open(incoming.path).use { check ->
            check.execSQL("PRAGMA trusted_schema = OFF")
            val integrity = check.query("PRAGMA integrity_check").first().values.first()
            require(integrity == "ok") { "El archivo está dañado ($integrity)." }
            val version = userVersion(check)
            require(version != 0) { "No es una base de tablero: es SQLite, pero no tiene su esquema." }
            require(canOpen(version)) {
                "Esquema v$version; la app usa v$SCHEMA_VERSION. Exporta con un tablero actualizado."
            }
            val extra = check.query("SELECT type, name FROM sqlite_master WHERE type IN ('trigger', 'view')")
            require(extra.isEmpty()) { "El archivo trae objetos que tablero no crea: ${extra.map { it["name"] }}" }
            (check.query("SELECT COUNT(*) AS n FROM product").first()["n"] as Long).toInt()
        }
    } catch (e: IllegalArgumentException) {
        incoming.delete(); throw e
    } catch (e: Exception) {
        incoming.delete(); throw IllegalArgumentException("No es una base de tablero: ${e.message}", e)
    }

    /** Close the current database, remove it and its sidecars, put `incoming` in its place. */
    private fun swapIn(incoming: File) {
        conn?.close()
        conn = null
        listOf("", "-wal", "-shm", "-journal").forEach { File(file.path + it).delete() }
        check(incoming.renameTo(file)) { "No se pudo mover la copia a ${file.path}" }
        _version.value++
        _updates.value++
    }

    /**
     * Write a single-file copy, the phone's `tablero snapshot`: VACUUM INTO
     * reads through the WAL, so nothing recent is missed.
     */
    suspend fun export(target: OutputStream, scratch: File) = withContext(thread) {
        val copy = scratch.resolve("export.db").apply { delete() }
        try {
            open().exec("VACUUM INTO ?", copy.path)
            target.use { out -> copy.inputStream().use { it.copyTo(out) } }
        } finally {
            copy.delete()
        }
    }
}
