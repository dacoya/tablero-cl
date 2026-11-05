plugins {
    alias(libs.plugins.kotlin.jvm)
    // Parity helpers shared with app's instrumented test, so the same checks
    // run on the JVM and on a device.
    `java-test-fixtures`
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(libs.sqlite)
    api(libs.jsoup)
    implementation(libs.okhttp)
    testFixturesApi(libs.serialization.json)
    testImplementation(kotlin("test"))
    testImplementation(libs.sqlite.bundled)
    testImplementation(libs.mockwebserver)
}

// The Python checkout this project lives in. It owns the schema and the
// parity corpus; nothing here keeps a copy of either.
val repo: File = rootDir.parentFile

// Both come from the Python side: the schema, and the scrape registry that
// `tablero fixtures` writes from scrape.sites.
tasks.processResources {
    from(repo.resolve("scripts/schema.sql")) { into("cl/tablero/core") }
    from(repo.resolve("tests/fixtures/sites.tsv")) { into("cl/tablero/core") }
}

tasks.test {
    useJUnitPlatform()
    systemProperty("tablero.repo", repo.absolutePath)
    inputs.dir(repo.resolve("tests/fixtures"))
    inputs.file(repo.resolve("scripts/db.py"))
}
