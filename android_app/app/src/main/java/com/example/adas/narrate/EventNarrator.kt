package com.example.adas.narrate

import kotlin.math.roundToInt

/**
 * Post-hoc narration of recorded events.
 *
 * Explicitly **not** in the live safety loop. The driver is warned by the FCW,
 * DDAWS and LDW alerts in milliseconds; narration runs afterwards, over a clip
 * that has already been written, to produce a human-readable line for the event
 * log and for upload. Nothing here may ever gate a warning.
 *
 * The design splits into two halves for a practical reason: the *context
 * assembly* is needed no matter what generates the text, whereas the generator
 * itself is swappable. [EventContext] and its prompt rendering are therefore
 * plain Kotlin and fully tested, while [EventNarrator] has a deterministic
 * implementation that ships today and a documented slot for an on-device VLM.
 *
 * MODEL-AGNOSTIC: consumes clip metadata, telemetry and labels. Never touches the
 * detector.
 */

/** One detected object as it appeared during an event. */
data class NarrationObject(
    val label: String,
    val peakConfidence: Float,
    /** Lowest TTC observed for this object during the event, if it was closing. */
    val minTtcSeconds: Float? = null,
    val wasInEgoPath: Boolean = false
)

/**
 * Everything known about a recorded event, assembled from the clip manifest, the
 * collision assessment, telemetry and GNSS.
 */
data class EventContext(
    val reason: String,
    val triggeredAtMs: Long,
    val durationMs: Long,
    val frameCount: Int,
    val speedKmh: Int? = null,
    val objects: List<NarrationObject> = emptyList(),
    val latitude: Double? = null,
    val longitude: Double? = null,
    /** Driver state at the time, if DDAWS was running. */
    val driverAlert: String? = null,
    val laneDeparture: String? = null
) {
    /** The object that drove the event: lowest TTC in path, else highest confidence. */
    val primaryObject: NarrationObject?
        get() = objects.filter { it.wasInEgoPath && it.minTtcSeconds != null }
            .minByOrNull { it.minTtcSeconds!! }
            ?: objects.maxByOrNull { it.peakConfidence }

    /**
     * Render the context as a prompt for a vision-language model.
     *
     * Kept as structured text rather than free prose deliberately: small on-device
     * models follow an explicit field list far more reliably than they infer
     * structure, and it keeps the prompt token count — which dominates latency at
     * this size — predictable.
     */
    fun toPrompt(): String {
        val sb = StringBuilder()
        sb.append("You are a driving event analyst. Describe this dashcam event ")
        sb.append("in one or two plain sentences. Be factual; do not speculate ")
        sb.append("about intent or blame.\n\n")
        sb.append("EVENT\n")
        sb.append("- trigger: $reason\n")
        sb.append("- duration: ${(durationMs / 1000f).roundToInt()}s ($frameCount frames)\n")
        speedKmh?.let { sb.append("- ego speed: $it km/h\n") }
        if (latitude != null && longitude != null) {
            sb.append("- location: %.5f, %.5f\n".format(latitude, longitude))
        }
        driverAlert?.let { sb.append("- driver state: $it\n") }
        laneDeparture?.let { sb.append("- lane: $it\n") }

        if (objects.isEmpty()) {
            sb.append("- road users: none detected\n")
        } else {
            sb.append("ROAD USERS\n")
            for (o in objects) {
                sb.append("- ${o.label} (confidence ${(o.peakConfidence * 100).roundToInt()}%")
                o.minTtcSeconds?.let { sb.append(", closest approach ${"%.1f".format(it)}s") }
                if (o.wasInEgoPath) sb.append(", in ego lane")
                sb.append(")\n")
            }
        }
        return sb.toString()
    }
}

/** Produces a human-readable description of a recorded event. */
interface EventNarrator {
    /** Narrate [context]. Implementations may be slow; call off the main thread. */
    suspend fun narrate(context: EventContext): String
}

/**
 * Deterministic narrator — no model, no download, no inference cost.
 *
 * This is what ships today, and it is not merely a placeholder: for the event log
 * a predictable one-liner is arguably better than a generated one, since it never
 * hallucinates a road user that was not detected. The VLM's value is in the
 * *frames*, describing what the labels cannot — road surface, weather, whether a
 * pedestrian was already crossing — and that arrives with the real model.
 */
class TemplateNarrator : EventNarrator {

    override suspend fun narrate(context: EventContext): String {
        val parts = mutableListOf<String>()

        val speed = context.speedKmh
        val motion = when {
            speed == null -> "Travelling"
            speed == 0 -> "Stationary"
            speed < 30 -> "Moving slowly at $speed km/h"
            else -> "Travelling at $speed km/h"
        }

        val primary = context.primaryObject
        parts += if (primary == null) {
            "$motion when the recording was triggered (${context.reason.cleaned()})."
        } else {
            val where = if (primary.wasInEgoPath) "in the vehicle's path" else "nearby"
            val closing = primary.minTtcSeconds?.let {
                ", closing to within ${"%.1f".format(it)} seconds of collision"
            } ?: ""
            "$motion when a ${primary.label.replace('_', ' ')} was detected " +
                "$where$closing."
        }

        val others = context.objects.filter { it !== primary }
        if (others.isNotEmpty()) {
            val summary = others.groupingBy { it.label.replace('_', ' ') }.eachCount()
                .entries.joinToString(", ") { (label, n) ->
                    if (n > 1) "$n ${label}s" else "a $label"
                }
            parts += "Also present: $summary."
        }

        context.driverAlert?.let { parts += "Driver monitoring reported: $it." }
        context.laneDeparture?.let { parts += "Lane status: $it." }

        parts += "Clip length ${(context.durationMs / 1000f).roundToInt()}s."
        return parts.joinToString(" ")
    }

    private fun String.cleaned(): String =
        replace("⚠️", "").replace(Regex("\\s+"), " ").trim().lowercase()
}

/*
 * Wiring the on-device VLM (needs one dependency and one large asset):
 *
 *   1. Dependency: the LiteRT-LM Kotlin API. Target that, NOT the MediaPipe
 *      `LlmInference` API — MediaPipe's LLM Inference API is in maintenance-only
 *      mode and its Android path is being superseded.
 *   2. Asset: Gemma 3n E2B in `.litertlm` format, INT4 (~1.5 GB). Far too large
 *      to bundle in the APK — it has to be downloaded on first use into
 *      filesDir and integrity-checked, with the feature disabled until then.
 *   3. Implement `GemmaNarrator : EventNarrator`: feed `context.toPrompt()`
 *      together with 2-3 keyframes sampled from the clip (Gemma 3n takes
 *      interleaved image+text), and cap the output length — narration latency on
 *      a phone is seconds, not milliseconds.
 *   4. Run it on a background dispatcher, opportunistically (charging + Wi-Fi is
 *      the sane default), never in the analysis loop.
 *
 * Everything above this comment stays unchanged when that lands: the context
 * assembly, the prompt, and the event-log integration are already done, and
 * TemplateNarrator remains the fallback whenever the model is absent or the
 * device is thermally throttled.
 */
