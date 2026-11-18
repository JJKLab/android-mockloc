"""渲染器 ↔ Config 键契约守卫（v1.3.0 走查 P1-14）。

`ModuleBridge.render()` 是配置离开 App 的唯一出口，`Config.parseInto()` 是模块读回的唯一入口。
两边键集合一旦漂移，症状是"每次 sync 静默重置"——App 设了但模块当没设，且本地无编译错误。
本测试用文本解析把这条契约钉在 CI 里，不依赖 Kotlin 工具链。

同时守 mockloc.py：CLI 写的键必须是渲染器也写的键，否则 PC 侧写的配置模块永远读不到。
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
BRIDGE = ROOT / "app/src/main/java/com/mockloc/ModuleBridge.kt"
CONFIG = ROOT / "app/src/main/java/com/mockloc/Config.kt"
CLI = ROOT / "tools/mockloc.py"

failures = []


def check(label, ok, detail=""):
    print(f"{'PASS' if ok else 'FAIL'} {label}" + (f": {detail}" if detail else ""))
    if not ok:
        failures.append(label)


def render_keys():
    text = BRIDGE.read_text(encoding="utf-8")
    return {"MOCKLOC_" + k for k in re.findall(r'appendLine\("MOCKLOC_([A-Z0-9_]+)=', text)}


def config_keys():
    text = CONFIG.read_text(encoding="utf-8")
    return set(re.findall(r'"(MOCKLOC_[A-Z0-9_]+)" ->', text))


def cli_keys():
    text = CLI.read_text(encoding="utf-8")
    return set(re.findall(r'"(MOCKLOC_[A-Z0-9_]+)":', text))


def main():
    r, c = render_keys(), config_keys()
    check("渲染器写了至少 20 个键", len(r) >= 20, str(len(r)))
    check("Config 解析了至少 20 个键", len(c) >= 20, str(len(c)))

    # 渲染器写而 Config 不读 = 模块永远看不到（漏键 → 静默重置）。
    render_only = sorted(r - c)
    check("渲染器写的键 Config 都解析", not render_only, ",".join(render_only))

    # Config 读而渲染器不写 = 只能靠 PC/手写 conf 才能设，App 无法表达（死键）。
    config_only = sorted(c - r)
    check("Config 解析的键渲染器都写", not config_only, ",".join(config_only))

    # 三路致盲必须各自独立存在（P1-1）：少任何一路都说明又塌缩回单键。
    for key in ("MOCKLOC_BLIND_WIFI", "MOCKLOC_BLIND_CELL", "MOCKLOC_BLIND_BLE", "MOCKLOC_NETCUT"):
        check(f"契约含 {key}", key in r and key in c)

    # CLI 写的键必须是渲染器写的键的子集（PC 侧不能发明模块不认的键）。
    cli_only = sorted(cli_keys() - r)
    check("mockloc.py 不写渲染器没有的键", not cli_only, ",".join(cli_only))

    print("ALL PASS" if not failures else f"{len(failures)} FAILED")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
