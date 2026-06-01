package io.konektis.ocpp

import io.konektis.config.OcppConfig
import io.konektis.ocpp.db.*
import io.ktor.websocket.*
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.Database
import kotlin.test.*

class OcppServiceTest {

    private fun newService(acceptCp: Boolean = true, acceptTags: Boolean = true): OcppService =
        newServiceOn(freshTestDb(), acceptCp, acceptTags)

    private fun newServiceOn(db: Database, acceptCp: Boolean = true, acceptTags: Boolean = true): OcppService {
        val cfg = OcppConfig(enabled = true, heartbeatInterval = 300, connectionTimeout = 60,
            callTimeoutSeconds = 1, acceptUnknownChargePoints = acceptCp, acceptUnknownIdTags = acceptTags,
            autoProbeOnBoot = false)
        return OcppService(ChargePointStore(db), IdTagStore(db), ChargerSettingsStore(db), TransactionStore(db), cfg)
            .also { it.initStores() }
    }

    @Test
    fun bootAcceptsWhenAutoAcceptOn() = runTest {
        val svc = newService(acceptCp = true)
        svc.registerSession("CP01", mockk(relaxed = true))
        val resp = svc.handleBootNotification("CP01", BootNotificationRequest("Acme", "X1"))
        assertEquals(RegistrationStatus.Accepted, resp.status)
        assertEquals(300, resp.interval)
    }

    @Test
    fun bootPendingWhenAutoAcceptOff() = runTest {
        val svc = newService(acceptCp = false)
        svc.registerSession("CP02", mockk(relaxed = true))
        val resp = svc.handleBootNotification("CP02", BootNotificationRequest("Acme", "X1"))
        assertEquals(RegistrationStatus.Pending, resp.status)
    }

    @Test
    fun startTransactionPersistsOnStop() = runTest {
        val svc = newService()
        svc.registerSession("CP03", mockk(relaxed = true))
        val start = svc.handleStartTransaction("CP03",
            StartTransactionRequest(connectorId = 1, idTag = "TAG1", meterStart = 0, timestamp = "2026-01-01T00:00:00Z"))
        svc.handleStopTransaction("CP03",
            StopTransactionRequest(transactionId = start.transactionId, timestamp = "2026-01-01T01:00:00Z", meterStop = 1000))
        val recent = svc.recentTransactions(10)
        assertEquals(1, recent.size)
        assertEquals(1000, recent.first().meterStop)
    }

    @Test
    fun statusNotificationUpdatesLiveState() = runTest {
        val svc = newService()
        svc.registerSession("CP04", mockk(relaxed = true))
        svc.handleStatusNotification("CP04",
            StatusNotificationRequest(connectorId = 1, errorCode = ChargePointErrorCode.NoError, status = ChargePointStatus.Available))
        val cp = svc.stateFlow.value.chargePoints.single { it.chargePointId == "CP04" }
        assertEquals("Available", cp.connectors.single().status)
    }

    @Test
    fun transactionIdsContinueAcrossRestart() = runTest {
        val db = freshTestDb()
        val svc1 = newServiceOn(db)
        svc1.registerSession("CP1", mockk(relaxed = true))
        val first = svc1.handleStartTransaction("CP1",
            StartTransactionRequest(connectorId = 1, idTag = "TAG1", meterStart = 0, timestamp = "2026-01-01T00:00:00Z"))
        svc1.handleStopTransaction("CP1",
            StopTransactionRequest(transactionId = first.transactionId, timestamp = "2026-01-01T01:00:00Z", meterStop = 500))

        // simulate restart: new service over the SAME db
        val svc2 = newServiceOn(db)
        svc2.registerSession("CP1", mockk(relaxed = true))
        val second = svc2.handleStartTransaction("CP1",
            StartTransactionRequest(connectorId = 1, idTag = "TAG1", meterStart = 0, timestamp = "2026-01-01T02:00:00Z"))
        assertTrue(second.transactionId > first.transactionId)
    }

    @Test
    fun meterValuesPowerExtractionFeedsLatestPowerW() = runTest {
        val svc = newService()
        svc.registerSession("CP1", mockk(relaxed = true))

        // W passthrough
        svc.handleMeterValues("CP1", MeterValuesRequest(connectorId = 1, meterValue = listOf(
            MeterValue("t", listOf(SampledValue(value = "2300", measurand = Measurand.PowerActiveImport, unit = UnitOfMeasure.W))))))
        assertEquals(2300, svc.latestPowerW("CP1", 1))

        // kW -> W
        svc.handleMeterValues("CP1", MeterValuesRequest(connectorId = 1, meterValue = listOf(
            MeterValue("t", listOf(SampledValue(value = "3.5", measurand = Measurand.PowerActiveImport, unit = UnitOfMeasure.kW))))))
        assertEquals(3500, svc.latestPowerW("CP1", 1))
    }

    @Test
    fun authorizeRejectsBlankIdTag() = runTest {
        val svc = newService(acceptTags = true)
        svc.registerSession("CP1", mockk(relaxed = true))
        val r = svc.handleAuthorize("CP1", AuthorizeRequest(idTag = ""))
        assertEquals(AuthorizationStatus.Invalid, r.idTagInfo.status)
    }

    @Test
    fun previouslyAcceptedChargePointStaysAcceptedWithAutoAcceptOff() = runTest {
        val db = freshTestDb()
        // First boot with auto-accept on: CPX gets accepted + persisted.
        val svcA = newServiceOn(db, acceptCp = true)
        svcA.registerSession("CPX", mockk(relaxed = true))
        assertEquals(RegistrationStatus.Accepted,
            svcA.handleBootNotification("CPX", BootNotificationRequest("Acme", "X1")).status)

        // Restart with auto-accept off: the persisted accepted record still wins.
        val svcB = newServiceOn(db, acceptCp = false)
        svcB.registerSession("CPX", mockk(relaxed = true))
        assertEquals(RegistrationStatus.Accepted,
            svcB.handleBootNotification("CPX", BootNotificationRequest("Acme", "X1")).status)
    }
}
