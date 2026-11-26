package com.droidacoustic.pro.geometry

internal actual class GeometryCacheLock actual constructor() {
    private val monitor=Any()
    actual fun <T> withLock(block: () -> T): T = synchronized(monitor,block)
}
