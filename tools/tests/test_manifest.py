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

    app = manifest.find("application")
    theme = attr(app, "theme") or ""
    # Regression guard for the duplicated title: a *ActionBar theme draws the app label above our
    # own header band, so the name appeared twice on every screen.
    check("主题是 NoActionBar", "NoActionBar" in theme, theme)

    services = {attr(s, "name"): s for s in app.findall("service")}
    mock_service = services.get(".MockService")
    check("MockService 需要 DUMP 权限", attr(mock_service, "permission") == "android.permission.DUMP",
          str(attr(mock_service, "permission")))
    check("MockService 的 FGS 类型是 location",
          attr(mock_service, "foregroundServiceType") == "location")

    activities = {attr(a, "name"): a for a in app.findall("activity")}
    main_activity = activities.get(".MainActivity")
    check("MainActivity 已 exported", attr(main_activity, "exported") == "true")

    strings = trees.get(ROOT / "app" / "src" / "main" / "res" / "values" / "strings.xml")
    names = {s.get("name") for s in strings.findall("string")} if strings is not None else set()
    referenced = {v for v in [attr(app, "description"), attr(main_activity, "label")] if v}
    missing = [v for v in referenced if not v.startswith("@string/") or v.split("/")[-1] not in names]
    check("manifest 引用的字符串都存在", not missing, ", ".join(missing))

    print("ALL PASS" if fails == 0 else f"{fails} 项失败")
    return 0 if fails == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
