package com.droidacoustic.pro.geometry

/** Prediction semantics attached to original triangle indices, independent of mesh topology. */
enum class MeshFaceAssignment {
    FOLLOW_ZONE, SEATED, STANDING, EXCLUDED;
    fun zoneType(parent: String): String? = when (this) {
        FOLLOW_ZONE -> parent.takeIf {it=="AUDIENCE_SEATED" || it=="AUDIENCE_STANDING"}
        SEATED -> "AUDIENCE_SEATED"
        STANDING -> "AUDIENCE_STANDING"
        EXCLUDED -> null
    }
    fun earOffset(parent: String): Float? = when (zoneType(parent)) {
        "AUDIENCE_SEATED" -> 1.2f
        "AUDIENCE_STANDING" -> 1.6f
        else -> null
    }
    fun surfaceKey(areaId: Int): String = when (this) {
        FOLLOW_ZONE -> "area:$areaId"
        else -> "area:$areaId:${name.lowercase()}"
    }
    fun surfaceName(parent: String): String = when (this) {
        FOLLOW_ZONE -> parent
        SEATED -> "$parent — Seated"
        STANDING -> "$parent — Standing"
        EXCLUDED -> "$parent — Excluded"
    }
}
