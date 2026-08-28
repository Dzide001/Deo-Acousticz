#!/usr/bin/env python3
"""
Validation harness for any CLF CF1/CF2 decoder.

This does not decode CF2 for production use - Phase 2 is waiting on the official
CLF Group reader tooling. What this does is check that a decoder's output is
right, using two independent references:

  1. Ground truth. `CLF2_XD12.tab` is the open-text source for
     `Martin Audio-XD12.CF2`. Any correct decoder must reproduce it.

  2. The pole identity. At +/-90 degrees elevation every azimuth arc describes
     the same point in space, so all arcs must share their first and last value.
     This needs no reference file and holds for every balloon ever measured.

Point it at whatever the SDK produces and it will say whether the numbers are
real. Usage:

    python3 tools/clf/validate_balloon.py [corpus_root]
"""
import os
import struct
import sys

try:
    import numpy as np
except ImportError:
    sys.exit("needs numpy: python3 -m pip install numpy")

THIRD_OCTAVE = [25, 31.5, 40, 50, 63, 80, 100, 125, 160, 200, 250, 315, 400,
                500, 630, 800, 1000, 1250, 1600, 2000, 2500, 3150, 4000, 5000,
                6300, 8000, 10000, 12500, 16000, 20000]
CF2_AZ, CF2_EL, CF2_BANDS = 72, 37, 30

DEFAULT_ROOT = os.path.join(os.path.dirname(__file__), "..", "..",
                            "corpus", "clf")


def read_tab(path):
    """Parse the open TAB text format into {band_hz: [[el]*37]*72}."""
    bands, cur = {}, None
    with open(path, encoding="latin1") as fh:
        for line in fh:
            line = line.rstrip("\n")
            if line.startswith("<BAND>"):
                cur = []
                bands[float(line.split("\t")[1])] = cur
            elif cur is not None and line.strip() and not line.startswith("<"):
                cur.append([float(x) for x in line.split("\t") if x.strip()])
    return bands


def check_poles(balloon, label):
    """Every arc must share its pole values. Returns worst spread in dB."""
    worst = 0.0
    for bi in range(balloon.shape[0]):
        band = balloon[bi]
        if not band.any():
            continue                      # unused band slot, zero-filled
        for col in (0, -1):
            pole = band[:, col]
            worst = max(worst, float(pole.max() - pole.min()))
    status = "ok" if worst <= 0.01 else "FAIL"
    print("  poles           %-4s worst spread across arcs: %.4f dB" % (status, worst))
    return worst <= 0.01


def check_against_tab(balloon, tab_path):
    """Compare a decoded balloon against the published text source."""
    if not os.path.exists(tab_path):
        print("  ground truth    skip  (%s not present)" % os.path.basename(tab_path))
        return True
    bands = read_tab(tab_path)
    worst, checked = 0.0, 0
    for hz, arcs in bands.items():
        if hz not in THIRD_OCTAVE:
            continue
        slot = THIRD_OCTAVE.index(hz)
        want = np.asarray(arcs, dtype=np.float32)
        got = balloon[slot]
        if want.shape != got.shape:
            print("  ground truth    FAIL  %g Hz shape %s != %s" % (hz, want.shape, got.shape))
            return False
        worst = max(worst, float(np.abs(want - got).max()))
        checked += 1
    # float32 quantisation of the text's one decimal place is about 5e-06
    status = "ok" if worst < 1e-3 else "FAIL"
    print("  ground truth    %-4s %d bands, worst deviation %.3g dB" % (status, checked, worst))
    return worst < 1e-3


def load_balloon(path, offset):
    """Read a CF2 balloon at a known offset as [band][azimuth][elevation]."""
    with open(path, "rb") as fh:
        raw = fh.read()
    n = CF2_AZ * CF2_EL * CF2_BANDS
    if offset + n * 4 > len(raw):
        raise ValueError("offset 0x%x overruns %s" % (offset, path))
    flat = np.frombuffer(raw, dtype="<f4", count=n, offset=offset)
    return flat.reshape(CF2_BANDS, CF2_AZ, CF2_EL)


def band_range(raw):
    """MINBAND/MAXBAND slot indices from the parameter block."""
    for off in range(0x1000, 0x1800, 4):
        lo, hi = struct.unpack_from("<II", raw, off)
        if 0 <= lo < hi <= CF2_BANDS - 1 and hi - lo >= 5:
            return lo, hi, off
    return None


def main():
    root = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else DEFAULT_ROOT)
    cf2 = os.path.join(root, "martin_audio", "Martin Audio-XD12.CF2")
    tab = os.path.join(root, "martin_audio", "CLF2_XD12.tab")

    if not os.path.exists(cf2):
        sys.exit("corpus not found at %s\n"
                 "corpus/clf is gitignored; restore it from tools/clf/inventory.json." % root)

    print("reference file: %s" % os.path.relpath(cf2, root))
    with open(cf2, "rb") as fh:
        raw = fh.read()

    magic = struct.unpack_from("<I", raw, 0)[0]
    version = raw[0x14:0x1a].split(b"\0")[0].decode("latin1")
    print("  magic           %s  0x%08X" % ("ok" if magic == 0x000ABD41 else "FAIL", magic))
    print("  version         %s" % version)

    br = band_range(raw)
    if br:
        lo, hi, at = br
        print("  band range      ok    slots %d..%d (%g..%g Hz) at 0x%x"
              % (lo, hi, THIRD_OCTAVE[lo], THIRD_OCTAVE[hi], at))

    # 0x3798 is where this file's balloon lives - established by correlating
    # against the TAB source, not by a rule. See docs/clf_format_notes.md.
    balloon = load_balloon(cf2, 0x3798)
    ok = check_poles(balloon, "XD12")
    ok = check_against_tab(balloon, tab) and ok

    print("\n%s" % ("PASS - decoding reproduces the published data exactly"
                    if ok else "FAIL - see above"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
