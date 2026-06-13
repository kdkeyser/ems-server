package io.konektis.devices.charger

import io.konektis.devices.Ampere
import io.konektis.devices.Watt
import io.konektis.ocpp.ChargePointStatus
import io.konektis.ocpp.ChargingRateUnitType
import io.konektis.ocpp.OcppService
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class OcppChargerTest {

    @Test
    fun getStateReturnsNullUntilPowerSeen() = runTest {
        val svc = mockk<OcppService>()
        every { svc.latestPowerW("CP1", 1) } returns null
        every { svc.connectorStatus("CP1", 1) } returns null
        val charger = OcppCharger("CP1", 1, svc)
        charger.update()
        assertNull(charger.getState())
    }

    @Test
    fun getStateReflectsLatestPower() = runTest {
        val svc = mockk<OcppService>()
        every { svc.latestPowerW("CP1", 1) } returns 2300
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
        every { svc.latestPowerW("CP1", 1) } returns null
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
        every { svc.latestPowerW("CP1", 1) } returns 7000
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
        every { svc.latestPowerW("CP1", 1) } returns 7000 // stale value left over from the finished session
        every { svc.connectorStatus("CP1", 1) } returns ChargePointStatus.Finishing
        every { svc.activeTransactionId("CP1", 1) } returns null // session ended: no open transaction
        val charger = OcppCharger("CP1", 1, svc)
        val state = charger.getState()
        assertEquals(0, state?.update?.currentPower?.value)
        assertEquals(ChargerConnection.Connected, state?.update?.connection)
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
