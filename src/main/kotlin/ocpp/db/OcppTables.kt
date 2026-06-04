package io.konektis.ocpp.db

import org.jetbrains.exposed.sql.Table

object OcppChargePoints : Table("ocpp_charge_points") {
    val chargePointId = varchar("charge_point_id", 64)
    val accepted = bool("accepted").default(false)
    val vendor = varchar("vendor", 128).nullable()
    val model = varchar("model", 128).nullable()
    val firmware = varchar("firmware", 128).nullable()
    val smartChargingSupported = bool("smart_charging_supported").default(false)
    val powerImportSeen = bool("power_import_seen").default(false)
    val lastBootAt = long("last_boot_at").nullable()
    override val primaryKey = PrimaryKey(chargePointId)
}

object OcppIdTags : Table("ocpp_id_tags") {
    val idTag = varchar("id_tag", 64)
    val status = varchar("status", 32)
    val expiryDate = varchar("expiry_date", 64).nullable()
    override val primaryKey = PrimaryKey(idTag)
}

object OcppChargerControl : Table("ocpp_charger_control") {
    val chargePointId = varchar("charge_point_id", 64)
    val mode = varchar("mode", 16)        // "SOLAR" | "FIXED"
    val fixedAmps = integer("fixed_amps")
    val charging = bool("charging")
    override val primaryKey = PrimaryKey(chargePointId)
}

object OcppTransactions : Table("ocpp_transactions") {
    val id = integer("id").autoIncrement()
    val transactionId = integer("transaction_id")
    val chargePointId = varchar("charge_point_id", 64)
    val connectorId = integer("connector_id")
    val idTag = varchar("id_tag", 64).nullable()
    val meterStart = integer("meter_start")
    val meterStop = integer("meter_stop").nullable()
    val startTime = long("start_time")
    val stopTime = long("stop_time").nullable()
    val stopReason = varchar("stop_reason", 32).nullable()
    override val primaryKey = PrimaryKey(id)
}
