package io.konektis.ocpp

import io.konektis.config.OcppConfig
import io.konektis.ocpp.db.*
import io.ktor.websocket.*
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class OcppServiceTest {

    private fun newService(acceptCp: Boolean = true, acceptTags: Boolean = true): OcppService {
        val db = freshTestDb()
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
}
