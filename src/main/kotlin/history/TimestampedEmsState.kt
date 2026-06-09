package io.konektis.history

import io.konektis.ems.EMSState
import java.time.Instant

/**
 * An EMS tick stamped with the wall-clock time it was produced. The timestamp is captured at tick
 * time (in EnergyManager) — not at insert time — because HistoryWriter buffers rows up to 30s before
 * writing them. EMSState carries no time field of its own.
 */
data class TimestampedEmsState(val ts: Instant, val state: EMSState)
