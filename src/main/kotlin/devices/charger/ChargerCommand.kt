package io.konektis.devices.charger

import io.konektis.devices.Ampere

sealed interface ChargerCommand {
    /** Start or continue charging at the given current. Implies an open OCPP transaction. */
    data class Charge(val current: Ampere) : ChargerCommand
    /** Stop charging. Implies closing any open OCPP transaction. */
    data object Stop : ChargerCommand
}
