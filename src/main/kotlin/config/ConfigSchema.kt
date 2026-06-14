package io.konektis.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind

/**
 * A machine-readable description of how a config object is shaped, derived entirely from the
 * `@Serializable` descriptors of the config model. The frontend renders forms from this, so adding a
 * device type or a field — or a whole new device kind — needs no frontend change: the schema (and the
 * deserialiser that backs it) are the single source of truth.
 *
 * Conditional rules that a descriptor can't express (e.g. a charger needs `host` for WebastoUnite but
 * `chargePointId` for OCPP) are intentionally *not* encoded here; they live in [Config.validate] and
 * surface as 422s. The form stays fully generic and the server remains the authority.
 */
@Serializable
data class FieldSchema(
    val name: String,
    /** One of: string, int, long, double, boolean, enum, object. */
    val type: String,
    val required: Boolean,
    val nullable: Boolean,
    val enumValues: List<String>? = null,
    /** Nested fields when [type] is "object". */
    val fields: List<FieldSchema>? = null,
)

@Serializable
data class ObjectSchema(val name: String, val fields: List<FieldSchema>)

@Serializable
data class ConfigSchema(
    val grid: ObjectSchema,
    /** Keyed by API device kind (solar, charger, battery, heatPump, car) → its object shape. */
    val deviceKinds: Map<String, ObjectSchema>,
)

/** Builds the [ConfigSchema] by walking the serializers' descriptors. Cheap; safe to cache. */
object ConfigSchemaBuilder {
    fun build(): ConfigSchema = ConfigSchema(
        grid = objectSchema(Grid.serializer().descriptor),
        deviceKinds = linkedMapOf(
            "solar" to objectSchema(Solar.serializer().descriptor),
            "charger" to objectSchema(Charger.serializer().descriptor),
            "battery" to objectSchema(Battery.serializer().descriptor),
            "heatPump" to objectSchema(HeatPump.serializer().descriptor),
            "car" to objectSchema(Car.serializer().descriptor),
        ),
    )

    private fun objectSchema(d: SerialDescriptor): ObjectSchema =
        ObjectSchema(d.serialName.substringAfterLast('.').removeSuffix("?"), fields(d))

    private fun fields(d: SerialDescriptor): List<FieldSchema> =
        (0 until d.elementsCount).map { i ->
            field(d.getElementName(i), d.getElementDescriptor(i), d.isElementOptional(i))
        }

    private fun field(name: String, d: SerialDescriptor, optional: Boolean): FieldSchema {
        val nullable = d.isNullable
        // A field is required for the client only if it has neither a default nor nullability.
        val required = !optional && !nullable
        return when (d.kind) {
            PrimitiveKind.BOOLEAN -> FieldSchema(name, "boolean", required, nullable)
            PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT ->
                FieldSchema(name, "int", required, nullable)
            PrimitiveKind.LONG -> FieldSchema(name, "long", required, nullable)
            PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> FieldSchema(name, "double", required, nullable)
            PrimitiveKind.CHAR, PrimitiveKind.STRING -> FieldSchema(name, "string", required, nullable)
            SerialKind.ENUM -> FieldSchema(
                name, "enum", required, nullable,
                enumValues = (0 until d.elementsCount).map { d.getElementName(it) },
            )
            StructureKind.CLASS, StructureKind.OBJECT ->
                FieldSchema(name, "object", required, nullable, fields = fields(d))
            // Lists/maps aren't used inside a single device object; treat anything else as text.
            else -> FieldSchema(name, "string", required, nullable)
        }
    }
}
