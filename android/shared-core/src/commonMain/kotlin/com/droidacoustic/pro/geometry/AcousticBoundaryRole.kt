package com.droidacoustic.pro.geometry

/** Independent of receiver/audience assignment. Explicit ideal geometric-acoustics boundaries. */
enum class AcousticBoundaryRole {
    NONE,
    /** No transmission or reflected arrival; diffraction is not modeled. */
    OCCLUDING,
    /** Opaque, two-sided, unit pressure reflection coefficient; first-order specular paths only. */
    RIGID_REFLECTOR
}
