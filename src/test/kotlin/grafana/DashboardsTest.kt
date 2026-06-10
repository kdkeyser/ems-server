package io.konektis.grafana

import kotlinx.serialization.json.*
import java.io.File
import kotlin.test.*

class DashboardsTest {
    private val dir = File("deploy/grafana/dashboards")

    private fun dashboards(): List<File> =
        dir.listFiles { f -> f.extension == "json" }?.toList().orEmpty()

    @Test
    fun thereAreThreeDashboards() {
        assertEquals(
            setOf("power-flow.json", "energy-balance.json", "battery-devices.json"),
            dashboards().map { it.name }.toSet(),
        )
    }

    @Test
    fun everyDashboardIsValidAndWiredToTheDatasource() {
        assertTrue(dashboards().isNotEmpty())
        for (f in dashboards()) {
            val root = Json.parseToJsonElement(f.readText()).jsonObject // throws if malformed
            assertNotNull(root["uid"], "${f.name} needs a uid")
            val panels = root["panels"]!!.jsonArray
            assertTrue(panels.isNotEmpty(), "${f.name} has no panels")
            for (panel in panels) {
                val targets = panel.jsonObject["targets"]?.jsonArray ?: continue
                for (t in targets) {
                    val ds = t.jsonObject["datasource"]!!.jsonObject["uid"]!!.jsonPrimitive.content
                    assertEquals("ems-clickhouse", ds, "${f.name}: target must use the EMS datasource")
                    val sql = t.jsonObject["rawSql"]!!.jsonPrimitive.content
                    assertTrue(sql.contains("ems."), "${f.name}: rawSql must read an ems.* table")
                }
            }
        }
    }
}
