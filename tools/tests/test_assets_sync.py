"""rootmodule 与 App assets 的脚本必须逐字节同步：ROOT-only 管线由 App 通过 su 执行
assets 副本，KernelSU 模块执行 rootmodule 原件——两份漂移意味着两条管线行为不同。"""

import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
fails = 0

for name in ("mockloc-lib.sh", "service.sh"):
    src = ROOT / "rootmodule" / name
    dst = ROOT / "app" / "src" / "main" / "assets" / "rootmodule" / name
    if not src.exists() or not dst.exists():
        print(f"FAIL 缺失文件: {src.name if not src.exists() else dst.name}")
        fails += 1
        continue
    if src.read_bytes() != dst.read_bytes():
        print(f"FAIL 内容漂移: {name}（重新 cp rootmodule/{name} app/src/main/assets/rootmodule/{name}）")
        fails += 1
    else:
        print(f"PASS {name} 同步")

print("ALL PASS" if fails == 0 else f"{fails} 项失败")
sys.exit(0 if fails == 0 else 1)
