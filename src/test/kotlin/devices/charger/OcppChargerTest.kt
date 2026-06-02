package io.konektis.devices.charger

import io.konektis.devices.Watt
import io.konektis.ocpp.ChargingRateUnitType
import io.konektis.ocpp.OcppService
import io.konektis.ocpp.db.ChargerSettingsRecord
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class OcppChargerTest {

    @Test
    fun getStateReturnsNullUntilPowerSeen() = runTest {
        val svc = mockk<OcppService>()
        every { svc.latestPowerW("CP1", 1) } returns null
        val charger = OcppCharger("CP1", 1, svc)
        charger.update()
        assertNull(charger.getState())
    }

    @Test
    fun getStateReflectsLatestPower() = runTest {
        val svc = mockk<OcppService>()
        every { svc.latestPowerW("CP1", 1) } returns 2300
        val charger = OcppCharger("CP1", 1, svc)
        charger.update()
        assertEquals(2300, charger.getState()?.update?.currentPower?.value)
    }

    @Test
    fun setMaxPowerSendsChargingProfileWhenCapable() = runTest {
        val svc = mockk<OcppService>()
        every { svc.isPowerControlCapable("CP1") } returns true
        coEvery { svc.getChargerSettings("CP1") } returns null
        coEvery { svc.setChargingProfile("CP1", 1, any(), any()) } returns true
        val charger = OcppCharger("CP1", 1, svc)

        charger.setMaxChargerPower(Watt(3680)) // 16A * 230V

        coVerify { svc.setChargingProfile("CP1", 1, 16.0, ChargingRateUnitType.A) }
    }

    @Test
    fun setMaxPowerNoOpWhenNotCapable() = runTest {
        val svc = mockk<OcppService>()
        every { svc.isPowerControlCapable("CP1") } returns false
        val charger = OcppCharger("CP1", 1, svc)

        charger.setMaxChargerPower(Watt(3680))

        coVerify(exactly = 0) { svc.setChargingProfile(any(), any(), any(), any()) }
    }

    @Test
    fun setMaxPowerNoOpWhenEmsAutoControlDisabled() = runTest {
        val svc = mockk<OcppService>()
        every { svc.isPowerControlCapable("CP1") } returns true
        coEvery { svc.getChargerSettings("CP1") } returns ChargerSettingsRecord("CP1", maxCurrentA = 32, emsAutoControl = false)
        val charger = OcppCharger("CP1", 1, svc)

        charger.setMaxChargerPower(Watt(3680))

        coVerify(exactly = 0) { svc.setChargingProfile(any(), any(), any(), any()) }
    }

    @Test
    fun setMaxPowerClampsToConfiguredMaxCurrent() = runTest {
        val svc = mockk<OcppService>()
        every { svc.isPowerControlCapable("CP1") } returns true
        coEvery { svc.getChargerSettings("CP1") } returns ChargerSettingsRecord("CP1", maxCurrentA = 10, emsAutoControl = true)
        coEvery { svc.setChargingProfile("CP1", 1, any(), any()) } returns true
        val charger = OcppCharger("CP1", 1, svc)

        charger.setMaxChargerPower(Watt(3680)) // would be 16A, clamped to 10A

        coVerify { svc.setChargingProfile("CP1", 1, 10.0, ChargingRateUnitType.A) }
    }
}
