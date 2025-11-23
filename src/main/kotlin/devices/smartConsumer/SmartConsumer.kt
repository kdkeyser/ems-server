package io.konektis.devices.smartConsumers

import io.konektis.devices.DeviceUpdate
import io.konektis.devices.Watt

sealed class ConsumeMode {
    object Unrestricted : ConsumeMode()
    data class SuggestConsumeUpTo(val power: Watt) : ConsumeMode()
}

data class SmartConsumerState(
    val power: Watt,
    val consumeMode : ConsumeMode
    )

interface SmartConsumer {
    suspend fun update()
    val state: DeviceUpdate<SmartConsumerState>?
    suspend fun setConsumeMode(consumeMode: ConsumeMode)
}