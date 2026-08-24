# DroidAcoustic Pro

**Professional Android acoustic simulation for tablet — open source.**

Targeting Android tablets (OnePlus Pad 3 and similar), DroidAcoustic Pro provides
professional-grade loudspeaker system design comparable to ArrayCalc, EASE, and
Soundvision — using only open-source libraries.

---

## Technology Stack

| Layer | Library | Licence |
|---|---|---|
| 3D Rendering | [Filament](https://github.com/google/filament) | Apache 2.0 |
| GPU Compute | Vulkan (Android NDK) | Open standard |
| 3D Import | [Assimp](https://github.com/assimp/assimp) | BSD 3-Clause |
| Math | [GLM](https://github.com/g-truc/glm) | MIT |
| JSON | [nlohmann/json](https://github.com/nlohmann/json) | MIT |
| Serialisation | Protocol Buffers | BSD 3-Clause |
| C++ Testing | Google Test | BSD 3-Clause |
| UI | Jetpack Compose (Material 3) | Apache 2.0 |

---

## Development Phases

| Phase | Feature | Duration |
|---|---|---|
| **0** | Foundation — 3D viewport, Vulkan compute, CI | 8 wks |
| **1** | 3D navigation, UI shell, two-pane layout | 6 wks |
| **2** | Venue import (OBJ/glTF), audience areas, materials | 10 wks |
| **3** | Speaker library, CLF import, 3D placement | 8 wks |
| **4** | Direct SPL engine, heatmap visualisation | 10 wks |
| **5** | Atmospheric absorption, 8-band frequency analysis | 8 wks |
| **6** | DSP pipeline — biquad EQ, delay, filters | 10 wks |
| **7** | Early reflections (image source), RT60 | 14 wks |
| **8** | Line array tools, coherent summation, rigging | 14 wks |
| **9** | STI — Speech Transmission Index | 14 wks |
| **10** | Reports, optimisation, project management | ongoing |

**MVP = Phases 0–8 (~22 months solo, ~11 months with two devs)**

---

## Build Instructions

See [docs/build_instructions.md](docs/build_instructions.md).

---

## Repository Structure

```
droidacoustic/
├── android/          # Android app (Kotlin + Compose + NDK)
├── engine/           # Portable C++ acoustic engine
├── shaders/          # Vulkan compute shaders (GLSL source)
├── tests/            # C++ unit tests (gtest)
├── data/             # Seed speaker / venue data
└── docs/             # Architecture & algorithm documentation
```

---

## Licence

Apache 2.0 — see [LICENSE](LICENSE).
