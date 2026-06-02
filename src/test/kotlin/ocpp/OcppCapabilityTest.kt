package io.konektis.ocpp

import io.konektis.config.OcppConfig
import io.konektis.ocpp.db.*
import io.ktor.websocket.*
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class OcppCapabilityTest {

    private fun newService(): Pair<OcppService, ChargePointStore> {
        val db = freshTestDb()
        val store = ChargePointStore(db)
        val svc = OcppService(store, IdTagStore(db), ChargerSettingsStore(db), TransactionStore(db),
            OcppConfig(true, 300, 60, callTimeoutSeconds = 1, autoProbeOnBoot = false)).also { it.initStores() }
        return svc to store
    }

    @Test
    fun powerImportFlagSetFromMeterValues() = runTest {
        val (svc, store) = newService()
        svc.registerSession("CP1", mockk(relaxed = true))
        svc.handleBootNotification("CP1", BootNotificationRequest("Acme", "X1"))

        svc.handleMeterValues("CP1", MeterValuesRequest(
            connectorId = 1,
            meterValue = listOf(MeterValue(
                timestamp = "2026-01-01T00:00:00Z",
                sampledValue = listOf(SampledValue(value = "2300", measurand = Measurand.PowerActiveImport, unit = UnitOfMeasure.W)),
            )),
        ))

        assertEquals(2300, svc.latestPowerW("CP1", 1))
        assertTrue(store.get("CP1")!!.powerImportSeen)
        assertTrue(svc.stateFlow.value.chargePoints.single().powerReadable)
    }

    @Test
    fun smartChargingDetectedFromGetConfiguration() = runTest {
        val (svc, store) = newService()
        svc.registerSession("CP1", mockk(relaxed = true))
        svc.handleBootNotification("CP1", BootNotificationRequest("Acme", "X1")) // create the DB record
        // Simulate a GetConfiguration reply advertising SmartCharging.
        val resp = GetConfigurationResponse(
            configurationKey = listOf(ConfigurationKey("SupportedFeatureProfiles", true, "Core,SmartCharging")),
        )
        svc.applyCapabilityProbe("CP1", resp)

        assertTrue(store.get("CP1")!!.smartChargingSupported)
        assertTrue(svc.stateFlow.value.chargePoints.single().smartChargingSupported)
    }
}
