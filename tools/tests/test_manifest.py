"""Well-formedness and a few load-bearing facts about the app's XML.

This exists because a `//` comment once slipped inside an XML start-tag (Kotlin habit, invalid in
XML), which the local Kotlin type-check cannot see - raw kotlinc never opens the manifest - and the
CI build died in the manifest merger for it. Parsing these files costs milliseconds.
"""

import pathlib
import sys
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[2]
ANDROID_NS = "http://schemas.android.com/apk/res/android"

fails = 0


def check(label, ok, detail=""):
    global fails
    print(f"{'PASS' if ok else 'FAIL'} {label}{': ' + detail if detail else ''}")
    if not ok:
        fails += 1


def xml_files():
    return sorted((ROOT / "app" / "src" / "main").rglob("*.xml"))


def main():
    files = xml_files()
    check("找到 XML 文件", bool(files), f"{len(files)} 个")

    trees = {}
    for path in files:
        try:
            trees[path] = ET.parse(path).getroot()
        except ET.ParseError as exc:
            check(f"XML 可解析 {path.name}", False, str(exc))
    check("所有 XML 都能解析", len(trees) == len(files), f"{len(trees)}/{len(files)}")

    manifest = trees.get(ROOT / "app" / "src" / "main" / "AndroidManifest.xml")
    if manifest is None:
        print("manifest 解析失败，跳过后续断言")
        return 1

    def attr(elem, name):
        return elem.get(f"{{{ANDROID_NS}}}{name}") if elem is not None else None

    permissions = {attr(p, "name") for p in manifest.findall("uses-permission")}
    # ACCESS_MOCK_LOCATION is what lets 开发者选项's picker list us at all.
    check("声明了 ACCESS_MOCK_LOCATION", "android.permission.ACCESS_MOCK_LOCATION" in permissions,
          ", ".join(sorted(p for p in permissions if p)))
    # Without it, Android 11+ package visibility hides most installed apps from the policy page.
    check("声明了 QUERY_ALL_PACKAGES", "android.permission.QUERY_ALL_PACKAGES" in permissions)
    # Boot restore (REFACTOR-PLAN 1.1): without the permission the receiver is registered but the
    # system never delivers BOOT_COMPLETED to it, which looks exactly like a dead feature.
    check("声明了 RECEIVE_BOOT_COMPLETED", "android.permission.RECEIVE_BOOT_COMPLETED" in permissions)

    app = manifest.find("application")
    theme = attr(app, "theme") or ""
    # Regression guard for the duplicated title: a *ActionBar theme draws the app label above our
    # own header band, so the name appeared twice on every screen. DayNight support moved the
    # choice into MocklocTheme (values[-night]/themes.xml).
    check("主题是 MocklocTheme（DayNight/NoActionBar）", theme == "@style/MocklocTheme", theme)
    theme_files = sorted((ROOT / "app" / "src" / "main").rglob("themes.xml"))
    check("两套 themes.xml 都在", len(theme_files) == 2,
          ", ".join(str(p.relative_to(ROOT)) for p in theme_files))
    theme_texts = {str(p.relative_to(ROOT)): p.read_text(encoding="utf-8") for p in theme_files}
    check("日间主题 Light.NoActionBar",
          any("Light.NoActionBar" in t for k, t in theme_texts.items() if "values-night" not in k))
    check("夜间主题 NoActionBar",
          any("NoActionBar" in t for k, t in theme_texts.items() if "values-night" in k))

    services = {attr(s, "name"): s for s in app.findall("service")}
    mock_service = services.get(".MockService")
    check("MockService 需要 DUMP 权限", attr(mock_service, "permission") == "android.permission.DUMP",
          str(attr(mock_service, "permission")))
    check("MockService 的 FGS 类型是 location",
          attr(mock_service, "foregroundServiceType") == "location")

    activities = {attr(a, "name"): a for a in app.findall("activity")}
    main_activity = activities.get(".MainActivity")
    check("MainActivity 已 exported", attr(main_activity, "exported") == "true")

    receivers = {attr(r, "name"): r for r in app.findall("receiver")}
    boot = receivers.get(".BootReceiver")
    check("BootReceiver 已注册", boot is not None)
    boot_actions = {a.get(f"{{{ANDROID_NS}}}name")
                    for f in (boot.findall("intent-filter") if boot is not None else [])
                    for a in f.findall("action")}
    check("BootReceiver 监听 BOOT_COMPLETED",
          "android.intent.action.BOOT_COMPLETED" in boot_actions, ", ".join(sorted(boot_actions)))

    strings = trees.get(ROOT / "app" / "src" / "main" / "res" / "values" / "strings.xml")
    names = {s.get("name") for s in strings.findall("string")} if strings is not None else set()
    referenced = {v for v in [attr(app, "description"), attr(main_activity, "label")] if v}
    missing = [v for v in referenced if not v.startswith("@string/") or v.split("/")[-1] not in names]
    check("manifest 引用的字符串都存在", not missing, ", ".join(missing))

    print("ALL PASS" if fails == 0 else f"{fails} 项失败")
    return 0 if fails == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
