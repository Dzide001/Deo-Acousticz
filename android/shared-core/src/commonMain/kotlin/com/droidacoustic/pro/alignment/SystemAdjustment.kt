package com.droidacoustic.pro.alignment

import com.droidacoustic.pro.prediction.PredictionRequest
import kotlin.math.log10

/** Values match the portable DSP/editor precision. Absolute settings, not cumulative edits. */
data class SystemDsp(val gainDb: Float, val delayMs: Float, val inverted: Boolean) {
    init {
        require(gainDb.isFinite() && gainDb in -12f..12f)
        require(delayMs.isFinite() && delayMs in 0f..200f)
    }
}
data class SystemAdjustment(val sourceId: Int, val before: SystemDsp, val after: SystemDsp) {
    val changed get() = before != after
    /** Compare-and-set protects against applying an obsolete command. Inverse supports undo. */
    fun apply(current: SystemDsp): SystemDsp? = after.takeIf { changed && current == before }
    fun inverse() = SystemAdjustment(sourceId, after, before)
}
data class LevelDifference(val beforeDb: Double?, val afterDb: Double?) {
    val differenceDb: Double? get() = if(beforeDb != null && afterDb != null) afterDb-beforeDb else null
    companion object {
        fun fromPower(before: Double?, after: Double?) = LevelDifference(level(before),level(after))
        private fun level(power: Double?) = power?.takeIf {it.isFinite() && it>0}?.let {10*log10(it)}
    }
}
data class ReceiverAdjustmentComparison(
    val receiver: SavedAlignmentReceiver, val total: LevelDifference,
    val bands: Map<Int,LevelDifference>, val beforeArrivals: List<ArrivalComparison>,
    val afterArrivals: List<ArrivalComparison>
)
/** Derived, immutable preview. Never persisted as a current prediction. */
class SystemAdjustmentPreview internal constructor(
    val adjustment: SystemAdjustment, val inputKey: String,
    comparisons: List<ReceiverAdjustmentComparison>
) {
    private val rows = comparisons.toList()
    val receivers get() = rows.toList()
    fun applicable(key: String, current: SystemDsp) = key == inputKey && adjustment.apply(current) != null
}
object SystemAdjustmentComparison {
    fun calculate(adjustment: SystemAdjustment, inputKey: String, references: AlignmentReferences,
        sources: List<AlignmentSource>, speedOfSound: Double,
        before: PredictionRequest, after: PredictionRequest,
        checkCancellation: () -> Unit = {}): SystemAdjustmentPreview {
        require(adjustment.changed && sources.any {it.id==adjustment.sourceId})
        val receivers=references.receivers
        require(receivers.isNotEmpty() && before.receiverCount==receivers.size && after.receiverCount==receivers.size)
        require(before.coverageConvention()==after.coverageConvention())
        val old=before.calculate(checkCancellation);val new=after.calculate(checkCancellation)
        val arrivals=references.compare(sources,speedOfSound)
        val proposed=references.compare(sources,speedOfSound,mapOf(adjustment.sourceId to adjustment.after.delayMs.toDouble()))
        return SystemAdjustmentPreview(adjustment,inputKey,receivers.mapIndexed {i,r ->
            val a=old.bandPowersAt(i);val b=new.bandPowersAt(i)
            ReceiverAdjustmentComparison(r,LevelDifference.fromPower(old.totalPowerAt(i),new.totalPowerAt(i)),
                (a.keys+b.keys).sorted().associateWith {LevelDifference.fromPower(a[it],b[it])},
                arrivals[i].arrivals.toList(),proposed[i].arrivals.toList())
        })
    }
}
