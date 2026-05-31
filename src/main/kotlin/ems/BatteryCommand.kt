package io.konektis.ems

import io.konektis.devices.Watt

/** What the EMS wants the battery to do this tick. */
sealed interface BatteryCommand {
    /** Hold Modbus control (802) and target [power] W (positive=charge, negative=discharge). */
    data class SetPower(val power: Watt) : BatteryCommand

    /** Hand control back to the inverter (803). */
    data object ReleaseToInverter : BatteryCommand
}
