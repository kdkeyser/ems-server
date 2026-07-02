package io.konektis.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ConfigSchemaTest {
    private val schema = ConfigSchemaBuilder.build()
    private fun field(obj: ObjectSchema, name: String) = obj.fields.single { it.name == name }

    @Test fun `exposes every device kind`() {
        assertEquals(setOf("solar", "charger", "battery", "heatPump", "car"), schema.deviceKinds.keys)
    }

    @Test fun `enum fields carry their options`() {
        val type = field(schema.deviceKinds.getValue("solar"), "type")
        assertEquals("enum", type.type)
        assertEquals(listOf("SMA_Sunny_Boy"), type.enumValues)
        assertTrue(type.required)
    }

    @Test fun `charger type enum lists both variants`() {
        val type = field(schema.deviceKinds.getValue("charger"), "type")
        assertEquals(listOf("WebastoUnite", "OCPP"), type.enumValues)
    }

    @Test fun `fields with a default are optional, fields without are required`() {
        val charger = schema.deviceKinds.getValue("charger")
        assertTrue(field(charger, "name").required, "name has no default")
        // host / chargePointId default to null; connectorId defaults to 1 -> all optional.
        assertTrue(!field(charger, "host").required && field(charger, "host").nullable)
        assertTrue(!field(charger, "chargePointId").required)
        assertTrue(!field(charger, "connectorId").required)
    }

    @Test fun `nested objects are described recursively`() {
        val cc = field(schema.deviceKinds.getValue("charger"), "chargingCurrent")
        assertEquals("object", cc.type)
        val nested = assertNotNull(cc.fields)
        assertEquals(setOf("min", "max"), nested.map { it.name }.toSet())
        assertEquals("double", nested.single { it.name == "min" }.type)
    }

    @Test fun `field types map to primitives`() {
        val car = schema.deviceKinds.getValue("car")
        assertEquals("boolean", field(car, "enabled").type)
        assertEquals("int", field(car, "brokerPort").type)
        assertEquals("string", field(car, "vin").type)
    }

    @Test fun `grid is described too`() {
        assertEquals(setOf("type", "host"), schema.grid.fields.map { it.name }.toSet())
        assertEquals("enum", field(schema.grid, "type").type)
    }
}
