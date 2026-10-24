"""mockloc phase-1 CLI: resolve a place to WGS-84 and inject it as a system test provider.

Two privilege paths:
  adb  - shell uid 2000 granted the mock_location app-op. No root needed, lost on reboot.
  su   - root, needed so the KernelSU/APatch boot module can re-apply without a PC.

Usage: `python tools/mockloc.py` alone opens the menu; flags exist for scripts and cron.
"""

import argparse
import json
import math
import os
import random
import shlex
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import amap  # noqa: E402
from coords import gcj02_to_wgs84, parse_latlng  # noqa: E402

PROVIDER_DEFAULT = "gps"
CONFIG_REMOTE = "/data/adb/mockloc/config.prop"
LOCAL_CONFIG = os.path.join(os.path.dirname(os.path.abspath(__file__)), "config.json")
SU_CMD = os.environ.get("MOCKLOC_SU", "su -c")
COST_GEOCODE = "高德地理编码 1 次（个人实名 key 免费额度 5000/天）"


class DeviceError(Exception):
    pass


def run_adb(serial, shell_cmd=None, args=None, timeout=30):
    base = [os.environ.get("ADB", "adb")]
    if serial:
        base += ["-s", serial]
    if shell_cmd is not None:
        base += ["shell", shell_cmd]
    else:
        base += args or []
    try:
        proc = subprocess.run(base, capture_output=True, text=True, timeout=timeout)
    except FileNotFoundError as exc:
        raise DeviceError("adb not found - set ADB=/path/to/adb.exe") from exc
    except subprocess.TimeoutExpired as exc:
        raise DeviceError(f"adb timed out: {shell_cmd or args}") from exc
    return proc.returncode, (proc.stdout or "").strip(), (proc.stderr or "").strip()


class Target:
    def __init__(self, serial=None, mode="su"):
        self.mode = mode
        code, out, err = run_adb(None, args=["devices"])
        if code != 0:
            raise DeviceError(f"adb devices failed: {(out + err)[:200]}")
        online = [ln.split()[0] for ln in out.splitlines()[1:] if ln.strip().endswith("device")]
        if serial:
            if serial not in online:
                raise DeviceError(f"{serial} not online (found: {online or 'none'})")
            self.serial = serial
        elif len(online) == 0:
            raise DeviceError("no device on adb - plug in the phone, enable USB debugging, accept the prompt")
        elif len(online) > 1:
            raise DeviceError(f"multiple devices {online} - pass --serial")
        else:
            self.serial = online[0]

    def info(self):
        code, out, err = run_adb(self.serial, "getprop ro.build.version.release; getprop ro.build.version.sdk")
        return out.replace("\n", " / ")

    def sh(self, cmd, su=None):
        use_su = (self.mode == "su") if su is None else su
        wrapped = f"{SU_CMD} {shlex.quote(cmd)}" if use_su else cmd
        code, out, err = run_adb(self.serial, wrapped)
        blob = out + err
        if code != 0 and ("Permission denied" in blob or "not found" in blob):
            raise DeviceError(f"shell failed: {blob[:200]}")
        return code, out, err


class DryRunTarget:
    """Prints the exact shell commands instead of sending them, so the wiring is reviewable offline."""

    serial = "dry-run"
    mode = "su"

    def __init__(self):
        self.sent = []

    def info(self):
        return "dry-run"

    def sh(self, cmd, su=None):
        self.sent.append(cmd)
        prefix = "su -c " if (su if su is not None else self.mode == "su") else ""
        print(f"  $ {prefix}{cmd}")
        if cmd.startswith("appops get"):
            return 0, "allow", ""
        if "is-location-enabled" in cmd:
            return 0, "true", ""
        return 0, "", ""


def load_key(cli_key=None):
    if cli_key:
        return cli_key
    env = os.environ.get("AMAP_KEY")
    if env:
        return env.strip()
    if os.path.exists(LOCAL_CONFIG):
        with open(LOCAL_CONFIG, encoding="utf-8") as fh:
            return (json.load(fh).get("amap_key") or "").strip()
    return ""


def ensure_appop(t, uids=("0", "2000")):
    for uid in uids:
        t.sh(f"appops set {uid} android:mock_location allow")
    # Read back every uid we just touched: in su mode the binder caller is 0, in adb mode 2000,
    # and only the caller's own mode decides whether LMS accepts the injection.
    states = {}
    for uid in uids:
        code, out, err = t.sh(f"appops get {uid} android:mock_location", su=t.mode == "su")
        states[uid] = (out or err).strip() or "unknown"
    if not any("allow" in v.lower() for v in states.values()):
        raise DeviceError(f"mock_location app-op not granted: {states}")
    return " ".join(f"uid{k}={v.split()[0]}" for k, v in states.items())


def ensure_master_switch(t):
    t.sh("cmd location set-location-enabled true")
    code, out, err = t.sh("cmd location is-location-enabled")
    if out.strip().lower() != "true":
        raise DeviceError(f"location master switch off: {out or err}")


def ensure_provider(t, provider):
    t.sh(f"cmd location providers add-test-provider {provider}")
    t.sh(f"cmd location providers set-test-provider-enabled {provider} true")


def inject(t, provider, lat, lng, accuracy):
    stamp = int(time.time() * 1000)
    code, out, err = t.sh(
        "cmd location providers set-test-provider-location "
        f"{provider} --location {lat:.7f},{lng:.7f} --accuracy {accuracy:.1f} --time {stamp}"
    )
    blob = (out + err).lower()
    if code != 0 or "exception" in blob:
        raise DeviceError(f"injection failed: {(out or err)[:300]}")
    return stamp


def read_back(t, provider):
    """Ask the device what it currently reports, so the user is not trusting a silent exit 0."""
    code, out, err = t.sh(f"dumpsys location | grep -iE '{provider}|mock' | head -20")
    return out or err


def offset_meters(lat, lng, meters, bearing_rad):
    d_lat = meters * math.cos(bearing_rad) / 111132.0
    d_lng = meters * math.sin(bearing_rad) / (111412.8 * math.cos(math.radians(lat)))
    return lat + d_lat, lng + d_lng


def jitter(lat, lng, radius_m):
    if radius_m <= 0:
        return lat, lng
    dist = radius_m * math.sqrt(random.random())
    return offset_meters(lat, lng, dist, random.uniform(0, 2 * math.pi))


def write_config(t, cfg):
    body = "\n".join(f"{k}={v}" for k, v in cfg.items())
    t.sh(f"mkdir -p $(dirname {CONFIG_REMOTE})")
    code, out, err = t.sh(f"printf '%s\\n' {shlex.quote(body)} > {CONFIG_REMOTE}; chmod 600 {CONFIG_REMOTE}")
    if code != 0:
        raise DeviceError(f"cannot write {CONFIG_REMOTE}: {(out or err)[:200]}")
    return CONFIG_REMOTE


def resolve_place(query, city, key, datum, first=False):
    if not key:
        raise DeviceError("no Amap key - set AMAP_KEY, add tools/config.json, or pass --lat/--lng directly")
    try:
        hits = amap.geocode(key, query, city)
    except amap.AmapError as exc:
        # info() comes straight from Amap, so it stays accurate even where the numeric
        # infocode table is not documented for every service.
        hint = "  <- key 无效，或申的不是「Web服务」类型" if exc.infocode == "10001" else ""
        raise DeviceError(f"高德返回 info={exc} infocode={exc.infocode}{hint}") from exc
    except OSError as exc:
        raise DeviceError(f"高德接口不可达: {exc}") from exc
    print(f"[{COST_GEOCODE}] 命中 {len(hits)} 条")
    if not hits:
        raise DeviceError(f"高德没有解析出 {query!r}（status=1 但 geocodes 为空，换个写法或加 --city）")
    if not first:
        for i, h in enumerate(hits, 1):
            print(f"  {i:>2}. {h['name']}  [{h['level']}]  GCJ-02 {h['lat']:.6f},{h['lng']:.6f}")
        pick = int(input(f"选一个 [1-{len(hits)}]: ").strip() or "1")
        chosen = hits[pick - 1]
    else:
        chosen = hits[0]
    if datum == "gcj02":
        lat, lng = gcj02_to_wgs84(chosen["lat"], chosen["lng"])
    else:
        lat, lng = chosen["lat"], chosen["lng"]
    print(f"  -> {chosen['name']}\n     GCJ-02 {chosen['lat']:.6f},{chosen['lng']:.6f}"
          f"\n     WGS-84 {lat:.6f},{lng:.6f}  (注入用这组)")
    return lat, lng, chosen["name"]


def cmd_set(t, args):
    if args.place:
        lat, lng, label = resolve_place(args.place, args.city or "", load_key(args.key), args.datum, args.first)
    elif args.coord:
        lat, lng = parse_latlng(args.coord, args.datum)
        label = f"{lat:.6f},{lng:.6f}"
    else:
        raise DeviceError("need --place or --coord")

    state = ensure_appop(t)
    ensure_master_switch(t)
    ensure_provider(t, args.provider)
    stamp = inject(t, args.provider, lat, lng, args.accuracy)
    print(f"[ok] appop={state} provider={args.provider} ts={stamp}")
    if args.persist:
        if t.mode != "su":
            raise DeviceError("--persist needs --mode su")
        path = write_config(t, {
            "MOCKLOC_ENABLED": "true",
            "MOCKLOC_PROVIDER": args.provider,
            "MOCKLOC_LAT": f"{lat:.7f}",
            "MOCKLOC_LNG": f"{lng:.7f}",
            "MOCKLOC_ACC": f"{args.accuracy:.1f}",
            "MOCKLOC_RADIUS": f"{args.radius:.1f}",
            "MOCKLOC_INTERVAL": f"{int(args.interval)}",
            "MOCKLOC_TARGETS": ",".join(args.target or []),
            "MOCKLOC_LABEL": label,
        })
        print(f"[ok] 开机自恢复配置已写入 {path}（root 模块会读它）")
    return lat, lng, label


def cmd_watch(t, args):
    lat, lng, label = cmd_set(t, args)
    if args.dry_run:
        return
    print(f"[watch] {label} 半径 {args.radius:.0f}m 每 {args.interval:.0f}s 刷新，Ctrl-C 停")
    try:
        while True:
            time.sleep(args.interval)
            jlat, jlng = jitter(lat, lng, args.radius)
            inject(t, args.provider, jlat, jlng, args.accuracy)
    except KeyboardInterrupt:
        print("\n[watch] stopped")


def cmd_off(t, args):
    code, out, err = t.sh(f"cmd location providers set-test-provider-enabled {args.provider} false")
    t.sh(f"cmd location providers remove-test-provider {args.provider}")
    if args.persist and t.mode == "su":
        t.sh(f"rm -f {CONFIG_REMOTE}")
    print(f"[ok] {args.provider} 测试 provider 已移除（真实 GPS 恢复）")
    print("     app-op android:mock_location 仍是 allow，那不影响真实定位；要收权跑 "
          "`adb shell appops set 2000 android:mock_location deny`")


def cmd_status(t, args):
    code, out, err = t.sh("cmd location is-location-enabled")
    print(f"master switch : {out or err}")
    code, out, err = t.sh("appops get 2000 android:mock_location", su=False)
    print(f"appop(2000)   : {out or err}")
    code, out, err = t.sh("appops get 0 android:mock_location")
    print(f"appop(0/root) : {out or err}")
    code, out, err = t.sh("settings get secure mock_location")
    print(f"secure flag   : {out or err}")
    code, out, err = t.sh(f"dumpsys location | grep -iE 'mock|{args.provider} provider' | head -12")
    print("dumpsys       :")
    print("\n".join(f"  {ln}" for ln in (out or err).splitlines()))


def cmd_doctor(t, args):
    print("逐项自检（每项都是一次真调用，不是读配置）")
    checks = [
        ("adb 在线", lambda: t.serial),
        ("系统版本", t.info),
        ("su 可用", lambda: t.sh("id", su=True)[1] if t.mode == "su" else "skipped (mode=adb)"),
        ("location 服务", lambda: t.sh("cmd location is-location-enabled")[1]),
        ("appop 可授", lambda: ensure_appop(t)),
        ("测试 provider 可建", lambda: ensure_provider(t, args.provider)),
        ("注入可用", lambda: inject(t, args.provider, 31.230416, 121.473701, 5.0)),
    ]
    for name, fn in checks:
        try:
            print(f"  PASS  {name}: {fn()}")
        except Exception as exc:
            print(f"  FAIL  {name}: {exc}")
            break
    print("\n注意：这条链路给到 App 的定位 isMock()=true，App 侧自检能看出来。")
    print("过检测要靠阶段 2 的 libxposed 模块（把 mock bit 抹掉 + 在 system_server 里就地改写）。")


MENU = [
    ("1", "搜索地点并注入", "set --place"),
    ("2", "输入经纬度注入", "set --coord"),
    ("3", "持续刷新（防时间戳过期）", "watch"),
    ("4", "查看当前状态", "status"),
    ("5", "逐项自检", "doctor"),
    ("6", "关闭并恢复真实 GPS", "off"),
    ("0", "退出", ""),
]


def interactive():
    print("mockloc - 安卓虚拟定位（阶段1：系统测试 provider）")
    t = Target(mode=input("权限模式 su/adb [su]: ").strip() or "su")
    print(f"设备 {t.serial} :: {t.info()}")
    while True:
        print()
        for k, label, _ in MENU:
            print(f"  {k}. {label}")
        choice = input("> ").strip()
        try:
            if choice == "1":
                place = input("地点名: ").strip()
                city = input("城市(可空): ").strip()
                cmd_set(t, build_args(place=place, city=city))
            elif choice == "2":
                coord = input("经纬度 (lat,lng 或 lng,lat): ").strip()
                datum = input("坐标系 wgs84/gcj02/bd09 [gcj02]: ").strip() or "gcj02"
                cmd_set(t, build_args(coord=coord, datum=datum))
            elif choice == "3":
                cmd_watch(t, build_args(watch=True))
            elif choice == "4":
                cmd_status(t, build_args())
            elif choice == "5":
                cmd_doctor(t, build_args())
            elif choice == "6":
                cmd_off(t, build_args())
            elif choice == "0":
                return
        except DeviceError as exc:
            print(f"[error] {exc}")


def build_args(place=None, city=None, coord=None, datum="gcj02", watch=False):
    return argparse.Namespace(
        place=place, city=city, coord=coord, datum=datum, key=None, provider=PROVIDER_DEFAULT,
        accuracy=5.0, persist=True, dry_run=False, radius=(20.0 if watch else 0.0),
        interval=15.0, first=False, target=[],
    )


def main():
    parser = argparse.ArgumentParser(description="Android mock-location CLI (phase 1)")
    parser.add_argument("--serial")
    parser.add_argument("--mode", choices=["su", "adb"], default="su")
    sub = parser.add_subparsers(dest="cmd")

    common = argparse.ArgumentParser(add_help=False)
    common.add_argument("--provider", default=PROVIDER_DEFAULT)
    common.add_argument("--accuracy", type=float, default=5.0)
    common.add_argument("--dry-run", action="store_true", help="print the device commands, send nothing")

    p_set = sub.add_parser("set", parents=[common], help="resolve + inject once")
    p_set.add_argument("--place")
    p_set.add_argument("--city", default="")
    p_set.add_argument("--coord", help="lat,lng (also accepts lng,lat)")
    p_set.add_argument("--datum", choices=["wgs84", "gcj02", "bd09"], default="gcj02")
    p_set.add_argument("--key", default=None, help="Amap Web服务 key")
    p_set.add_argument("--first", action="store_true", help="take geocode hit #1 (for scripts)")
    p_set.add_argument("--radius", type=float, default=0.0, help="jitter radius persisted for the module")
    p_set.add_argument("--interval", type=float, default=15.0, help="root-module refresh interval, seconds")
    p_set.add_argument("--target", action="append", default=[], metavar="PKG",
                       help="restrict the Xposed module to these packages (repeatable)")
    p_set.add_argument("--persist", action="store_true", help="write boot-module config too")

    p_watch = sub.add_parser("watch", parents=[common], help="keep re-injecting with fresh timestamps")
    p_watch.add_argument("--place")
    p_watch.add_argument("--city", default="")
    p_watch.add_argument("--coord")
    p_watch.add_argument("--datum", choices=["wgs84", "gcj02", "bd09"], default="gcj02")
    p_watch.add_argument("--key", default=None)
    p_watch.add_argument("--first", action="store_true")
    p_watch.add_argument("--radius", type=float, default=20.0, help="jitter radius in metres")
    p_watch.add_argument("--interval", type=float, default=15.0)
    p_watch.add_argument("--target", action="append", default=[], metavar="PKG")
    p_watch.add_argument("--persist", action="store_true")

    p_off = sub.add_parser("off", parents=[common], help="remove the test provider")
    p_off.add_argument("--persist", action="store_true", help="also delete the boot config")

    sub.add_parser("status", parents=[common])
    p_doc = sub.add_parser("doctor", parents=[common])

    args = parser.parse_args()
    if not args.cmd:
        interactive()
        return

    ns = args
    for name, value in (("radius", 0.0), ("interval", 15.0), ("persist", False),
                        ("dry_run", False), ("mode", "su"), ("serial", None), ("target", [])):
        if not hasattr(ns, name):
            setattr(ns, name, value)
    t = DryRunTarget() if ns.dry_run else Target(ns.serial, ns.mode)
    try:
        if args.cmd == "set":
            cmd_set(t, ns)
        elif args.cmd == "watch":
            cmd_watch(t, ns)
        elif args.cmd == "off":
            cmd_off(t, ns)
        elif args.cmd == "status":
            cmd_status(t, ns)
        elif args.cmd == "doctor":
            cmd_doctor(t, ns)
    except DeviceError as exc:
        print(f"[error] {exc}", file=sys.stderr)
        sys.exit(2)
    except KeyboardInterrupt:
        sys.exit(130)


if __name__ == "__main__":
    main()
