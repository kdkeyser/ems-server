package io.konektis.cardata

import io.konektis.ocpp.db.dbQuery
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction

object CarDataTokens : Table("cardata_tokens") {
    val clientId = varchar("client_id", 128)
    val refreshToken = varchar("refresh_token", 4096)
    val gcid = varchar("gcid", 128).nullable()
    override val primaryKey = PrimaryKey(clientId)
}

data class CarDataTokenRecord(val clientId: String, val refreshToken: String, val gcid: String?)

/** Persists the long-lived refresh token so the one-time device approval survives restarts.
 *  An interface so the service's tests can fake it. */
interface CarDataTokenStore {
    fun init()
    suspend fun get(clientId: String): CarDataTokenRecord?
    suspend fun save(clientId: String, refreshToken: String, gcid: String?)
}

class SqlCarDataTokenStore(private val db: Database) : CarDataTokenStore {
    override fun init() = transaction(db) { SchemaUtils.create(CarDataTokens) }

    override suspend fun get(clientId: String): CarDataTokenRecord? = dbQuery(db) {
        CarDataTokens.selectAll().where { CarDataTokens.clientId eq clientId }.singleOrNull()?.let {
            CarDataTokenRecord(it[CarDataTokens.clientId], it[CarDataTokens.refreshToken], it[CarDataTokens.gcid])
        }
    }

    override suspend fun save(clientId: String, refreshToken: String, gcid: String?) = dbQuery(db) {
        val exists = CarDataTokens.selectAll().where { CarDataTokens.clientId eq clientId }.any()
        if (exists) {
            CarDataTokens.update({ CarDataTokens.clientId eq clientId }) {
                it[CarDataTokens.refreshToken] = refreshToken
                it[CarDataTokens.gcid] = gcid
            }
        } else {
            CarDataTokens.insert {
                it[CarDataTokens.clientId] = clientId
                it[CarDataTokens.refreshToken] = refreshToken
                it[CarDataTokens.gcid] = gcid
            }
        }
        Unit
    }
}
