package com.hcc.dvdplayer.logic

/**
 * Values the player and the picker actually use.
 *
 * A key listed in [RestrictionInput.present] came from the MDM and wins over
 * the built-in default. [lockManaged] is true when `lock_controls` itself was
 * sent: the 3-second hold on the picture cannot override that. When the MDM
 * omits the key, [localLock] (the hold) is used and defaults to unlocked.
 */
data class ManagedSettings(
    val autoStart: Boolean,
    val zoomToFillDefault: Boolean,
    val lockControls: Boolean,
    val lockManaged: Boolean,
    val showAudioMeter: Boolean,
    val enableSnapshot: Boolean,
    val enableFreeze: Boolean,
    val controlsAutohideSeconds: Int,
    val excludeSmartDevices: Boolean,
    val extraExcludedVids: Set<Int>,
) {
    val exclusionPolicy: ExclusionPolicy
        get() = ExclusionPolicy(excludeSmartDevices, extraExcludedVids)

    companion object {
        const val AUTO_START = "auto_start"
        const val ZOOM_TO_FILL_DEFAULT = "zoom_to_fill_default"
        const val LOCK_CONTROLS = "lock_controls"
        const val SHOW_AUDIO_METER = "show_audio_meter"
        const val ENABLE_SNAPSHOT = "enable_snapshot"
        const val ENABLE_FREEZE = "enable_freeze"
        const val CONTROLS_AUTOHIDE_SECONDS = "controls_autohide_seconds"
        const val EXCLUDE_SMART_DEVICES = "exclude_smart_devices"
        const val EXTRA_EXCLUDED_VIDS = "extra_excluded_vids"

        const val AUTOHIDE_DEFAULT = 4
        const val AUTOHIDE_MIN = 1
        const val AUTOHIDE_MAX = 60

        fun parse(input: RestrictionInput, localLock: Boolean): ManagedSettings {
            val lockManaged = LOCK_CONTROLS in input.present
            val seconds = when {
                CONTROLS_AUTOHIDE_SECONDS !in input.present -> AUTOHIDE_DEFAULT
                input.ints.containsKey(CONTROLS_AUTOHIDE_SECONDS) -> input.ints.getValue(CONTROLS_AUTOHIDE_SECONDS)
                else -> input.strings[CONTROLS_AUTOHIDE_SECONDS]?.trim()?.toIntOrNull() ?: AUTOHIDE_DEFAULT
            }
            val vids = if (EXTRA_EXCLUDED_VIDS in input.present) {
                parseVendorIds(input.strings[EXTRA_EXCLUDED_VIDS].orEmpty())
            } else {
                emptySet()
            }
            return ManagedSettings(
                autoStart = bool(input, AUTO_START, true),
                zoomToFillDefault = bool(input, ZOOM_TO_FILL_DEFAULT, false),
                lockControls = if (lockManaged) bool(input, LOCK_CONTROLS, false) else localLock,
                lockManaged = lockManaged,
                showAudioMeter = bool(input, SHOW_AUDIO_METER, true),
                enableSnapshot = bool(input, ENABLE_SNAPSHOT, true),
                enableFreeze = bool(input, ENABLE_FREEZE, true),
                controlsAutohideSeconds = seconds.coerceIn(AUTOHIDE_MIN, AUTOHIDE_MAX),
                excludeSmartDevices = bool(input, EXCLUDE_SMART_DEVICES, true),
                extraExcludedVids = vids,
            )
        }

        fun parseVendorIds(raw: String): Set<Int> {
            if (raw.isBlank()) return emptySet()
            return raw.split(',', ';', ' ', '\n', '\t')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .mapNotNull { token ->
                    val hex = token.removePrefix("0x").removePrefix("0X")
                    hex.toIntOrNull(16)?.takeIf { it in 0..0xFFFF }
                }
                .toSet()
        }

        private fun bool(input: RestrictionInput, key: String, default: Boolean): Boolean {
            if (key !in input.present) return default
            input.bools[key]?.let { return it }
            return when (input.strings[key]?.trim()?.lowercase()) {
                "true", "1", "yes" -> true
                "false", "0", "no" -> false
                else -> default
            }
        }
    }
}

/**
 * The restriction bundle, already pulled apart so tests do not need Android.
 * [present] is every key the admin sent, including a key whose value did not
 * parse. Missing keys are not defaults the admin set; they mean "use the app default".
 */
data class RestrictionInput(
    val present: Set<String>,
    val bools: Map<String, Boolean> = emptyMap(),
    val ints: Map<String, Int> = emptyMap(),
    val strings: Map<String, String> = emptyMap(),
)
