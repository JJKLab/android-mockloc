"""守 root 模块 sync_app_prefs 的提取写法（真机判据的本地替代）。

真机（PJF110 / Android 16 / KernelSU / toybox sed 0.8.12）实测两个坑：
1. 范围地址 `/name="module_conf"/,/<\\/string>/` 在 start 与 end 同行时**不会当场收敛**，
   会把后续行（如 `<string name="osmdroid.basePath">…`）一起带进 config.prop。
2. 旧版本会留下带 `&#10;`/XML 残尾的坏 config.prop，而 `[ prefs -nt CFG ]` 的 mtime 守卫
   会让它**永远不被修复**（tier-3 读到一行式 conf，spoofing 直接关）。

本机没有 toybox sed，无法直接复现，所以静态守住这两个写法。
"""
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
LIB = ROOT / "rootmodule/mockloc-lib.sh"
SERVICE = ROOT / "rootmodule/service.sh"

failures = []


def check(label, ok, detail=""):
    print(f"{'PASS' if ok else 'FAIL'} {label}" + (f": {detail}" if detail else ""))
    if not ok:
        failures.append(label)


def main():
    lib = LIB.read_text(encoding="utf-8")
    svc = SERVICE.read_text(encoding="utf-8")

    # 1) 不用范围地址提取（toybox 的范围地址有坑）。
    check("sync_app_prefs 不用范围地址", "/,/</string>/p" not in lib and "/,/p" not in lib)
    check("sync_app_prefs 用单行替换", 's/.*name="module_conf"' in lib)

    # 2) 坏配置必须能自愈，不能被 mtime 守卫挡掉。
    check("有损坏自愈守卫（&#10; 或 XML 残尾）",
          "&#10;" in lib and "<string" in lib and "grep -q" in lib)

    # 3) 反转义仍在（Android XmlUtils 把换行写成 &#10;）。
    check("保留 &#10; 反转义", "s/&#10;/" in lib)

    # 4) 真机实测：早期启动 sleep 不可用会让循环空转、刷爆 service.log（43 万条）。
    check("service.sh 有日志上限", "service.log" in svc and "1048576" in svc)
    check("service.sh 的 sleep 有降级", "sleep 0" in svc)

    print("ALL PASS" if not failures else f"{len(failures)} FAILED")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
