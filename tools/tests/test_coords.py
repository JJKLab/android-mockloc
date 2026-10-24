"""Numeric acceptance for coords.py. Run: python tools/tests/test_coords.py"""

import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from coords import (  # noqa: E402
    bd09_to_wgs84,
    gcj02_to_wgs84,
    out_of_china,
    parse_latlng,
    wgs84_to_bd09,
    wgs84_to_gcj02,
)

FAILS = []


def check(label, got, want, tol):
    err = abs(got - want)
    ok = err <= tol
    print(f"{'PASS' if ok else 'FAIL'} {label}: got {got:.7f} want {want:.7f} (err {err:.2e})")
    if not ok:
        FAILS.append(label)


def haversine_m(a, b):
    r = 6371008.8
    p1, p2 = math.radians(a[0]), math.radians(b[0])
    dp, dl = p2 - p1, math.radians(b[1] - a[1])
    h = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(h))


# Anchor 1: Oriental Pearl Tower. GCJ-02 as shown on Chinese web maps -> expected WGS-84
# from the reference implementation used during reconnaissance.
pearl_gcj = (31.239703, 121.499733)
pearl_wgs_expected = (31.241703, 121.495284)
got = gcj02_to_wgs84(*pearl_gcj)
check("pearl gcj->wgs lat", got[0], pearl_wgs_expected[0], 3e-6)  # ~0.3 m
check("pearl gcj->wgs lng", got[1], pearl_wgs_expected[1], 3e-6)

# Anchor 2: Photon/OSM WGS-84 point for the same tower -> forward conversion must land on
# the GCJ-02 number the web maps publish.
photon_wgs = (31.2419464, 121.4952604)
got = wgs84_to_gcj02(*photon_wgs)
check("pearl wgs->gcj lat", got[0], 31.239947, 5e-5)
check("pearl wgs->gcj lng", got[1], 121.499710, 5e-5)

# Round trip must be numerically invertible.
rt = gcj02_to_wgs84(*wgs84_to_gcj02(31.2304, 121.4737))
check("roundtrip lat", rt[0], 31.2304, 1e-9)
check("roundtrip lng", rt[1], 121.4737, 1e-9)

# bd09 -> wgs84 must undo wgs84 -> bd09 exactly (Shenzhen).
rt2 = bd09_to_wgs84(*wgs84_to_bd09(22.5431, 114.0579))
check("roundtrip bd lat", rt2[0], 22.5431, 1e-6)
check("roundtrip bd lng", rt2[1], 114.0579, 1e-6)

# GCJ-02 must actually move the point, and the shift must be in the documented band.
shift = haversine_m((31.239703, 121.499733), gcj02_to_wgs84(31.239703, 121.499733))
print(f"INFO gcj02->wgs84 shift in Lujiazui: {shift:.0f} m")
check("shift within 50-700m", shift, 478.0, 120.0)

# Iterative inverse must beat naive subtraction by a real margin.
naive = (31.239703 + (31.239703 - wgs84_to_gcj02(31.239703, 121.499733)[0]),
         121.499733 + (121.499733 - wgs84_to_gcj02(31.239703, 121.499733)[1]))
gap = haversine_m(naive, gcj02_to_wgs84(31.239703, 121.499733))
print(f"INFO naive-subtract vs iterative: {gap:.1f} m apart")
if gap > 30:
    FAILS.append("naive vs iterative gap implausibly large")

# Out-of-china passthrough.
check("paris untouched", wgs84_to_gcj02(48.8584, 2.2945)[0], 48.8584, 0.0)
if out_of_china(31.24, 121.5):
    FAILS.append("Shanghai wrongly classified as out of china")

# Input parsing: lng-first detection and datum tagging.
check("parse lat,lng", parse_latlng("31.239703,121.499733", "gcj02")[0], 31.241703, 3e-6)
check("parse lng,lat", parse_latlng("121.499733,31.239703", "gcj02")[1], 121.495284, 3e-6)

print()
print("ALL PASS" if not FAILS else f"FAILURES: {FAILS}")
sys.exit(1 if FAILS else 0)
