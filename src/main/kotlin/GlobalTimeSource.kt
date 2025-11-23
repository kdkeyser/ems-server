package io.konektis

import kotlin.time.TimeSource

object GlobalTimeSource {
    val source = TimeSource.Monotonic
}