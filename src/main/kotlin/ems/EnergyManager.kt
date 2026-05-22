package io.konektis.ems

import io.klogging.Klogging
import io.konektis.config.Config
import io.konektis.devices.Watt
import io.konektis.devices.World
import io.konektis.devices.smartConsumers.ConsumeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.time.delay
import java.time.Duration

enum class Mode {
    AUTO, MANUAL
}

class EnergyManager(
    private val world: World,
    private val config: Config,
    private val strategy: Strategy
) : Klogging {

    var mode = Mode.AUTO
    val emsStateFlow = MutableStateFlow(EMSState(null, null, null, null, null, null, null))

    suspend fun run() {
        while (true) {
            val emsState = buildEMSState()
            emsStateFlow.value = emsState

            if (mode == Mode.AUTO) {
                val snapshot = buildWorldSnapshot(emsState)
                if (snapshot == null) {
                    logger.warn("Incomplete device state — skipping optimization tick")
                    logger.warn("State was $emsState")
                } else {
                    applyDecisions(strategy.decide(snapshot))
                }
            }

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

    private fun buildWorldSnapshot(emsState: EMSState): WorldSnapshot? {
        val chargerConfig = config.devices.charger.firstOrNull() ?: return null
        if (emsState.gridPower == null || emsState.chargerPower == null) return null
        return WorldSnapshot(
            gridPower = Watt(emsState.gridPower),
            solarPower = Watt(emsState.solarPower ?: 0),
            batteryCharge = (emsState.batteryCharge ?: 0).toUShort(),
            batteryPower = Watt(emsState.batteryPower ?: 0),
            chargerPower = Watt(emsState.chargerPower),
            heatpumpPower = Watt(emsState.heatpumpPower ?: 0),
            chargerMinAmps = chargerConfig.chargingCurrent.min.toInt(),
            chargerMaxAmps = chargerConfig.chargingCurrent.max.toInt()
        )
    }

    private suspend fun applyDecisions(decisions: ControlDecisions) {
        decisions.chargerMaxAmps?.let { amps ->
            world.chargers.values.forEach { charger ->
                try {
                    charger.setMaxChargerPower(Watt(amps * 230))
                } catch (e: Exception) {
                    logger.error("Failed to set charger power", e)
                }
            }
        }
        decisions.batteryTargetPower?.let { power ->
            world.batteries.values.forEach { battery ->
                try {
                    battery.setChargingPower(power)
                } catch (e: Exception) {
                    logger.error("Failed to set battery power", e)
                }
            }
        }
        decisions.heatpumpConsumeMode?.let { consumeMode ->
            world.smartConsumers.values.forEach { consumer ->
                try {
                    consumer.setConsumeMode(consumeMode)
                } catch (e: Exception) {
                    logger.error("Failed to set heat pump mode", e)
                }
            }
        }
    }
}
