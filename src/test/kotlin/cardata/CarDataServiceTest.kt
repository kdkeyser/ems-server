package io.konektis.cardata

import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class CarDataServiceTest {
    private fun service(descriptor: String = "soc.desc"): CarDataService {
        val cfg = CarDataConfig(enabled = true, clientId = "cid", vin = "WBA1", socDescriptor = descriptor)
        return CarDataService(cfg, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
    }

    @Test
    fun socFlowStartsNull() {
        assertNull(service().socFlow.value)
    }

    @Test
    fun onMessageUpdatesSocFlowForConfiguredDescriptor() = runTest {
        val svc = service("soc.desc")
        svc.onMessage("""{"vin":"WBA1","data":{"soc.desc":{"value":64}}}""")
        assertEquals(64, svc.socFlow.value)
    }

    @Test
    fun onMessageIgnoresUnrelatedDescriptor() = runTest {
        val svc = service("soc.desc")
        svc.onMessage("""{"vin":"WBA1","data":{"soc.desc":{"value":40}}}""")
        svc.onMessage("""{"vin":"WBA1","data":{"other":{"value":99}}}""") // no soc -> keep last
        assertEquals(40, svc.socFlow.value)
    }
}
