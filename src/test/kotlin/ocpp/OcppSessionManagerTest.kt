package io.konektis.ocpp

import io.ktor.websocket.*
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class OcppSessionManagerTest {

    private lateinit var sessionManager: OcppSessionManager
    private lateinit var mockSession: DefaultWebSocketSession

    @BeforeTest
    fun setup() {
        sessionManager = OcppSessionManager()
        mockSession = mockk(relaxed = true)
    }

    @Test
    fun testRegisterSession() = runTest {
        // Register a session
        sessionManager.registerSession("CP001", mockSession)

        // Verify session is registered
        val session = sessionManager.getSession("CP001")
        assertNotNull(session)
        assertEquals("CP001", session.chargePointId)
    }

    @Test
    fun testUnregisterSession() = runTest {
        // Register and then unregister
        sessionManager.registerSession("CP001", mockSession)
        sessionManager.unregisterSession("CP001")

        // Verify session is removed
        val session = sessionManager.getSession("CP001")
        assertNull(session)
    }

    @Test
    fun testGetAllSessions() = runTest {
        // Register multiple sessions
        val mockSession1 = mockk<DefaultWebSocketSession>(relaxed = true)
        val mockSession2 = mockk<DefaultWebSocketSession>(relaxed = true)
        
        sessionManager.registerSession("CP001", mockSession1)
        sessionManager.registerSession("CP002", mockSession2)

        // Verify all sessions are returned
        val sessions = sessionManager.getAllSessions()
        assertEquals(2, sessions.size)
        assertTrue(sessions.any { it.chargePointId == "CP001" })
        assertTrue(sessions.any { it.chargePointId == "CP002" })
    }

    @Test
    fun testHandleBootNotification() {
        val request = BootNotificationRequest(
            chargePointVendor = "TestVendor",
            chargePointModel = "TestModel",
            chargePointSerialNumber = "SN123456"
        )

        val response = sessionManager.handleBootNotification("CP001", request)

        assertEquals(RegistrationStatus.Accepted, response.status)
        assertNotNull(response.currentTime)
        assertEquals(300, response.interval)
    }

    @Test
    fun testHandleHeartbeat() {
        val response = sessionManager.handleHeartbeat("CP001")
        assertNotNull(response.currentTime)
    }

    @Test
    fun testHandleAuthorize() {
        val request = AuthorizeRequest(idTag = "USER001")
        val response = sessionManager.handleAuthorize("CP001", request)

        assertEquals(AuthorizationStatus.Accepted, response.idTagInfo.status)
    }

    @Test
    fun testHandleAuthorizeInvalidTag() {
        val request = AuthorizeRequest(idTag = "")
        val response = sessionManager.handleAuthorize("CP001", request)

        assertEquals(AuthorizationStatus.Invalid, response.idTagInfo.status)
    }

    @Test
    fun testHandleStartTransaction() = runTest {
        sessionManager.registerSession("CP001", mockSession)

        val request = StartTransactionRequest(
            connectorId = 1,
            idTag = "USER001",
            meterStart = 0,
            timestamp = "2025-11-16T15:00:00Z"
        )

        val response = sessionManager.handleStartTransaction("CP001", request)

        assertNotNull(response.transactionId)
        assertEquals(AuthorizationStatus.Accepted, response.idTagInfo.status)

        // Verify transaction is tracked
        val session = sessionManager.getSession("CP001")
        assertNotNull(session)
        assertTrue(session.activeTransactions.containsKey(response.transactionId))
    }

    @Test
    fun testHandleStopTransaction() = runTest {
        sessionManager.registerSession("CP001", mockSession)

        // Start a transaction first
        val startRequest = StartTransactionRequest(
            connectorId = 1,
            idTag = "USER001",
            meterStart = 0,
            timestamp = "2025-11-16T15:00:00Z"
        )
        val startResponse = sessionManager.handleStartTransaction("CP001", startRequest)

        // Stop the transaction
        val stopRequest = StopTransactionRequest(
            transactionId = startResponse.transactionId,
            timestamp = "2025-11-16T15:30:00Z",
            meterStop = 1000
        )
        val stopResponse = sessionManager.handleStopTransaction("CP001", stopRequest)

        assertEquals(AuthorizationStatus.Accepted, stopResponse.idTagInfo?.status)

        // Verify transaction is removed from active transactions
        val session = sessionManager.getSession("CP001")
        assertNotNull(session)
        assertFalse(session.activeTransactions.containsKey(startResponse.transactionId))
    }

    @Test
    fun testHandleStatusNotification() = runTest {
        sessionManager.registerSession("CP001", mockSession)

        val request = StatusNotificationRequest(
            connectorId = 1,
            errorCode = ChargePointErrorCode.NoError,
            status = ChargePointStatus.Available
        )

        val response = sessionManager.handleStatusNotification("CP001", request)
        assertNotNull(response)

        // Verify connector state is updated
        val session = sessionManager.getSession("CP001")
        assertNotNull(session)
        val connector = session.connectors[1]
        assertNotNull(connector)
        assertEquals(ChargePointStatus.Available, connector.status)
        assertEquals(ChargePointErrorCode.NoError, connector.errorCode)
    }

    @Test
    fun testHandleMeterValues() {
        val request = MeterValuesRequest(
            connectorId = 1,
            meterValue = listOf(
                MeterValue(
                    timestamp = "2025-11-16T15:00:00Z",
                    sampledValue = listOf(
                        SampledValue(value = "1000", unit = UnitOfMeasure.Wh)
                    )
                )
            )
        )

        val response = sessionManager.handleMeterValues("CP001", request)
        assertNotNull(response)
    }

    @Test
    fun testHandleDataTransfer() {
        val request = DataTransferRequest(
            vendorId = "TestVendor",
            messageId = "TestMessage",
            data = "TestData"
        )

        val response = sessionManager.handleDataTransfer("CP001", request)
        assertEquals(DataTransferStatus.Accepted, response.status)
    }

    @Test
    fun testGenerateTransactionId() {
        val id1 = sessionManager.generateTransactionId()
        val id2 = sessionManager.generateTransactionId()

        assertTrue(id1 > 0)
        assertTrue(id2 > id1)
    }

    @Test
    fun testMultipleConnectors() = runTest {
        sessionManager.registerSession("CP001", mockSession)

        // Update status for multiple connectors
        val request1 = StatusNotificationRequest(
            connectorId = 1,
            errorCode = ChargePointErrorCode.NoError,
            status = ChargePointStatus.Available
        )
        sessionManager.handleStatusNotification("CP001", request1)

        val request2 = StatusNotificationRequest(
            connectorId = 2,
            errorCode = ChargePointErrorCode.NoError,
            status = ChargePointStatus.Charging
        )
        sessionManager.handleStatusNotification("CP001", request2)

        // Verify both connectors are tracked
        val session = sessionManager.getSession("CP001")
        assertNotNull(session)
        assertEquals(2, session.connectors.size)
        assertEquals(ChargePointStatus.Available, session.connectors[1]?.status)
        assertEquals(ChargePointStatus.Charging, session.connectors[2]?.status)
    }

    @Test
    fun testTransactionConnectorLink() = runTest {
        sessionManager.registerSession("CP001", mockSession)

        // Start transaction
        val startRequest = StartTransactionRequest(
            connectorId = 1,
            idTag = "USER001",
            meterStart = 0,
            timestamp = "2025-11-16T15:00:00Z"
        )
        val startResponse = sessionManager.handleStartTransaction("CP001", startRequest)

        // Verify connector is linked to transaction
        val session = sessionManager.getSession("CP001")
        assertNotNull(session)
        val connector = session.connectors[1]
        assertNotNull(connector)
        assertEquals(startResponse.transactionId, connector.currentTransactionId)
        assertEquals(ChargePointStatus.Charging, connector.status)

        // Stop transaction
        val stopRequest = StopTransactionRequest(
            transactionId = startResponse.transactionId,
            timestamp = "2025-11-16T15:30:00Z",
            meterStop = 1000
        )
        sessionManager.handleStopTransaction("CP001", stopRequest)

        // Verify connector is unlinked
        val updatedConnector = session.connectors[1]
        assertNotNull(updatedConnector)
        assertNull(updatedConnector.currentTransactionId)
        assertEquals(ChargePointStatus.Available, updatedConnector.status)
    }

    @Test
    fun testBootNotificationUpdatesSessionState() = runTest {
        sessionManager.registerSession("CP001", mockSession)

        val request = BootNotificationRequest(
            chargePointVendor = "TestVendor",
            chargePointModel = "TestModel"
        )

        sessionManager.handleBootNotification("CP001", request)

        val session = sessionManager.getSession("CP001")
        assertNotNull(session)
        assertTrue(session.bootNotificationReceived)
        assertEquals(RegistrationStatus.Accepted, session.registrationStatus)
    }

    @Test
    fun testHeartbeatUpdatesLastHeartbeat() = runTest {
        sessionManager.registerSession("CP001", mockSession)

        val session = sessionManager.getSession("CP001")
        assertNotNull(session)
        val initialHeartbeat = session.lastHeartbeat

        // Wait a bit and send heartbeat
        kotlinx.coroutines.delay(100)
        sessionManager.handleHeartbeat("CP001")

        val updatedSession = sessionManager.getSession("CP001")
        assertNotNull(updatedSession)
        assertTrue(updatedSession.lastHeartbeat > initialHeartbeat)
    }
}