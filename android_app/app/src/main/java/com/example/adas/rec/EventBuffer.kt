package com.example.adas.rec

/**
 * Circular pre-roll buffer for event recording — the "REC" that until now was a
 * boolean toggle and nothing else.
 *
 * A dashcam has to produce the seconds *before* an event, which have already
 * happened by the time anything triggers. So frames stream continuously into a
 * ring buffer holding the last [preRollMs]; a trigger freezes that history and
 * keeps recording for a further [postRollMs], and the two together are the clip.
 *
 * Generic in the payload type: the app stores redacted JPEG bytes, the tests
 * store integers. That keeps the whole windowing/eviction/cooldown policy — the
 * part with the off-by-one risks — testable on the JVM with no camera, no codec
 * and no clock.
 *
 * This is the keystone the rest of the data loop waits on: clip upload,
 * stored-frame redaction, scene-graph geometry and real-frame VLM training are
 * all blocked on clips actually existing.
 */

/** One buffered frame with the time it was captured. */
data class TimedFrame<T>(val tMs: Long, val payload: T)

/** A completed event clip: pre-roll history plus post-roll tail. */
data class Clip<T>(
    /** Why it was triggered, e.g. "DANGER: person ahead". */
    val reason: String,
    val triggeredAtMs: Long,
    val frames: List<TimedFrame<T>>
) {
    val frameCount: Int get() = frames.size
    /** Wall duration covered by the clip. */
    val durationMs: Long
        get() = if (frames.size < 2) 0L else frames.last().tMs - frames.first().tMs
}

/**
 * @param preRollMs seconds of history kept ahead of a trigger.
 * @param postRollMs how long recording continues after a trigger.
 * @param maxFrames hard cap on buffered frames — memory safety, since payloads
 *   are image bytes on device.
 * @param cooldownMs after a clip completes, ignore new triggers for this long so
 *   one long hazard does not produce a burst of near-identical clips.
 * @param maxClipMs absolute ceiling on a single clip, so repeated re-triggering
 *   during a sustained hazard cannot extend one recording indefinitely.
 */
class EventBuffer<T>(
    val preRollMs: Long = 8_000,
    val postRollMs: Long = 4_000,
    val maxFrames: Int = 600,
    val cooldownMs: Long = 5_000,
    val maxClipMs: Long = 30_000
) {
    private val frames = ArrayDeque<TimedFrame<T>>()

    private var triggeredAtMs: Long? = null
    private var recordingEndsAtMs: Long = 0L
    private var reason: String = ""
    private var lastClipEndedMs: Long? = null

    /** True while a triggered clip is still collecting its post-roll. */
    val isRecording: Boolean get() = triggeredAtMs != null

    val bufferedFrames: Int get() = frames.size

    /** Frames currently held, oldest first. Exposed for inspection/tests. */
    fun snapshot(): List<TimedFrame<T>> = frames.toList()

    /**
     * Add a frame.
     *
     * @return the completed [Clip] when this frame closes out a recording,
     *   otherwise null. Callers persist the clip on the non-null result.
     */
    fun add(payload: T, tMs: Long): Clip<T>? {
        frames.addLast(TimedFrame(tMs, payload))

        if (!isRecording) {
            // Idle: keep only the pre-roll window.
            while (frames.isNotEmpty() && tMs - frames.first().tMs > preRollMs) {
                frames.removeFirst()
            }
        }
        // Memory ceiling applies in both states.
        while (frames.size > maxFrames) {
            frames.removeFirst()
        }

        if (isRecording && tMs >= recordingEndsAtMs) {
            return finish(tMs)
        }
        return null
    }

    /**
     * Request a recording.
     *
     * Re-triggering while already recording extends the post-roll rather than
     * starting a second clip — a hazard that stays dangerous should produce one
     * longer clip, not several overlapping ones — up to [maxClipMs].
     *
     * @return true if this call started or extended a recording.
     */
    fun trigger(reason: String, tMs: Long): Boolean {
        val startedAt = triggeredAtMs
        if (startedAt != null) {
            val extended = (tMs + postRollMs).coerceAtMost(startedAt + maxClipMs)
            if (extended > recordingEndsAtMs) {
                recordingEndsAtMs = extended
                return true
            }
            return false
        }

        lastClipEndedMs?.let { if (tMs - it < cooldownMs) return false }

        triggeredAtMs = tMs
        recordingEndsAtMs = tMs + postRollMs
        this.reason = reason
        return true
    }

    /** Close the current recording early (camera teardown, app backgrounded). */
    fun flush(tMs: Long): Clip<T>? = if (isRecording) finish(tMs) else null

    /** Drop everything, including the cooldown. */
    fun reset() {
        frames.clear()
        triggeredAtMs = null
        recordingEndsAtMs = 0L
        reason = ""
        lastClipEndedMs = null
    }

    private fun finish(tMs: Long): Clip<T> {
        val clip = Clip(reason, triggeredAtMs ?: tMs, frames.toList())
        triggeredAtMs = null
        recordingEndsAtMs = 0L
        reason = ""
        lastClipEndedMs = tMs

        // Retain only what could serve as pre-roll for a following event.
        while (frames.isNotEmpty() && tMs - frames.first().tMs > preRollMs) {
            frames.removeFirst()
        }
        return clip
    }
}
