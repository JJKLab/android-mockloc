"""Search the DEX strings inside an APK for class/method evidence.

Used to answer "which location classes does this app actually contain, and does it look at the
mock flag?" without guessing from public SDK docs.
"""

import re
import sys
import zipfile

PATTERNS = [
    b"com/amap/api/location",
    b"com/tencent/map/geolocation",
    b"com/baidu/location",
    b"android/location/LocationManager",
    b"isFromMockProvider",
    b"isMock",
    b"mock",
    b"AMapLocation",
    b"TencentLocation",
    b"BDLocation",
]


def main(path):
    zf = zipfile.ZipFile(path)
    dexes = [n for n in zf.namelist() if n.endswith(".dex")]
    print(f"{path}: {len(dexes)} dex files")
    found = {}
    descriptors = {}
    for name in dexes:
        data = zf.read(name)
        for pat in PATTERNS:
            count = data.count(pat)
            if count:
                found[pat] = found.get(pat, 0) + count
        for m in re.finditer(rb"L[a-zA-Z0-9_/$]{4,90}Location[a-zA-Z0-9_/$]{0,60};", data):
            descriptors.setdefault(m.group(0).decode("ascii", "replace"), 0)
            descriptors[m.group(0).decode("ascii", "replace")] += 1
    print("\n--- 关键串命中次数 ---")
    for pat, count in sorted(found.items(), key=lambda kv: -kv[1]):
        print(f"  {count:8}  {pat.decode()}")
    print("\n--- 含 Location 的类描述符（按出现次数，前 30）---")
    top = sorted(descriptors.items(), key=lambda kv: -kv[1])[:30]
    for desc, count in top:
        print(f"  {count:6}  {desc}")


if __name__ == "__main__":
    for arg in sys.argv[1:]:
        main(arg)
        print()
