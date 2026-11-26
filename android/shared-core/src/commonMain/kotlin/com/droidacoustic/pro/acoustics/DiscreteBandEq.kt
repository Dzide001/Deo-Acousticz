package com.droidacoustic.pro.acoustics

/** Legacy magnitude-only band offsets; unset frequencies are neutral, never nearest-neighbour EQ. */
object DiscreteBandEq {
    fun gainDb(offsets:Map<Int,Float>,frequencyHz:Int):Float=offsets[frequencyHz] ?: 0f
}
