package com.akane.inspiration.telemetry.status

/** What the battery is doing, as the live gauge and the status notification word it. */
enum class ChargeState {
    Charging,
    Discharging,

    /** A charger is connected but the battery is not charging: full, held at a charge limit, or losing to the load. */
    PluggedNotCharging,
}

/**
 * On a charger, a current within this of zero is noise (a taper ending, a charger regulating, a battery held at a
 * limit): it neither turns the label away from charging nor has a time to empty.
 */
const val CHARGING_DRAIN_DEADBAND_MA = 50

/**
 * How far back inside [CHARGING_DRAIN_DEADBAND_MA] a current must come before the label or time left it decided
 * changes again, so a drain hovering at the deadband does not flip them every reading.
 */
const val DEADBAND_HYSTERESIS_MA = 10

// The battery's own BATTERY_STATUS_*, as LightRead and LiveReading name them.
private const val STATUS_CHARGING = "charging"
private const val STATUS_DISCHARGING = "discharging"
private const val STATUS_FULL = "full"
private const val STATUS_NOT_CHARGING = "not_charging"

/**
 * What the battery is doing, from its own [status] and the measured [currentMa] (calibrated, discharge negative),
 * never from the plug alone: a plugged battery can be full, held at a charge limit, or losing to the load. The plug
 * decides only without a usable status, and then a drain past [CHARGING_DRAIN_DEADBAND_MA] still overrides it. A label
 * the current decided against the deadband ([previous], the last reading's) holds until the current is
 * [DEADBAND_HYSTERESIS_MA] back inside it.
 */
fun chargeState(
    plugged: Boolean?,
    status: String?,
    currentMa: Int?,
    previous: ChargeState? = null,
): ChargeState {
    val drainDeadbandMa = deadbandHeldBy(previous == ChargeState.PluggedNotCharging)
    val chargeDeadbandMa = deadbandHeldBy(previous == ChargeState.Charging)
    val drainingPastDeadband = currentMa != null && currentMa <= -drainDeadbandMa
    return when {
        // Unplugged, the battery can only discharge.
        plugged == false -> ChargeState.Discharging

        // "Charging" means the charger is enabled; a weak one can still lose to the load.
        status == STATUS_CHARGING -> if (drainingPastDeadband) ChargeState.PluggedNotCharging else ChargeState.Charging

        status == STATUS_DISCHARGING -> if (plugged == true) ChargeState.PluggedNotCharging else ChargeState.Discharging

        status == STATUS_FULL || status == STATUS_NOT_CHARGING -> ChargeState.PluggedNotCharging

        // No usable status: the plug decides, with the same deadband for a measured drain.
        plugged == true -> if (drainingPastDeadband) ChargeState.PluggedNotCharging else ChargeState.Charging

        // Neither plug nor status: only a clear charge current reads as charging.
        currentMa != null && currentMa >= chargeDeadbandMa -> ChargeState.Charging

        else -> ChargeState.Discharging
    }
}

/** [CHARGING_DRAIN_DEADBAND_MA], narrowed by [DEADBAND_HYSTERESIS_MA] while a label it decided is [held]. */
fun deadbandHeldBy(held: Boolean): Int = if (held) CHARGING_DRAIN_DEADBAND_MA - DEADBAND_HYSTERESIS_MA else CHARGING_DRAIN_DEADBAND_MA
