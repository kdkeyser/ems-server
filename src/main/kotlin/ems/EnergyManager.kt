package io.konektis.ems

import io.klogging.Klogging
import io.konektis.ManagerMode
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

    // setMode() is called from the WebSocket coroutine; tick() reads mode from the run() loop.
    @Volatile
    var mode = Mode.AUTO
        private set
    private var previousMode = Mode.AUTO
    private var blindTicks = 0

    val emsStateFlow = MutableStateFlow(EMSState(null, null, null, null, null, null, null))
    val modeFlow = MutableStateFlow(ManagerMode.AUTO)

    fun setMode(newMode: Mode) {
        mode = newMode
        modeFlow.value = if (newMode == Mode.AUTO) ManagerMode.AUTO else ManagerMode.MANUAL
    }

    suspend fun run() {
        while (true) {
            tick()
            delay(Duration.ofSeconds(5))
        }
    }

    /** One control cycle. Extracted so tests can drive it without the timing loop. */
    suspend fun tick() {
        val emsState = buildEMSState()
        emsStateFlow.value = emsState

        // AUTO -> MANUAL transition: hand everything back to its own logic, once.
        if (mode == Mode.MANUAL && previousMode == Mode.AUTO) {
            releaseAll()
        }
        previousMode = mode

        if (mode != Mode.AUTO) return

        when {
            // Tier 3 — blind: grid or battery reading missing. Fail toward the inverter.
            emsState.gridPower == null || emsState.batteryPower == null -> {
                blindTicks++
                if (blindTicks >= BLIND_RELEASE_TICKS) {
                    // Retry every blind tick past the threshold: SMABattery.releaseToInverter() is a
                    // no-op once released, so a failed 803 write is retried until it succeeds rather
                    // than leaving the battery armed with no steering (fail toward the inverter).
                    logger.warn("Blind for $blindTicks ticks (>= $BLIND_RELEASE_TICKS) — releasing battery to inverter")
                    world.batteries.values.forEach { battery ->
                        runCatchingLog("release battery") { battery.releaseToInverter() }
                    }
                }
            }
            // Tier 1 — full data: run the surplus cascade.
            emsState.chargerPower != null && emsState.heatpumpPower != null -> {
                blindTicks = 0
                val snapshot = buildWorldSnapshot(emsState)
                if (snapshot != null) {
                    val decisions = strategy.decide(snapshot)
                    logger.debug(
                        "control: grid=${emsState.gridPower}W battery=${emsState.batteryPower}W " +
                            "-> ${decisions.batteryCommand} (target >0 = charge, <0 = discharge)"
                    )
                    applyDecisions(decisions)
                }
            }
            // Tier 2 — degraded: grid + battery present; steer the battery only.
            else -> {
                blindTicks = 0
                val target = strategy.decideDegraded(Watt(emsState.gridPower!!), Watt(emsState.batteryPower!!))
                logger.debug(
                    "control(degraded): grid=${emsState.gridPower}W battery=${emsState.batteryPower}W " +
                        "-> target=${target.value}W (>0 = charge, <0 = discharge)"
                )
                world.batteries.values.forEach { battery ->
                    runCatchingLog("set battery") { battery.setChargingPower(target) }
                }
            }
        }
    }

    private suspend fun releaseAll() {
        world.batteries.values.forEach { battery ->
            runCatchingLog("release battery") { battery.releaseToInverter() }
        }
        world.smartConsumers.values.forEach { consumer ->
            runCatchingLog("heatpump normal") { consumer.setConsumeMode(ConsumeMode.Unrestricted) }
        }
        // TODO: charger release is best-effort. A full revert needs stopping the Webasto
        // keepalive (register 6000); not implemented. We simply stop sending setpoints.
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
        decisions.batteryCommand?.let { cmd ->
            world.batteries.values.forEach { battery ->
                try {
                    when (cmd) {
                        is BatteryCommand.SetPower -> battery.setChargingPower(cmd.power)
                        BatteryCommand.ReleaseToInverter -> battery.releaseToInverter()
                    }
                } catch (e: Exception) {
                    logger.error("Failed to apply battery command", e)
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

    private suspend fun runCatchingLog(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            logger.error("Failed to $what", e)
        }
    }

    companion object {
        const val BLIND_RELEASE_TICKS = 6  // ~30s at 5s cadence
    }
}
