package io.konektis.devices

/**
 * Holds the live [World] so the device graph can be rebuilt at runtime (config hot-reload) while the
 * collector and energy manager keep a stable reference. Readers always see the latest via [current];
 * [swap] installs a new graph and returns the previous one so the caller can tear it down.
 *
 * Swaps are serialised by the caller (the reload coroutine and the poll/control tick share a mutex),
 * so a swap never lands in the middle of a refresh or control cycle.
 */
class WorldHolder(initial: World) {
    @Volatile
    var current: World = initial
        private set

    /** Install [newWorld] and return the world it replaced (for teardown). */
    fun swap(newWorld: World): World {
        val old = current
        current = newWorld
        return old
    }
}
