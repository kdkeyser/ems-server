package io.konektis.ocpp.db

import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction

@Serializable
data class ChargePointRecord(
    val chargePointId: String,
    val accepted: Boolean,
    val vendor: String?,
    val model: String?,
    val firmware: String?,
    val smartChargingSupported: Boolean,
    val powerImportSeen: Boolean,
)

class ChargePointStore(private val db: Database) {
    fun init() = transaction(db) { SchemaUtils.create(OcppChargePoints) }

    private fun row(r: ResultRow) = ChargePointRecord(
        chargePointId = r[OcppChargePoints.chargePointId],
        accepted = r[OcppChargePoints.accepted],
        vendor = r[OcppChargePoints.vendor],
        model = r[OcppChargePoints.model],
        firmware = r[OcppChargePoints.firmware],
        smartChargingSupported = r[OcppChargePoints.smartChargingSupported],
        powerImportSeen = r[OcppChargePoints.powerImportSeen],
    )

    suspend fun get(id: String): ChargePointRecord? = dbQuery(db) {
        OcppChargePoints.selectAll().where { OcppChargePoints.chargePointId eq id }
            .singleOrNull()?.let(::row)
    }

    suspend fun all(): List<ChargePointRecord> = dbQuery(db) {
        OcppChargePoints.selectAll().map(::row)
    }

    suspend fun recordBoot(id: String, vendor: String?, model: String?, firmware: String?) = dbQuery(db) {
        val exists = OcppChargePoints.selectAll()
            .where { OcppChargePoints.chargePointId eq id }.any()
        if (exists) {
            OcppChargePoints.update({ OcppChargePoints.chargePointId eq id }) {
                it[OcppChargePoints.vendor] = vendor
                it[OcppChargePoints.model] = model
                it[OcppChargePoints.firmware] = firmware
                it[lastBootAt] = System.currentTimeMillis()
            }
        } else {
            OcppChargePoints.insert {
                it[chargePointId] = id
                it[accepted] = false
                it[OcppChargePoints.vendor] = vendor
                it[OcppChargePoints.model] = model
                it[OcppChargePoints.firmware] = firmware
                it[lastBootAt] = System.currentTimeMillis()
            }
        }
        Unit
    }

    suspend fun setAccepted(id: String, value: Boolean) = dbQuery(db) {
        OcppChargePoints.update({ OcppChargePoints.chargePointId eq id }) { it[accepted] = value }
        Unit
    }

    suspend fun setSmartChargingSupported(id: String, value: Boolean) = dbQuery(db) {
        OcppChargePoints.update({ OcppChargePoints.chargePointId eq id }) { it[smartChargingSupported] = value }
        Unit
    }

    suspend fun setPowerImportSeen(id: String, value: Boolean) = dbQuery(db) {
        OcppChargePoints.update({ OcppChargePoints.chargePointId eq id }) { it[powerImportSeen] = value }
        Unit
    }
}

@Serializable
data class IdTagRecord(val idTag: String, val status: String, val expiryDate: String?)

class IdTagStore(private val db: Database) {
    fun init() = transaction(db) { SchemaUtils.create(OcppIdTags) }

    suspend fun get(idTag: String): IdTagRecord? = dbQuery(db) {
        OcppIdTags.selectAll().where { OcppIdTags.idTag eq idTag }.singleOrNull()?.let {
            IdTagRecord(it[OcppIdTags.idTag], it[OcppIdTags.status], it[OcppIdTags.expiryDate])
        }
    }

    suspend fun all(): List<IdTagRecord> = dbQuery(db) {
        OcppIdTags.selectAll().map { IdTagRecord(it[OcppIdTags.idTag], it[OcppIdTags.status], it[OcppIdTags.expiryDate]) }
    }

    suspend fun put(idTag: String, status: String, expiryDate: String? = null) = dbQuery(db) {
        val exists = OcppIdTags.selectAll().where { OcppIdTags.idTag eq idTag }.any()
        if (exists) {
            OcppIdTags.update({ OcppIdTags.idTag eq idTag }) {
                it[OcppIdTags.status] = status; it[OcppIdTags.expiryDate] = expiryDate
            }
        } else {
            OcppIdTags.insert {
                it[OcppIdTags.idTag] = idTag; it[OcppIdTags.status] = status; it[OcppIdTags.expiryDate] = expiryDate
            }
        }
        Unit
    }

    suspend fun delete(idTag: String) = dbQuery(db) {
        OcppIdTags.deleteWhere { OcppIdTags.idTag eq idTag }; Unit
    }
}

@Serializable
data class ChargerSettingsRecord(val chargePointId: String, val maxCurrentA: Int, val emsAutoControl: Boolean)

class ChargerSettingsStore(private val db: Database) {
    fun init() = transaction(db) { SchemaUtils.create(OcppChargerSettings) }

    suspend fun get(id: String): ChargerSettingsRecord? = dbQuery(db) {
        OcppChargerSettings.selectAll().where { OcppChargerSettings.chargePointId eq id }.singleOrNull()?.let {
            ChargerSettingsRecord(it[OcppChargerSettings.chargePointId], it[OcppChargerSettings.maxCurrentA], it[OcppChargerSettings.emsAutoControl])
        }
    }

    suspend fun put(id: String, maxCurrentA: Int, emsAutoControl: Boolean) = dbQuery(db) {
        val exists = OcppChargerSettings.selectAll().where { OcppChargerSettings.chargePointId eq id }.any()
        if (exists) {
            OcppChargerSettings.update({ OcppChargerSettings.chargePointId eq id }) {
                it[OcppChargerSettings.maxCurrentA] = maxCurrentA
                it[OcppChargerSettings.emsAutoControl] = emsAutoControl
            }
        } else {
            OcppChargerSettings.insert {
                it[chargePointId] = id
                it[OcppChargerSettings.maxCurrentA] = maxCurrentA
                it[OcppChargerSettings.emsAutoControl] = emsAutoControl
            }
        }
        Unit
    }
}

data class ChargerControlRecord(val chargePointId: String, val mode: String, val fixedAmps: Int, val charging: Boolean)

/** Persisted per-charger control intent. An interface so EnergyManager unit tests can fake it. */
interface ChargerControlStore {
    fun init()
    suspend fun get(id: String): ChargerControlRecord?
    suspend fun put(id: String, mode: String, fixedAmps: Int, charging: Boolean)
}

class SqlChargerControlStore(private val db: Database) : ChargerControlStore {
    override fun init() = transaction(db) { SchemaUtils.create(OcppChargerControl) }

    override suspend fun get(id: String): ChargerControlRecord? = dbQuery(db) {
        OcppChargerControl.selectAll().where { OcppChargerControl.chargePointId eq id }.singleOrNull()?.let {
            ChargerControlRecord(
                it[OcppChargerControl.chargePointId], it[OcppChargerControl.mode],
                it[OcppChargerControl.fixedAmps], it[OcppChargerControl.charging],
            )
        }
    }

    override suspend fun put(id: String, mode: String, fixedAmps: Int, charging: Boolean) = dbQuery(db) {
        val exists = OcppChargerControl.selectAll().where { OcppChargerControl.chargePointId eq id }.any()
        if (exists) {
            OcppChargerControl.update({ OcppChargerControl.chargePointId eq id }) {
                it[OcppChargerControl.mode] = mode
                it[OcppChargerControl.fixedAmps] = fixedAmps
                it[OcppChargerControl.charging] = charging
            }
        } else {
            OcppChargerControl.insert {
                it[chargePointId] = id
                it[OcppChargerControl.mode] = mode
                it[OcppChargerControl.fixedAmps] = fixedAmps
                it[OcppChargerControl.charging] = charging
            }
        }
        Unit
    }
}

@Serializable
data class TransactionRecord(
    val transactionId: Int, val chargePointId: String, val connectorId: Int, val idTag: String?,
    val meterStart: Int, val meterStop: Int?, val startTime: Long, val stopTime: Long?, val stopReason: String?,
)

class TransactionStore(private val db: Database) {
    fun init() = transaction(db) { SchemaUtils.create(OcppTransactions) }

    suspend fun record(
        transactionId: Int, chargePointId: String, connectorId: Int, idTag: String?,
        meterStart: Int, meterStop: Int?, startTime: Long, stopTime: Long?, stopReason: String?,
    ) = dbQuery(db) {
        OcppTransactions.insert {
            it[OcppTransactions.transactionId] = transactionId
            it[OcppTransactions.chargePointId] = chargePointId
            it[OcppTransactions.connectorId] = connectorId
            it[OcppTransactions.idTag] = idTag
            it[OcppTransactions.meterStart] = meterStart
            it[OcppTransactions.meterStop] = meterStop
            it[OcppTransactions.startTime] = startTime
            it[OcppTransactions.stopTime] = stopTime
            it[OcppTransactions.stopReason] = stopReason
        }
        Unit
    }

    fun maxTransactionId(): Int = transaction(db) {
        OcppTransactions.selectAll().maxOfOrNull { it[OcppTransactions.transactionId] } ?: 0
    }

    suspend fun recent(limit: Int): List<TransactionRecord> = dbQuery(db) {
        OcppTransactions.selectAll().orderBy(OcppTransactions.id, SortOrder.DESC).limit(limit).map {
            TransactionRecord(
                it[OcppTransactions.transactionId], it[OcppTransactions.chargePointId], it[OcppTransactions.connectorId],
                it[OcppTransactions.idTag], it[OcppTransactions.meterStart], it[OcppTransactions.meterStop],
                it[OcppTransactions.startTime], it[OcppTransactions.stopTime], it[OcppTransactions.stopReason],
            )
        }
    }
}
