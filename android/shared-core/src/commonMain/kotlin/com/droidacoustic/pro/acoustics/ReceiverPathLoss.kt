package com.droidacoustic.pro.acoustics

import com.droidacoustic.pro.geometry.BoundaryPoint

/** Explicit snapshot contract: additional loss varies by emitter/band, never by bounce point. */
internal class ReceiverPathLoss(private val loss:(SoundSource,Int)->Float): (SoundSource,BoundaryPoint?,Int)->Float {
    override fun invoke(source:SoundSource,point:BoundaryPoint?,hz:Int)=loss(source,hz)
}
