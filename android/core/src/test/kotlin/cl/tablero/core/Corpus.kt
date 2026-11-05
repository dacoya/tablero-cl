package cl.tablero.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.File

/** The Python checkout: owner of the schema and of tests/fixtures. */
val REPO = File(checkNotNull(System.getProperty("tablero.repo")) { "run through Gradle" })

val FIXTURES: File = REPO.resolve("tests/fixtures")

fun corpus(name: String): JsonElement =
    Json.parseToJsonElement(FIXTURES.resolve(name).readText())
