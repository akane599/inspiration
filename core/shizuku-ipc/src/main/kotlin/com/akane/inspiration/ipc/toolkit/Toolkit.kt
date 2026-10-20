package com.akane.inspiration.ipc.toolkit

/**
 * Closed enums of the typed write surface. Each value carries an explicit [code] (the only thing
 * that crosses AIDL) and the exact argv token. Mapping from a code is an explicit `when`, never
 * `name`, `ordinal` or `entries[code]`; an unknown code maps to null and the caller refuses.
 */
enum class AppOp(
    val code: Int,
    val argv: String,
) {
    RUN_ANY_IN_BACKGROUND(1, "RUN_ANY_IN_BACKGROUND"),
    RUN_IN_BACKGROUND(2, "RUN_IN_BACKGROUND"),
    WAKE_LOCK(3, "WAKE_LOCK"),
    ;

    companion object {
        fun fromCode(code: Int): AppOp? =
            when (code) {
                1 -> RUN_ANY_IN_BACKGROUND
                2 -> RUN_IN_BACKGROUND
                3 -> WAKE_LOCK
                else -> null
            }
    }
}

/**
 * App-op modes. [ALLOW] is the permissive write: Android's AppStateTracker restricts an app whenever
 * RUN_ANY_IN_BACKGROUND is anything but MODE_ALLOWED, and MODE_DEFAULT is stored rather than pruned,
 * so writing `default` leaves the app restricted (device-verified on Android 15). [DEFAULT] is kept
 * only to name a mode read back from the device; it is never written ([writable] is false and the
 * service refuses its code).
 */
enum class AppOpMode(
    val code: Int,
    val argv: String,
    val writable: Boolean,
) {
    IGNORE(1, "ignore", writable = true),
    DEFAULT(2, "default", writable = false),
    ALLOW(3, "allow", writable = true),
    ;

    companion object {
        fun fromCode(code: Int): AppOpMode? =
            when (code) {
                1 -> IGNORE
                2 -> DEFAULT
                3 -> ALLOW
                else -> null
            }
    }
}

/** [rawValue] is what `get-standby-bucket` prints for the bucket. */
enum class StandbyBucket(
    val code: Int,
    val argv: String,
    val rawValue: Int,
) {
    ACTIVE(1, "active", 10),
    WORKING_SET(2, "working_set", 20),
    FREQUENT(3, "frequent", 30),
    RARE(4, "rare", 40),
    RESTRICTED(5, "restricted", 45),
    ;

    companion object {
        fun fromCode(code: Int): StandbyBucket? =
            when (code) {
                1 -> ACTIVE
                2 -> WORKING_SET
                3 -> FREQUENT
                4 -> RARE
                5 -> RESTRICTED
                else -> null
            }
    }
}

/**
 * Doze profiles. [constants] is the compile-time `device_idle_constants` value; null (STANDARD)
 * means the setting is deleted. [effectiveMs] are the values that must be visible in
 * `dumpsys deviceidle` Settings after applying.
 */
enum class DozeProfile(
    val code: Int,
    val constants: String?,
) {
    STANDARD(0, null),
    BALANCED(1, "light_after_inactive_to=60000,inactive_to=600000,idle_after_inactive_to=600000"),
    AGGRESSIVE_NIGHT(
        2,
        "light_after_inactive_to=15000,inactive_to=60000,idle_after_inactive_to=60000," +
            "sensing_to=0,locating_to=0,motion_inactive_to=0",
    ),
    ;

    val effectiveMs: Map<String, Long> =
        constants
            ?.split(',')
            ?.associate { pair ->
                pair.substringBefore('=') to pair.substringAfter('=').toLong()
            }.orEmpty()

    companion object {
        fun fromCode(code: Int): DozeProfile? =
            when (code) {
                0 -> STANDARD
                1 -> BALANCED
                2 -> AGGRESSIVE_NIGHT
                else -> null
            }

        /** Union of keys any profile controls; the service reports their effective values. */
        val controlledKeys: List<String> =
            listOf(BALANCED, AGGRESSIVE_NIGHT).flatMap { it.effectiveMs.keys }.distinct()
    }
}

/**
 * Writes the hard deny of protected packages (rule d: role holders, the default IME, accessibility services, device
 * admins, the always-on VPN, or a package whose protection could not be decided) still lets through. Each one only
 * lifts a background restriction, so it cannot take a protected app out of the background, which is what the rule
 * guards against; it keeps a restriction Inspiration added restorable after the app became protected. Every other
 * hard-deny rule still refuses everything. The service enforces this; the client applies the same rule to refuse
 * before it asks.
 */
object RestrictionLift {
    /** Only `allow` lifts an app-op restriction (`ignore` sets one; `default` is never written). */
    fun appOp(mode: AppOpMode): Boolean = mode == AppOpMode.ALLOW

    /** A bucket strictly more active than [currentRaw], the value `get-standby-bucket` prints now. */
    fun bucket(
        target: StandbyBucket,
        currentRaw: Int,
    ): Boolean = target.rawValue < currentRaw
}

object PackageNames {
    private const val MAX_LENGTH = 255
    private val PATTERN = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

    /** Whole-string match (never `find`); ASCII only, first char a letter, no trailing newline. */
    fun isValid(name: String?): Boolean = name != null && name.length <= MAX_LENGTH && PATTERN.matches(name)
}

object DozeRaw {
    private val SAFE = Regex("[A-Za-z0-9_=,.]{0,1024}")

    /** Values the service is willing to replay into `device_idle_constants`. */
    fun isSafe(raw: String): Boolean = SAFE.matches(raw)

    private const val MAX_LENGTH = 1024
    private val PAIR = Regex("([a-z][a-z0-9_]{0,63})=(\\d{1,18}(\\.\\d{1,9})?|true|false)")

    /**
     * Comma-separated `key=value` pairs: every key an identifier (`[a-z][a-z0-9_]{0,63}`, so keys AOSP
     * never defines (OEM additions) qualify), no key repeated, every value a non-negative integer, an
     * optional decimal (factors) or `true`/`false`; at most 1024 characters. The value grammar carries the
     * safety (no separators or shell metacharacters), and the write uses fixed argv and a CAS, so a
     * restore may replay this without the service having read it itself.
     */
    fun isRestorable(raw: String): Boolean {
        if (raw.isEmpty() || raw.length > MAX_LENGTH) return false
        val seen = HashSet<String>()
        return raw.split(',').all { pair ->
            val key = PAIR.matchEntire(pair)?.groupValues?.get(1)
            key != null && seen.add(key)
        }
    }
}
