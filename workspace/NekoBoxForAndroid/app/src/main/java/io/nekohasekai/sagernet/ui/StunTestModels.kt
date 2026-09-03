package io.nekohasekai.sagernet.ui

import androidx.annotation.StringRes
import io.nekohasekai.sagernet.R

internal enum class StunPreset(
    val value: String,
    @param:StringRes val titleRes: Int,
    @param:StringRes val descriptionRes: Int,
    val servers: List<String>,
) {
    BALANCED(
        "balanced",
        R.string.stun_preset_balanced,
        R.string.stun_preset_balanced_description,
        listOf(
            "stunserver2025.stunprotocol.org:3478",
            "stun.voipgate.com:3478",
            "stun.cloudflare.com:3478",
            "stun.l.google.com:19302",
        ),
    ),
    FULL(
        "full",
        R.string.stun_preset_full,
        R.string.stun_preset_full_description,
        listOf(
            "stunserver2025.stunprotocol.org:3478",
            "stun.voipgate.com:3478",
        ),
    ),
    FAST(
        "fast",
        R.string.stun_preset_fast,
        R.string.stun_preset_fast_description,
        listOf(
            "stun.cloudflare.com:3478",
            "stun.l.google.com:19302",
        ),
    ),
    CUSTOM(
        "custom",
        R.string.stun_preset_custom,
        R.string.stun_preset_custom_description,
        emptyList(),
    ),
    ;

    companion object {
        fun fromValue(value: String): StunPreset =
            entries.firstOrNull { it.value == value } ?: BALANCED
    }
}

internal sealed interface StunServerParseResult {
    data class Valid(val servers: List<String>) : StunServerParseResult
    data class Invalid(val reason: Reason, val line: String = "") : StunServerParseResult

    enum class Reason {
        EMPTY,
        FORMAT,
        PORT,
        TOO_MANY,
    }
}

internal object StunServerListParser {
    const val MAX_SERVERS = 8

    private val bracketedAddress = Regex("^\\[[^\\s\\[\\]]+]:([0-9]+)$")
    private val hostAddress = Regex("^([^\\s:\\[\\]]+):([0-9]+)$")

    fun parse(value: String): StunServerParseResult {
        val servers = linkedMapOf<String, String>()
        value.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach
            val match = bracketedAddress.matchEntire(line) ?: hostAddress.matchEntire(line)
                ?: return StunServerParseResult.Invalid(
                    StunServerParseResult.Reason.FORMAT,
                    line,
                )
            val port = match.groupValues.last().toIntOrNull()
                ?: return StunServerParseResult.Invalid(
                    StunServerParseResult.Reason.PORT,
                    line,
                )
            if (port !in 1..65535) {
                return StunServerParseResult.Invalid(StunServerParseResult.Reason.PORT, line)
            }
            servers.putIfAbsent(line.lowercase(), line)
            if (servers.size > MAX_SERVERS) {
                return StunServerParseResult.Invalid(StunServerParseResult.Reason.TOO_MANY)
            }
        }
        return if (servers.isEmpty()) {
            StunServerParseResult.Invalid(StunServerParseResult.Reason.EMPTY)
        } else {
            StunServerParseResult.Valid(servers.values.toList())
        }
    }
}

internal data class StunServerUiResult(
    val server: String,
    val bindingSuccess: Boolean,
    val behaviorSupported: Boolean,
    val behaviorComplete: Boolean,
    val natType: Int,
    val mappingBehavior: Int,
    val filteringBehavior: Int,
    val externalAddress: String,
    val externalPort: Int,
    val ipFamily: Int,
    val durationMilliseconds: Long,
    val errorCode: String,
    val errorMessage: String,
    val warningCode: String = "",
)

internal object StunProtocolCodes {
    const val NAT_ERROR = 0
    const val NAT_UNKNOWN = 1
    const val NAT_NONE = 2
    const val NAT_BLOCKED = 3
    const val NAT_FULL = 4
    const val NAT_SYMMETRIC = 5
    const val NAT_RESTRICTED = 6
    const val NAT_PORT_RESTRICTED = 7
    const val NAT_SYMMETRIC_UDP_FIREWALL = 8

    const val BEHAVIOR_UNKNOWN = 0
    const val BEHAVIOR_ENDPOINT = 1
    const val BEHAVIOR_ADDRESS = 2
    const val BEHAVIOR_ADDRESS_AND_PORT = 3
}

internal enum class StunAssessmentKind {
    FAVORABLE,
    MODERATE,
    RESTRICTIVE,
    OPEN,
    INCONSISTENT,
    BASIC_ONLY,
    FAILED,
    CANCELLED,
}

internal data class StunAssessment(
    val kind: StunAssessmentKind,
    val representative: StunServerUiResult? = null,
)

internal object StunAssessmentPolicy {
    fun assess(results: List<StunServerUiResult>): StunAssessment {
        if (results.isNotEmpty() && results.all { it.errorCode == "cancelled" }) {
            return StunAssessment(StunAssessmentKind.CANCELLED)
        }
        val complete = results.filter { it.behaviorComplete }
        if (complete.isEmpty()) {
            return StunAssessment(
                if (results.any { it.bindingSuccess }) {
                    StunAssessmentKind.BASIC_ONLY
                } else {
                    StunAssessmentKind.FAILED
                },
            )
        }
        val behaviors = complete.map {
            Triple(
                it.mappingBehavior,
                it.filteringBehavior,
                it.natType == StunProtocolCodes.NAT_NONE,
            )
        }.distinct()
        if (behaviors.size > 1) {
            return StunAssessment(StunAssessmentKind.INCONSISTENT)
        }
        val result = complete.first()
        val kind = when {
            result.natType == StunProtocolCodes.NAT_NONE ->
                StunAssessmentKind.OPEN
            result.mappingBehavior == StunProtocolCodes.BEHAVIOR_ADDRESS_AND_PORT ||
                result.mappingBehavior == StunProtocolCodes.BEHAVIOR_ADDRESS ->
                StunAssessmentKind.RESTRICTIVE
            result.filteringBehavior == StunProtocolCodes.BEHAVIOR_ADDRESS_AND_PORT ->
                StunAssessmentKind.MODERATE
            else -> StunAssessmentKind.FAVORABLE
        }
        return StunAssessment(kind, result)
    }
}
