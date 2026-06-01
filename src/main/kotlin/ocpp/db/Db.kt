package io.konektis.ocpp.db

import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

/** Open a file-based SQLite database for all non-time-series persistence. */
fun openDatabase(path: String): Database =
    Database.connect(url = "jdbc:sqlite:$path", driver = "org.sqlite.JDBC")

/** Run an Exposed transaction on the IO dispatcher (SQLite calls are blocking). */
suspend fun <T> dbQuery(db: Database, block: suspend () -> T): T =
    newSuspendedTransaction(Dispatchers.IO, db) { block() }
