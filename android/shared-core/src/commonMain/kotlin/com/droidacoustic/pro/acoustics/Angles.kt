package com.droidacoustic.pro.acoustics

import kotlin.math.PI

internal fun radians(degrees: Double): Double = degrees * PI / 180.0
internal fun degrees(radians: Double): Double = radians * 180.0 / PI
