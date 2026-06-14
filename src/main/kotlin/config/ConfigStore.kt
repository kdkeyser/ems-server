package io.konektis.config

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/**
 * Persisted, editable config. The configurable subtree is stored as a single JSON document so the
 * stored shape matches the [Config] domain model (and the future API payloads) exactly — no
 * per-field columns, no schema churn when a device type gains a field. A single row (id = 1).
 *
 * Bootstrap fields in the stored document are ignored on load (see [Config.withBootstrapFrom]); only
 * the in-scope subtree (grid, devices, ocpp, …) is taken from here.
 */
object EmsConfigTable : Table("ems_config") {
    val id = integer("id")
    val json = text("json")
    val version = integer("version")
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(id)
}

/** The stored config document plus its revision (bumped on every save, for change detection). */
data class StoredConfig(val json: String, val version: Int)

class ConfigStore(private val db: Database) {
    fun init() = transaction(db) { SchemaUtils.create(EmsConfigTable) }

    /** The stored config document, or null if nothing has been stored yet. */
    fun load(): StoredConfig? = transaction(db) {
        EmsConfigTable.selectAll()
            .where { EmsConfigTable.id eq SINGLETON_ID }
            .singleOrNull()
            ?.let { StoredConfig(it[EmsConfigTable.json], it[EmsConfigTable.version]) }
    }

    /** Insert or replace the single config document, bumping the revision. Returns the new revision. */
    fun save(json: String): Int = transaction(db) {
        val current = EmsConfigTable.selectAll().where { EmsConfigTable.id eq SINGLETON_ID }.singleOrNull()
        if (current != null) {
            val next = current[EmsConfigTable.version] + 1
            EmsConfigTable.update({ EmsConfigTable.id eq SINGLETON_ID }) {
                it[EmsConfigTable.json] = json
                it[version] = next
                it[updatedAt] = System.currentTimeMillis()
            }
            next
        } else {
            EmsConfigTable.insert {
                it[id] = SINGLETON_ID
                it[EmsConfigTable.json] = json
                it[version] = 1
                it[updatedAt] = System.currentTimeMillis()
            }
            1
        }
    }

    companion object {
        const val SINGLETON_ID = 1
    }
}
