package com.droidacoustic.pro.prediction

enum class PredictionChannel { LISTENER, COVERAGE, REFLECTIONS, OPTIMIZER }
enum class PredictionState { EMPTY, STALE, RUNNING, CURRENT, FAILED }
data class PredictionTicket(val sceneRevision: Long, val sequence: Long, val channel: PredictionChannel)

/** Owned by the application's state thread. Workers return tickets; only the owner publishes.
 * Cancellation saves work but is never relied on for correctness.
 */
class PredictionGate {
    private var sequence = 0L
    private val latest = mutableMapOf<PredictionChannel, PredictionTicket>()
    private val states = mutableMapOf<PredictionChannel, PredictionState>()
    fun invalidate(state: PredictionState = PredictionState.STALE) {
        latest.clear()
        PredictionChannel.entries.forEach { states[it] = state }
    }
    fun begin(channel: PredictionChannel, revision: Long): PredictionTicket {
        val ticket = PredictionTicket(revision, ++sequence, channel)
        latest[channel] = ticket
        states[channel] = PredictionState.RUNNING
        return ticket
    }
    fun isLatest(ticket: PredictionTicket, currentRevision: Long) =
        ticket.sceneRevision == currentRevision && latest[ticket.channel] == ticket
    fun complete(ticket: PredictionTicket, currentRevision: Long): Boolean {
        if (!isLatest(ticket, currentRevision) || states[ticket.channel] != PredictionState.RUNNING) return false
        states[ticket.channel] = PredictionState.CURRENT
        return true
    }
    fun fail(ticket: PredictionTicket, currentRevision: Long): Boolean {
        if (!isLatest(ticket, currentRevision)) return false
        states[ticket.channel] = PredictionState.FAILED
        return true
    }
    fun needsRequest(channel: PredictionChannel, revision: Long): Boolean =
        latest[channel]?.sceneRevision != revision || states[channel] !in setOf(PredictionState.RUNNING, PredictionState.CURRENT)

    fun state(channels: Set<PredictionChannel>): PredictionState {
        val values = channels.map { states[it] ?: PredictionState.EMPTY }
        return when {
            values.isEmpty() -> PredictionState.EMPTY
            PredictionState.RUNNING in values -> PredictionState.RUNNING
            PredictionState.FAILED in values -> PredictionState.FAILED
            values.all { it == PredictionState.CURRENT } -> PredictionState.CURRENT
            values.all { it == PredictionState.EMPTY } -> PredictionState.EMPTY
            else -> PredictionState.STALE
        }
    }
}
