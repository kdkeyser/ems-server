package io.konektis.ems

import io.klogging.Klogging
import io.konektis.config.Config
import io.konektis.devices.World
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.time.delay
import java.time.Duration

enum class Mode {
    AUTO, MANUAL
}

class EnergyManager(
    private val world: World,
    private val config: Config
) : Klogging {

    var mode = Mode.AUTO
    val emsStateFlow = MutableStateFlow(EMSState(null, null, null, null, null, null, null))

    suspend fun run() {
        while (true) {
            emsStateFlow.value = buildEMSState()
            delay(Duration.ofSeconds(5))
        }
    }

    suspend fun buildEMSState(): EMSState {
        val gridState = world.grid.getState()?.update
        val solarStates = world.solar.values.mapNotNull { it.getState()?.update?.power?.value }
        val solarPower = if (solarStates.isEmpty()) null else solarStates.sum()
        val batteryState = world.batteries.values.firstOrNull()?.getState()?.update
        val chargerState = world.chargers.values.firstOrNull()?.getState()?.update
        val heatpumpState = world.smartConsumers.values.firstOrNull()?.getState()?.update

        return EMSState(
            gridPower = gridState?.power?.value,
            gridVoltage = gridState?.voltage?.value?.toInt(),
            chargerPower = chargerState?.currentPower?.value,
            heatpumpPower = heatpumpState?.power?.value,
            solarPower = solarPower,
            batteryPower = batteryState?.power?.value,
            batteryCharge = batteryState?.charge?.toInt()
        )
    }
}
