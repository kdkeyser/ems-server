package io.konektis.devices.charger

import io.konektis.GlobalTimeSource
import io.konektis.devices.Ampere
import io.konektis.devices.Watt
import io.konektis.ocpp.ChargePointStatus
import io.konektis.ocpp.ChargingRateUnitType
import io.konektis.ocpp.OcppService
import io.konektis.ocpp.PowerReading
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.TestTimeSource
import kotlin.time.Duration.Companion.seconds

class OcppChargerTest {

    @Test
    fun getStateReturnsNullUntilPowerSeen() = runTest {
        val svc = mockk<OcppService>()
        every { svc.latestPowerReading("CP1", 1) } returns null
        every { svc.connectorStatus("CP1", 1) } returns null
        val charger = OcppCharger("CP1", 1, svc)
        charger.update()
        assertNull(charger.getState())
    }

    @Test
    fun getStateReflectsLatestPower() = runTest {
        val svc = mockk<OcppService>()
        every { svc.latestPowerReading("CP1", 1) } returns PowerReading(2300, GlobalTimeSource.source.markNow())
        every { svc.connectorStatus("CP1", 1) } returns null
        every { svc.activeTransactionId("CP1", 1) } returns 99 // session open: live reading is trusted
        val charger = OcppCharger("CP1", 1, svc)
        charger.update()
        assertEquals(2300, charger.getState()?.update?.currentPower?.value)
    }

    @Test
    fun `chargerConnectionFrom maps OCPP statuses`() {
        assertEquals(ChargerConnection.NotConnected, chargerConnectionFrom(ChargePointStatus.Available))
        assertEquals(ChargerConnection.NotConnected, chargerConnectionFrom(ChargePointStatus.Reserved))
        assertEquals(ChargerConnection.NotConnected, chargerConnectionFrom(ChargePointStatus.Unavailable))
        assertEquals(ChargerConnection.Connected, chargerConnectionFrom(ChargePointStatus.Preparing))
        assertEquals(ChargerConnection.Connected, chargerConnectionFrom(ChargePointStatus.SuspendedEV))
        assertEquals(ChargerConnection.Connected, chargerConnectionFrom(ChargePointStatus.SuspendedEVSE))
        assertEquals(ChargerConnection.Connected, chargerConnectionFrom(ChargePointStatus.Finishing))
        assertEquals(ChargerConnection.Charging, chargerConnectionFrom(ChargePointStatus.Charging))
        assertEquals(ChargerConnection.Unknown, chargerConnectionFrom(ChargePointStatus.Faulted))
        assertEquals(ChargerConnection.Unknown, chargerConnectionFrom(null))
    }

    @Test
    fun `getState surfaces connection even with no power yet`() = runTest {
        val svc = mockk<OcppService>()
        every { svc.latestPowerReading("CP1", 1) } returns null
        every { svc.connectorStatus("CP1", 1) } returns ChargePointStatus.Preparing
        every { svc.activeTransactionId("CP1", 1) } returns null
        val charger = OcppCharger("CP1", 1, svc)
        charger.update()
        val state = charger.getState()
        assertEquals(0, state?.update?.currentPower?.value)
        assertEquals(ChargerConnection.Connected, state?.update?.connection)
    }

    @Test
    fun `getState reflects charging connection with power`() = runTest {
        val svc = mockk<OcppService>()
        every { svc.latestPowerReading("CP1", 1) } returns PowerReading(7000, GlobalTimeSource.source.markNow())
        every { svc.connectorStatus("CP1", 1) } returns ChargePointStatus.Charging
        every { svc.activeTransactionId("CP1", 1) } returns 7 // session open: live reading is trusted
        val charger = OcppCharger("CP1", 1, svc)
        charger.update()
        val state = charger.getState()
        assertEquals(7000, state?.update?.currentPower?.value)
        assertEquals(ChargerConnection.Charging, state?.update?.connection)
    }

    @Test
    fun `getState reports zero power when no transaction is active even if a stale reading remains`() = runTest {
        val svc = mockk<OcppService>()
        every { svc.latestPowerReading("CP1", 1) } returns PowerReading(7000, GlobalTimeSource.source.markNow()) // leftover from the finished session
        every { svc.connectorStatus("CP1", 1) } returns ChargePointStatus.Finishing
        every { svc.activeTransactionId("CP1", 1) } returns null // session ended: no open transaction
        val charger = OcppCharger("CP1", 1, svc)
        val state = charger.getState()
        assertEquals(0, state?.update?.currentPower?.value)
        assertEquals(ChargerConnection.Connected, state?.update?.connection)
    }

    @Test
    fun `getState treats a frozen MeterValues reading as unreadable mid-transaction`() = runTest {
        val ts = TestTimeSource()
        val staleMark = ts.markNow()
        ts += 120.seconds // older than the 90 s meter-staleness bound
        val svc = mockk<OcppService>()
        every { svc.latestPowerReading("CP1", 1) } returns PowerReading(7000, staleMark)
        every { svc.connectorStatus("CP1", 1) } returns ChargePointStatus.Charging
        every { svc.activeTransactionId("CP1", 1) } returns 7
        val charger = OcppCharger("CP1", 1, svc)
        assertNull(charger.getState(),
            "a charger frozen mid-transaction must read as unreadable, not as the last value forever")
    }

    @Test
    fun setMaxPowerSendsChargingProfileWhenCapable() = runTest {
        val svc = mockk<OcppService>()
        every { svc.activeTransactionId("CP1", 1) } returns 7 // session already open: no start attempt
        every { svc.isPowerControlCapable("CP1") } returns true
        coEvery { svc.setChargingProfile("CP1", 1, any(), any()) } returns true
        val charger = OcppCharger("CP1", 1, svc)

        charger.apply(ChargerCommand.Charge(Ampere(16)))

        coVerify { svc.setChargingProfile("CP1", 1, 16.0, ChargingRateUnitType.A) }
    }

    @Test
    fun setMaxPowerNoOpWhenNotCapable() = runTest {
        val svc = mockk<OcppService>()
        every { svc.activeTransactionId("CP1", 1) } returns 7 // session already open: no start attempt
        every { svc.isPowerControlCapable("CP1") } returns false
        val charger = OcppCharger("CP1", 1, svc)

        charger.apply(ChargerCommand.Charge(Ampere(16)))

        coVerify(exactly = 0) { svc.setChargingProfile(any(), any(), any(), any()) }
    }

    @Test
    fun `positive amps starts a transaction when a car is connected but none is open`() = runTest {
        val svc = mockk<OcppService>()
        every { svc.activeTransactionId("CP1", 1) } returns null
        every { svc.connectorStatus("CP1", 1) } returns ChargePointStatus.Preparing
        every { svc.isPowerControlCapable("CP1") } returns true
        coEvery { svc.remoteStart("CP1", "EMS", 1) } returns true
        coEvery { svc.setChargingProfile("CP1", 1, any(), any()) } returns true
        val charger = OcppCharger("CP1", 1, svc)

        charger.apply(ChargerCommand.Charge(Ampere(16)))

        coVerify { svc.remoteStart("CP1", "EMS", 1) }
    }

    @Test
    fun `positive amps does not start a transaction when one is already active`() = runTest {
        val svc = mockk<OcppService>()
        every { svc.activeTransactionId("CP1", 1) } returns 42
        every { svc.isPowerControlCapable("CP1") } returns true
        coEvery { svc.setChargingProfile("CP1", 1, any(), any()) } returns true
        val charger = OcppCharger("CP1", 1, svc)

        charger.apply(ChargerCommand.Charge(Ampere(16)))

        coVerify(exactly = 0) { svc.remoteStart(any(), any(), any()) }
    }

    @Test
    fun `positive amps does not start a transaction when no car is connected`() = runTest {
        val svc = mockk<OcppService>()
        every { svc.activeTransactionId("CP1", 1) } returns null
        every { svc.connectorStatus("CP1", 1) } returns ChargePointStatus.Available
        every { svc.isPowerControlCapable("CP1") } returns true
        coEvery { svc.setChargingProfile("CP1", 1, any(), any()) } returns true
        val charger = OcppCharger("CP1", 1, svc)

        charger.apply(ChargerCommand.Charge(Ampere(16)))

        coVerify(exactly = 0) { svc.remoteStart(any(), any(), any()) }
    }

    @Test
    fun `unchanged amps are not resent every tick`() = runTest {
        val svc = mockk<OcppService>()
        every { svc.activeTransactionId("CP1", 1) } returns 7
        every { svc.isPowerControlCapable("CP1") } returns true
        coEvery { svc.setChargingProfile("CP1", 1, any(), any()) } returns true
        val charger = OcppCharger("CP1", 1, svc)

        charger.apply(ChargerCommand.Charge(Ampere(16)))
        charger.apply(ChargerCommand.Charge(Ampere(16)))

        coVerify(exactly = 1) { svc.setChargingProfile("CP1", 1, 16.0, ChargingRateUnitType.A) }
    }

    @Test
    fun `changed amps are sent immediately`() = runTest {
        val svc = mockk<OcppService>()
        every { svc.activeTransactionId("CP1", 1) } returns 7
        every { svc.isPowerControlCapable("CP1") } returns true
        coEvery { svc.setChargingProfile("CP1", 1, any(), any()) } returns true
        val charger = OcppCharger("CP1", 1, svc)

        charger.apply(ChargerCommand.Charge(Ampere(16)))
        charger.apply(ChargerCommand.Charge(Ampere(10)))

        coVerify(exactly = 1) { svc.setChargingProfile("CP1", 1, 16.0, ChargingRateUnitType.A) }
        coVerify(exactly = 1) { svc.setChargingProfile("CP1", 1, 10.0, ChargingRateUnitType.A) }
    }

    @Test
    fun `rejected profile is retried on the next tick`() = runTest {
        val svc = mockk<OcppService>()
        every { svc.activeTransactionId("CP1", 1) } returns 7
        every { svc.isPowerControlCapable("CP1") } returns true
        coEvery { svc.setChargingProfile("CP1", 1, any(), any()) } returnsMany listOf(false, true)
        val charger = OcppCharger("CP1", 1, svc)

        charger.apply(ChargerCommand.Charge(Ampere(16)))
        charger.apply(ChargerCommand.Charge(Ampere(16)))

        coVerify(exactly = 2) { svc.setChargingProfile("CP1", 1, 16.0, ChargingRateUnitType.A) }
    }

    @Test
    fun `rejected RemoteStart is not retried within the backoff window`() = runTest {
        val svc = mockk<OcppService>()
        every { svc.activeTransactionId("CP1", 1) } returns null
        every { svc.connectorStatus("CP1", 1) } returns ChargePointStatus.Preparing
        every { svc.isPowerControlCapable("CP1") } returns true
        coEvery { svc.remoteStart("CP1", "EMS", 1) } returns false
        coEvery { svc.setChargingProfile("CP1", 1, any(), any()) } returns true
        val charger = OcppCharger("CP1", 1, svc)

        charger.apply(ChargerCommand.Charge(Ampere(16)))
        charger.apply(ChargerCommand.Charge(Ampere(16))) // next 5 s tick, still inside 30 s backoff

        coVerify(exactly = 1) { svc.remoteStart("CP1", "EMS", 1) }
    }

    @Test
    fun `no RemoteStart on an Unknown (faulted) connector`() = runTest {
        val svc = mockk<OcppService>()
        every { svc.activeTransactionId("CP1", 1) } returns null
        every { svc.connectorStatus("CP1", 1) } returns ChargePointStatus.Faulted
        every { svc.isPowerControlCapable("CP1") } returns true
        coEvery { svc.setChargingProfile("CP1", 1, any(), any()) } returns true
        val charger = OcppCharger("CP1", 1, svc)

        charger.apply(ChargerCommand.Charge(Ampere(16)))

        coVerify(exactly = 0) { svc.remoteStart(any(), any(), any()) }
    }

    @Test
    fun `Stop stops the active transaction and sends no profile`() = runTest {
        val svc = mockk<OcppService>()
        every { svc.activeTransactionId("CP1", 1) } returns 42
        coEvery { svc.remoteStop("CP1", 42) } returns true
        val charger = OcppCharger("CP1", 1, svc)

        charger.apply(ChargerCommand.Stop)

        coVerify { svc.remoteStop("CP1", 42) }
        // Stopping the transaction is the off switch; no redundant 0 A profile.
        coVerify(exactly = 0) { svc.setChargingProfile(any(), any(), any(), any()) }
    }

    @Test
    fun `Stop is a no-op when no transaction is open`() = runTest {
        val svc = mockk<OcppService>()
        every { svc.activeTransactionId("CP1", 1) } returns null
        val charger = OcppCharger("CP1", 1, svc)

        charger.apply(ChargerCommand.Stop)

        coVerify(exactly = 0) { svc.remoteStop(any(), any()) }
        coVerify(exactly = 0) { svc.setChargingProfile(any(), any(), any(), any()) }
    }

}
