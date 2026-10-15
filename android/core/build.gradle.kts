plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(libs.sqlite)
    testImplementation(kotlin("test"))
    testImplementation(libs.sqlite.bundled)
    testImplementation(libs.serialization.json)
}

// The Python checkout this project lives in. It owns the schema and the
// parity corpus; nothing here keeps a copy of either.
val repo: File = rootDir.parentFile

tasks.processResources {
    from(repo.resolve("scripts/schema.sql")) { into("cl/tablero/core") }
}

tasks.test {
    useJUnitPlatform()
    systemProperty("tablero.repo", repo.absolutePath)
    inputs.dir(repo.resolve("tests/fixtures"))
    inputs.file(repo.resolve("scripts/db.py"))
}
