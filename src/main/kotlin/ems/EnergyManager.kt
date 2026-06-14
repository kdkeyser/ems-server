package io.konektis.ems

import io.klogging.Klogging
import io.konektis.cardata.CarDataService
import io.konektis.ChargerControl
import io.konektis.ChargerMode
import io.konektis.ManagerMode
import io.konektis.config.Config
import io.konektis.devices.Ampere
import io.konektis.devices.Watt
import io.konektis.devices.World
import io.konektis.devices.WorldHolder
import io.konektis.devices.charger.ChargerCommand
import io.konektis.devices.charger.ChargerConnection
import io.konektis.devices.smartConsumers.ConsumeMode
import io.konektis.ocpp.db.ChargerControlStore
import io.konektis.devices.DeviceUpdate
import io.konektis.history.TimestampedEmsState
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

class EnergyManager(
    private val worldHolder: WorldHolder,
    private val configProvider: () -> Config,
    private val strategy: Strategy,
    private val chargerControlStore: ChargerControlStore,
    private val carDataService: CarDataService? = null,
) : Klogging {

    /** Fixed-graph convenience (tests and any caller without hot-reload): static world + config. */
    constructor(
        world: World, config: Config, strategy: Strategy,
        chargerControlStore: ChargerControlStore, carDataService: CarDataService? = null,
    ) : this(WorldHolder(world), { config }, strategy, chargerControlStore, carDataService)

    // The live device graph and config, re-read on every access so a hot-reload is picked up. Strategy
    // and the per-device thread pool stay boot-fixed (a strategy/refreshThreads change needs a restart).
    private val world: World get() = worldHolder.current
    private val config: Config get() = configProvider()

    private var previousMode = ManagerMode.AUTO
    private var blindTicks = 0

    val emsStateFlow = MutableStateFlow(EMSState(null, null, null, null, null, null, null))
    val modeFlow = MutableStateFlow(ManagerMode.AUTO)

    /**
     * Non-conflating tap of every tick for the history collector. Unlike [emsStateFlow] (a StateFlow
     * that drops values equal to the previous one), this emits on every tick so identical consecutive
     * states during quiet periods are still recorded. DROP_OLDEST keeps tick() non-blocking if no
     * collector is attached or it falls behind.
     */
    private val _emsHistoryFlow = MutableSharedFlow<TimestampedEmsState>(
        extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val emsHistoryFlow: SharedFlow<TimestampedEmsState> = _emsHistoryFlow.asSharedFlow()

    /** Latest car SoC (%) for the socket to push; a constant null flow when CarData is disabled. */
    val carSocFlow: StateFlow<Int?> = carDataService?.socFlow ?: MutableStateFlow<Int?>(null)

    private val chargerKey: String? =
        config.devices.charger.firstOrNull()?.let { it.chargePointId ?: it.name }

    private fun defaultControl() =
        ChargerControl(ChargerMode.SOLAR, config.devices.charger.firstOrNull()?.chargingCurrent?.max?.toInt() ?: 16, true)

    private val _chargerControlFlow = MutableStateFlow(defaultControl())
    val chargerControlFlow: StateFlow<ChargerControl> = _chargerControlFlow.asStateFlow()
    private val chargerControl: ChargerControl get() = _chargerControlFlow.value

    /** Load the persisted control on startup (call once before run()). */
    suspend fun loadChargerControl() {
        chargerControlStore.init()
        val key = chargerKey ?: return
        val rec = chargerControlStore.get(key) ?: return
        _chargerControlFlow.value = ChargerControl(
            mode = runCatching { ChargerMode.valueOf(rec.mode) }.getOrDefault(ChargerMode.SOLAR),
            fixedAmps = rec.fixedAmps,
            charging = rec.charging,
        )
    }

    suspend fun setCharging(control: ChargerControl) {
        _chargerControlFlow.value = control
        val key = chargerKey ?: return
        runCatchingLog("persist charger control") {
            chargerControlStore.put(key, control.mode.name, control.fixedAmps, control.charging)
        }
    }

    fun setMode(newMode: ManagerMode) {
        modeFlow.value = newMode
    }

    /** One control cycle. Called directly from the merged poll+control loop in Application. */
    suspend fun tick() {
        val emsState = buildEMSState()
        emsStateFlow.value = emsState
        _emsHistoryFlow.tryEmit(TimestampedEmsState(Instant.now(), emsState))

        val mode = modeFlow.value
        // AUTO -> MANUAL transition: hand everything back to its own logic, once.
        if (mode == ManagerMode.MANUAL && previousMode == ManagerMode.AUTO) {
            releaseAll()
        }
        previousMode = mode

        // No car connected -> force the charger off regardless of intent (don't push surplus to an
        // empty charger; keeps the battery's deadbeat projection exact). Connected/Charging/Unknown
        // (incl. non-OCPP chargers that report Unknown) -> use the intent-driven override.
        val connected = emsState.chargerConnection != ChargerConnection.NotConnected
        val override = configMaxAmps()?.let { effectiveChargerCommand(it, connected) }

        if (mode != ManagerMode.AUTO) {
            // MANUAL: independent charger control (battery/heat pump already released on transition).
            applyChargerCommand(override ?: ChargerCommand.Stop)
            return
        }

        when {
            // Tier 3 — blind: grid or battery reading missing. Fail toward the inverter.
            emsState.gridPower == null || emsState.batteryPower == null -> {
                blindTicks++
                if (blindTicks >= BLIND_RELEASE_TICKS) {
                    logger.warn("Blind for $blindTicks ticks (>= $BLIND_RELEASE_TICKS) — releasing battery to inverter")
                    world.battery?.let { battery ->
                        runCatchingLog("release battery") { battery.releaseToInverter() }
                    }
                }
                // Stop/Fixed are still enforced without surplus data; solar surplus (null) is left as-is.
                override?.let { applyChargerCommand(it) }
            }
            // Tier 1 — full data: run the surplus cascade with the charger override folded in.
            emsState.chargerPower != null && emsState.heatpumpPower != null -> {
                blindTicks = 0
                val snapshot = buildWorldSnapshot(emsState)
                if (snapshot != null) {
                    val decisions = strategy.decide(snapshot.copy(chargerOverride = override))
                    logger.debug(
                        "control: grid=${emsState.gridPower}W battery=${emsState.batteryPower}W " +
                            "charger=$override -> ${decisions.batteryCommand} (target >0 = charge, <0 = discharge)"
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
                world.battery?.let { battery ->
                    runCatchingLog("set battery") { battery.setChargingPower(target) }
                }
                // Stop/Fixed enforced; solar surplus (null) leaves the charger uncommanded.
                override?.let { applyChargerCommand(it) }
            }
        }
    }

    private suspend fun releaseAll() {
        world.battery?.let { battery ->
            runCatchingLog("release battery") { battery.releaseToInverter() }
        }
        world.heatPump?.let { consumer ->
            runCatchingLog("heatpump normal") { consumer.setConsumeMode(ConsumeMode.Unrestricted) }
        }
        // TODO: charger release is best-effort. A full revert needs stopping the Webasto
        // keepalive (register 6000); not implemented. We simply stop sending setpoints.
    }

    /** The reading, unless it is older than [STALE_AFTER] — then null, same as no reading at all. */
    private fun <T> fresh(u: DeviceUpdate<T>?): T? =
        u?.takeIf { it.collectedAt.elapsedNow() <= STALE_AFTER }?.update

    suspend fun buildEMSState(): EMSState {
        val gridState = fresh(world.grid.getState())
        val solarStates = world.solar.values.mapNotNull { fresh(it.getState())?.power?.value }
        val solarPower = if (solarStates.isEmpty()) null else solarStates.sum()
        val batteryState = fresh(world.battery?.getState())
        val chargerState = fresh(world.charger?.getState())
        val heatpumpState = fresh(world.heatPump?.getState())

        return EMSState(
            gridPower = gridState?.power?.value,
            gridVoltage = gridState?.voltage?.value?.toInt(),
            chargerPower = chargerState?.currentPower?.value,
            heatpumpPower = heatpumpState?.power?.value,
            solarPower = solarPower,
            batteryPower = batteryState?.power?.value,
            batteryCharge = batteryState?.charge?.toInt(),
            chargerConnection = chargerState?.connection,
            carCharge = carDataService?.socFlow?.value,
        )
    }

    /** Max charger amps from config, or null if no charger is configured. */
    private fun configMaxAmps(): Int? =
        if (world.charger != null) config.devices.charger.firstOrNull()?.chargingCurrent?.max?.toInt() else null

    /**
     * Effective forced charger command, or null to let the strategy compute the solar surplus.
     * No car or stopped -> Stop. Solar surplus only applies in AUTO; MANUAL auto-reverts to Fixed.
     */
    private fun effectiveChargerCommand(maxAmps: Int, connected: Boolean): ChargerCommand? {
        val c = chargerControl
        if (!connected || !c.charging) return ChargerCommand.Stop
        val chargerMode = if (modeFlow.value == ManagerMode.AUTO) c.mode else ChargerMode.FIXED
        return when (chargerMode) {
            ChargerMode.FIXED -> {
                val amps = c.fixedAmps.coerceIn(0, maxAmps)
                if (amps == 0) ChargerCommand.Stop else ChargerCommand.Charge(Ampere(amps))
            }
            ChargerMode.SOLAR -> null
        }
    }

    private suspend fun applyChargerCommand(cmd: ChargerCommand) {
        world.charger?.let { charger ->
            runCatchingLog("set charger power") { charger.apply(cmd) }
        }
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
        decisions.chargerCommand?.let { cmd ->
            world.charger?.let { charger ->
                try {
                    charger.apply(cmd)
                } catch (e: Exception) {
                    logger.error("Failed to apply charger command", e)
                }
            }
        }
        decisions.batteryCommand?.let { cmd ->
            world.battery?.let { battery ->
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
            world.heatPump?.let { consumer ->
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
        // ~30 s at nominal 5 s cadence. In the merged loop a poll timeout (10 s) stretches a tick to
        // ~15 s, so worst-case blind release takes up to 90 s — acceptable; the SMA watchdog is ≥15 min.
        const val BLIND_RELEASE_TICKS = 6
        /** Device readings older than this are treated as missing (a device's getState() retains the
         *  last value forever after its update() starts throwing — without this cap the blind-release
         *  failsafe would never fire when a device drops off the network). 3 missed 5 s polls. */
        val STALE_AFTER = 15.seconds
    }
}
