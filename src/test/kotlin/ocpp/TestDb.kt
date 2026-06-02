package io.konektis.ocpp

import org.jetbrains.exposed.sql.Database
import java.io.File

/** A fresh, isolated, file-backed SQLite database for a single test. Deleted on JVM exit. */
fun freshTestDb(): Database {
    val file = File.createTempFile("ocpp-test-", ".db").apply { deleteOnExit() }
    return Database.connect("jdbc:sqlite:${file.absolutePath}", "org.sqlite.JDBC")
}
