package io.konektis.cardata

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class CarDataServiceStartTest {

    @Test fun `start swallows auth failure instead of crashing the caller`() = runTest {
        val auth = mockk<CarDataAuth>()
        coEvery { auth.ensureAuthorized() } throws IllegalStateException("device authorization timed out")
        val svc = CarDataService(
            CarDataConfig(enabled = true, clientId = "c", vin = "v", socDescriptor = "d"),
            mockk(relaxed = true), auth, mockk(relaxed = true),
        )
        svc.start() // must return normally — a CarData failure may not cancel the EMS control loops
    }
}
