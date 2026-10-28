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
import re
import random
import shlex
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import amap  # noqa: E402
from coords import gcj02_to_wgs84, parse_latlng  # noqa: E402

PROVIDER_DEFAULT = "gps"
PROVIDERS_DEFAULT = "gps,network,fused"


def provider_list(args):
    """--provider pins one name (for experiments); otherwise the full list gets mocked, because
    on the test device leaving `network` real leaked the true position while gps/passive lied."""
    single = getattr(args, "provider", None)
    if single:
        return [single]
    return [p.strip() for p in (getattr(args, "providers", "") or "").split(",") if p.strip()]
SELF_PKG = "com.mockloc"
CONFIG_INBOX = "/data/local/tmp/mockloc.in"
CONFIG_REMOTE = "/data/adb/mockloc/config.prop"
CONFIG_PUB = "/data/local/tmp/mockloc.conf"
APP_PREFS = "/data/data/com.mockloc/shared_prefs/settings.xml"
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

    def __init__(self, mode="su"):
        self.mode = mode
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


def blind_spec(value):
    """Normalise a blinding spec to what the module parses: false | true | pkg,pkg.

    Tri-state on purpose. The layer that actually moves 高德地图 breaks the host app's own WiFi
    features, so "only these packages" is the setting people want, and an empty string cannot tell
    "nobody" apart from "everybody in scope".
    """
    if value is None or value is False:
        return "false"
    if value is True:
        return "true"
    text = str(value).strip()
    if text.lower() in ("", "false", "0", "none", "no"):
        return "false"
    if text.lower() in ("true", "1", "all", "*", "yes"):
        return "true"
    packages = [p.strip() for p in text.replace("，", ",").split(",") if p.strip()]
    if not all(re.fullmatch(r"[A-Za-z0-9._]+", p) for p in packages):
        raise DeviceError(f"致盲包名看不懂：{value}（要逗号分隔的包名，或 true/false）")
    return ",".join(packages)


def write_config(t, cfg):
    """Hand the config to the root module through the shell-writable inbox: KernelSU gives adb no
    su, so writing /data/adb directly from the PC is not an option on this device."""
    body = "\n".join(f"{k}={v}" for k, v in cfg.items())
    code, out, err = t.sh(
        f"printf '%s\\n' {shlex.quote(body)} > {CONFIG_INBOX}; chmod 664 {CONFIG_INBOX}",
        su=t.mode == "su",
    )
    if code != 0:
        raise DeviceError(f"cannot write {CONFIG_INBOX}: {(out or err)[:200]}")
    return CONFIG_INBOX


def disable_persist(t):
    for path in (CONFIG_INBOX, CONFIG_REMOTE):
        code, _, _ = t.sh(
            f"test -f {path} && sed -i 's/^MOCKLOC_ENABLED=.*/MOCKLOC_ENABLED=false/; "
            f"s/^MOCKLOC_GLOBAL=.*/MOCKLOC_GLOBAL=false/' {path}"
        )
        if code == 0:
            return path
    return None


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


SERVICE_COMPONENT = "com.mockloc/.MockService"


def app_start(t, providers, lat, lng, accuracy, radius, interval):
    """Drive the in-app test provider. Needs no root and no appops: the app-op comes from the
    developer-options picker, which Settings (a privileged process) writes."""
    extras = (f"--es cmd start --es providers {','.join(providers)} "
              f"--es lat {lat:.7f} --es lng {lng:.7f} "
              f"--es acc {accuracy:.1f} --es radius {radius:.1f} --es interval {int(interval)}")
    code, out, err = t.sh(f"am start-foreground-service -n {SERVICE_COMPONENT} {extras}", su=False)
    blob = (out + err).lower()
    if code != 0 or "exception" in blob or "not found" in blob:
        raise DeviceError(f"启动 App 内 provider 失败: {(out or err)[:220]}")


def app_stop(t, providers):
    t.sh(f"am start-foreground-service -n {SERVICE_COMPONENT} --es cmd stop "
         f"--es providers {','.join(providers)}", su=False)


def app_readback(t):
    code, out, err = t.sh("dumpsys location | grep -i mock | head -5", su=False)
    return (out or "").strip()


def cmd_set(t, args):
    if args.place:
        lat, lng, label = resolve_place(args.place, args.city or "", load_key(args.key), args.datum, args.first)
    elif args.coord:
        lat, lng = parse_latlng(args.coord, args.datum)
        label = f"{lat:.6f},{lng:.6f}"
    else:
        raise DeviceError("need --place or --coord")

    providers = provider_list(args)

    if args.mode == "app":
        app_start(t, providers, lat, lng, args.accuracy, args.radius, args.interval)
        if isinstance(t, DryRunTarget):
            print("[dry-run] 未真跑，回读与 app-op 状态都还没法判断")
            return lat, lng, label
        read = app_readback(t)
        if "mock" in read.lower():
            print(f"[ok] App 内 provider 已生效（app-op 来自开发者选项，不需 root）:\n{read}")
        else:
            raise DeviceError(
                "App 进程里 addTestProvider 没成，dumpsys 里看不到 mock 痕迹。\n"
                "  最大嫌疑：开发者选项里还没把 mockloc 设为「选择虚拟定位应用」。")
        return lat, lng, label

    if args.global_provider:
        state = ensure_appop(t)
        ensure_master_switch(t)
        for name in providers:
            ensure_provider(t, name)
            inject(t, name, lat, lng, args.accuracy)
        print(f"[ok] 全局 provider: appop={state} providers={providers}")
    elif not args.persist:
        raise DeviceError("--no-global 只是不建 provider；要么加 --persist 交给模块按 App 处理，要么去掉 --no-global")
    else:
        print("[ok] 跳过全局 provider：只写模块配置，按 App 生效（isMock 为 false）")

    if args.persist:
        path = write_config(t, {
            "MOCKLOC_ENABLED": "true",
            "MOCKLOC_GLOBAL": "true" if args.global_provider else "false",
            "MOCKLOC_PROVIDERS": ",".join(providers),
            "MOCKLOC_LAT": f"{lat:.7f}",
            "MOCKLOC_LNG": f"{lng:.7f}",
            "MOCKLOC_ACC": f"{args.accuracy:.1f}",
            "MOCKLOC_RADIUS": f"{args.radius:.1f}",
            "MOCKLOC_ALT": f"{args.alt:.1f}",
            "MOCKLOC_INTERVAL": f"{int(args.interval)}",
            "MOCKLOC_LOOP_INTERVAL": f"{int(args.interval)}",
            "MOCKLOC_TARGETS": ",".join(args.target or []),
            "MOCKLOC_LABEL": label,
            "MOCKLOC_GNSS": "true" if args.gnss_spoof else "false",
            "MOCKLOC_BLIND_WIFI": blind_spec(args.blind_wifi),
            "MOCKLOC_BLIND_CELL": blind_spec(args.blind_cell),
            "MOCKLOC_PROBE": "true" if args.probe else "false",
            "MOCKLOC_NETCUT": ",".join(args.netcut or []),
        })
        print(f"[ok] 开机自恢复配置已写入 {path}（root 模块会读它）")
    return lat, lng, label


def cmd_watch(t, args):
    lat, lng, label = cmd_set(t, args)
    if args.mode == "app":
        print("[watch] App 内 provider 自己按 interval 循环，无需本命令常驻")
        return
    if not args.global_provider:
        print("[watch] 按 App 模式无需常驻循环：模块每 1 秒自行重抖坐标")
        return
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
    providers = provider_list(args)
    if args.mode == "app":
        app_stop(t, providers)
        read = app_readback(t)
        print("[ok] App 内 provider 已停" if not read else f"[warn] dumpsys 仍有 mock 痕迹:\n{read}")
        return
    for name in providers:
        t.sh(f"cmd location providers set-test-provider-enabled {name} false")
        t.sh(f"cmd location providers remove-test-provider {name}")
    if args.wipe:
        t.sh(f"rm -f {CONFIG_INBOX}")
        t.sh(f"rm -f {CONFIG_REMOTE}")
        print(f"[ok] provider 已移除，两份配置（收件箱 + /data/adb）已删除")
        return
    hit = disable_persist(t)
    # Flip the switches instead of deleting: the module and the boot loop both read them, and the
    # coordinates stay behind so a later `set` can reuse the place.
    if hit:
        print(f"[ok] provider 已移除，{hit} 已置为双关（模块与开机循环都会停）")
    else:
        print("[warn] 设备上没找到可读的配置文件，只停了系统 provider；模块侧请自行关掉开关")


def cmd_sync(t, args):
    """App 选点 -> 模块配置。App 进程写不了 /data/local/tmp，所以由有 root 的一方搬运。

    渲染只发生在 Kotlin 那一侧（ModuleBridge.render），这里只做"取出已渲染好的文本 + 落盘"，
    避免 PC / root 模块 / App 三处各写一遍格式然后互相漂移。
    """
    code, xml, err = t.sh(f"cat {APP_PREFS}", su=True)
    if code != 0 or not xml.strip():
        detail = (err or xml or "").strip()[:160]
        raise DeviceError(
            f"读不到 {APP_PREFS}：{detail}\n"
            "  需要 KernelSU 给 Shell 授权，或在 App 里先注入一次。")
    body = pref_value(xml, "module_conf")
    if not body:
        raise DeviceError("App 的 prefs 里没有 module_conf——先在 App 里点一次注入。")
    written = []
    for path in (CONFIG_PUB, CONFIG_INBOX):
        c, out, e = t.sh(
            f"printf '%s\n' {shlex.quote(body)} > {path}; chmod 664 {path}", su=True)
        if c != 0:
            raise DeviceError(f"写 {path} 失败: {(out or e)[:160]}")
        written.append(path)
    print(f"[ok] 已把 App 选点同步到模块配置：{'、'.join(written)}")
    print(body)
    return body


def pref_value(xml, name):
    """Pull one string preference out of SharedPreferences XML and undo its escaping.

    Android's XmlUtils writes control characters as numeric references - a newline in a stored
    string comes back as `&#10;` - so decoding only the named entities produced one long line that
    the module's parser then read as `ENABLED=true&#10;GLOBAL=...`, i.e. spoofing off. Numeric
    references are decoded first so an original literal "&#10;" (stored as `&amp;#10;`) survives.
    """
    match = re.search(rf'name="{name}"[^>]*>(.*?)</string>', xml, re.S)
    if not match:
        return ""
    text = match.group(1)
    text = re.sub(r"&#x([0-9a-fA-F]+);", lambda m: chr(int(m.group(1), 16)), text)
    text = re.sub(r"&#([0-9]+);", lambda m: chr(int(m.group(1))), text)
    for entity, char in (("&lt;", "<"), ("&gt;", ">"), ("&quot;", '"'), ("&apos;", "'"), ("&amp;", "&")):
        text = text.replace(entity, char)
    return text


def cmd_scope(t, args):
    """LSPosed 实际给了哪些作用域——由模块自己在每个进程里报的，不是 App 里的第二份名单。

    读 logcat 而不是读 LSPosed 的配置库：配置库说"勾了谁"，而这一行说"代码真的进了谁的进程"，
    后者才是排查时想要的那个事实。
    """
    code, out, err = t.sh('logcat -d | grep -a "attached to" | tail -60')
    lines = (out or err).splitlines()
    attached = {}
    for line in lines:
        proc = re.search(r"\(([^)]+)\)\[com\.mockloc", line)
        pkg = re.search(r"attached to (\S+)", line)
        if proc and pkg:
            attached.setdefault(pkg.group(1), set()).add(proc.group(1))
    print("模块挂载证据（logcat 全缓冲区）")
    if not attached:
        print("  一条都没有：要么模块没启用/没勾作用域，要么那些 App 还没被重启过。")
    for pkg in sorted(attached):
        print(f"  {pkg}  进程 {len(attached[pkg])} 个: {', '.join(sorted(attached[pkg]))}")

    code, out, err = t.sh(
        'logcat -d | grep -aE "\\[(Amap|Tencent|MockTrace|Sensors|Gnss)\\]|notes=" | tail -40')
    report = (out or err).splitlines()
    if report:
        print("\n挂钩小结（同一份 logcat，去掉进程前缀）")
        for line in report:
            body = re.sub(r"^.*?,\d,\d\] ", "", line, count=1)
            print(f"  {body[:160]}")
    return attached


def cmd_status(t, args):
    code, out, err = t.sh("cmd location is-location-enabled")
    print(f"master switch : {out or err}")
    code, out, err = t.sh("appops get 2000 android:mock_location", su=False)
    print(f"appop(2000)   : {out or err}")
    code, out, err = t.sh("appops get 0 android:mock_location")
    print(f"appop(0/root) : {out or err}")
    code, out, err = t.sh("settings get secure mock_location")
    print(f"secure flag   : {out or err}")
    pattern = "|".join(provider_list(args))
    code, out, err = t.sh(f"dumpsys location | grep -iE 'mock|({pattern}) provider' | head -14")
    print("dumpsys       :")
    print("\n".join(f"  {ln}" for ln in (out or err).splitlines()))
    code, out, err = t.sh(f"cat {CONFIG_INBOX} 2>/dev/null || cat {CONFIG_REMOTE} 2>/dev/null")
    print("生效配置（收件箱优先，其次 /data/adb）:")
    print(out or "  (两处都没有，只有 --persist 才会写)")


TIERS = [
    ("0", "普通 App（无 root、无 Shizuku）",
     "App 自己调 addTestProvider，app-op 由开发者选项「选择虚拟定位应用」写；全局一个点、isMock=true、"
     "重装会清掉授权。本机实测：微信/高德/美团都不吃（美团首页仍显示连州、卡片距离都是真的）",
     "--mode app set --place 东方明珠 --first"),
    ("1", "Shizuku / Rish（shell uid，不用 root shell）",
     "与档 0 是同一个数据面（系统里多一个 mock provider），差别只在不用开发者选项、可脚本化、provider "
     "identity 是 shell 而非某个包名。本机已实测：adb shell（uid 2000、完全不用 su）就能建 provider。"
     "结构上不会改变任何 App 的判定：档 0 推不动的，这一档也推不动",
     "--mode adb set --place 东方明珠 --first"),
    ("2", "root，不装 Xposed",
     "系统 provider（identity 0/android[LocationService]）+ 开机自恢复 + **只有这一档能按 uid 断网**。"
     "本机实测：root provider + 掐掉高德网络（模块完全关掉）⇒ 高德蓝点落在注入点，重定位也不回真。"
     "isMock=true 依旧擦不掉",
     "set --mode su --place 东方明珠 --first --persist"),
    ("3", "root + LSPosed/libxposed 模块",
     "进目标进程改读数：按 App 分流、没有测试 provider 所以 isMock=false。本机实测：微信只有这一档"
     "（它读内嵌腾讯 SDK 并看自家 isMock）；高德反而不靠这档——它的坐标在 native 侧生成与消费，"
     "Java 钩子够不着，真正有效的是②输入面（这一档提供精细版致盲）",
     "set --mode su --no-global --place 东方明珠 --first --persist --blind-wifi com.autonavi.minimap"),
]

# Measured on this device (OnePlus Ace 3V / Android 16). What makes a given app move is a lever,
# not a rung: 0/1/2 all deliver the same mock provider, so "which tier" is the wrong question.
NEEDS = [
    ("微信 com.tencent.mm", "③进进程", "只吃进程内改写：它读内嵌腾讯 SDK 并看 mock 标志。已验通过"),
    ("高德地图 com.autonavi.minimap", "②抽空输入", "自家引擎算路、native 消费；致盲或按 uid 断网即落点，③改不动"),
    ("美团 com.sankuai.meituan", "未判定", "档 0 实测无效；它不在模块作用域，②/③还没测"),
    ("抖音 aweme.lite", "未测", "注意评论里的「IP 属地」是服务端按出口 IP 算的，与定位无关"),
]


def probe(t, cmd, su):
    try:
        code, out, err = t.sh(cmd, su=su)
        return code == 0, (out or err).splitlines()[0][:70] if (out or err) else ""
    except DeviceError as exc:
        return False, str(exc)[:70]


def cmd_tiers(t, args):
    print(f"设备 {t.serial} :: {t.info()}\n")
    for num, name, effect, cmd in TIERS:
        print(f"档 {num}  {name}")
        print(f"   效果 : {effect}")
        print(f"   命令 : python tools/mockloc.py {cmd}")
    print("\n目标 App 要的是哪一层（本机实测，①=系统provider ②=抽空输入 ③=进进程）：")
    for app, lever, note in NEEDS:
        print(f"  {app:32} {lever:8} {note}")
    print("\n判据：三档都装同一个 mockloc.apk，跑完档打开页面看两行——")
    print("  LKL gps : ... mock=False/True   是 App 实际读到的痕迹")
    print("  注入 : 已生效/未生效            只有档 3 会是已生效")


def cmd_doctor(t, args):
    if isinstance(t, DryRunTarget):
        print("[dry-run] 下面所有命令只打印不执行，可用性结论一律按通过处理——真机请去掉 --dry-run\n")
    print("逐项自检（每项都是一次真调用，不是读配置）")
    probe_provider = provider_list(args)[0]
    checks = [
        ("adb 在线", lambda: t.serial),
        ("系统版本", t.info),
        ("su 可用", lambda: t.sh("id", su=True)[1]),
        ("location 服务", lambda: t.sh("cmd location is-location-enabled")[1]),
        ("appop 可授", lambda: ensure_appop(t)),
        ("测试 provider 可建", lambda: ensure_provider(t, probe_provider)),
        ("注入可用", lambda: inject(t, probe_provider, 31.230416, 121.473701, 5.0)),
    ]
    tier_ok = {}
    for name, fn in checks:
        try:
            print(f"  PASS  {name}: {fn()}")
            if name == "su 可用":
                tier_ok["su"] = True
            if name in ("appop 可授", "测试 provider 可建", "注入可用"):
                tier_ok["provider"] = True
        except Exception as exc:
            print(f"  FAIL  {name}: {exc}")
            tier_ok.setdefault("su", False)
            tier_ok.setdefault("provider", False)
            break

    print("\n三档可用性")
    # Tier 1 is the shell uid, so probe it without su. Asking for the grant in the same command is
    # what made this read "不可用" on ROMs that strip MANAGE_APP_OPS_MODES from shell even though the
    # op is already allow and the provider builds fine - two different facts, two probes.
    ok_grant, grant_detail = probe(t, f"appops set 2000 android:mock_location allow", su=False)
    ok1, detail1 = probe(t, f"cmd location providers add-test-provider {probe_provider}", su=False)
    if ok1:
        t.sh(f"cmd location providers remove-test-provider {probe_provider}", su=False)
    print(f"  档0/1 免root    : {'可用（shell 能建 provider）' if ok1 else '不可用'} {'' if ok1 else detail1}")
    print(f"      授权途径    : {'shell 可自授 app-op' if ok_grant else f'需开发者选项或 root（{grant_detail[:60]}）'}")
    print(f"  档2 root        : {'可用' if tier_ok.get('su') and tier_ok.get('provider') else '不可用'}")
    ok_apk, d_apk = probe(t, f"pm path {SELF_PKG}", su=False)
    ok_lspd, _ = probe(t, "ls /data/adb/lspd", su=True)
    print(f"  档3 LSPosed+模块: {'可用' if ok_apk and ok_lspd else '需手工确认'}"
          f"{'  (模块 APK 未装)' if not ok_apk else ''}{'  (没找到 /data/adb/lspd)' if not ok_lspd else ''}")
    if d_apk:
        print(f"      模块 APK : {d_apk}")

    # Probing creates a provider; leaving it behind would silently spoof the whole phone.
    t.sh(f"cmd location providers set-test-provider-enabled {probe_provider} false")
    t.sh(f"cmd location providers remove-test-provider {probe_provider}")
    print("\n已清理探针建的测试 provider（真实 GPS 恢复）。")
    print("注意：档1/档2 给到 App 的定位 isMock()=true；要抹掉痕迹必须走档3。")


MENU = [
    ("1", "搜索地点并注入", "set --place"),
    ("2", "输入经纬度注入", "set --coord"),
    ("3", "持续刷新（防时间戳过期）", "watch"),
    ("4", "查看当前状态", "status"),
    ("5", "逐项自检 + 三档可用性", "doctor"),
    ("8", "把 App 选点同步给模块", "sync"),
    ("9", "模块真正挂进了哪些进程", "scope"),
    ("6", "关闭并恢复真实 GPS", "off"),
    ("7", "对照三档方案与命令", "tiers"),
    ("0", "退出", ""),
]


def interactive():
    print("mockloc - 安卓虚拟定位（阶段1：系统测试 provider）")
    t = Target(mode=input("权限模式 su/adb/app [su]: ").strip() or "su")
    print(f"设备 {t.serial} :: {t.info()}")

    def mk(**kw):
        ns = build_args(**kw)
        ns.mode = t.mode
        return ns

    while True:
        print()
        for k, label, _ in MENU:
            print(f"  {k}. {label}")
        choice = input("> ").strip()
        try:
            if choice == "1":
                place = input("地点名: ").strip()
                city = input("城市(可空): ").strip()
                cmd_set(t, ask_scope(mk(place=place, city=city)))
            elif choice == "2":
                coord = input("经纬度 (lat,lng 或 lng,lat): ").strip()
                datum = input("坐标系 wgs84/gcj02/bd09 [gcj02]: ").strip() or "gcj02"
                cmd_set(t, ask_scope(mk(coord=coord, datum=datum)))
            elif choice == "3":
                cmd_watch(t, ask_scope(mk(watch=True)))
            elif choice == "4":
                cmd_status(t, mk())
            elif choice == "5":
                cmd_doctor(t, mk())
            elif choice == "6":
                cmd_off(t, mk())
            elif choice == "7":
                cmd_tiers(t, mk())
            elif choice == "8":
                cmd_sync(t, mk())
            elif choice == "9":
                cmd_scope(t, mk())
            elif choice == "0":
                return
        except DeviceError as exc:
            print(f"[error] {exc}")


def build_args(place=None, city=None, coord=None, datum="gcj02", watch=False):
    return argparse.Namespace(
        place=place, city=city, coord=coord, datum=datum, key=None, provider=None,
        providers=PROVIDERS_DEFAULT,
        accuracy=5.0, persist=True, dry_run=False, radius=(20.0 if watch else 0.0),
        interval=15.0, first=False, target=[], global_provider=True, wipe=False,
        gnss_spoof=True, blind_wifi="false", blind_cell="false", probe=False,
    )


def ask_scope(ns):
    """Menu path only: decide the two layers without remembering that GLOBAL and TARGETS are separate."""
    ns.global_provider = (input("全局虚拟定位(建系统测试 provider)? y/n [y]: ").strip() or "y").lower() != "n"
    raw = input("只对这些包名生效(逗号分隔, 空=作用域内全部 App): ").strip()
    ns.target = [p.strip() for p in raw.split(",") if p.strip()] if raw else []
    if not ns.global_provider and not ns.target:
        print("[提示] 既不建全局 provider 又没填包名，模块会按 LSPosed 作用域里的全部 App 生效")
    if ns.target:
        # Only worth asking when the module is the layer doing the work. The blinding hooks are
        # installed once per process, so changing this needs the target app to be restarted.
        blind = input("致盲哪些 App 的 WiFi/基站输入(逗号包名, all=全部, 空=不致盲): ").strip()
        if blind:
            ns.blind_wifi = ns.blind_cell = "true" if blind.lower() == "all" else blind
        if (input("开 API 探针(只记录不改值，结果进 logcat)? y/n [n]: ").strip() or "n").lower() == "y":
            ns.probe = True
    return ns


def main():
    parser = argparse.ArgumentParser(description="Android mock-location CLI (phase 1)")
    parser.add_argument("--serial")
    parser.add_argument("--mode", choices=["su", "adb", "app"], default="su")
    sub = parser.add_subparsers(dest="cmd")

    common = argparse.ArgumentParser(add_help=False)
    common.add_argument("--provider", default=None, help="只操作这一个 provider（实验用）")
    common.add_argument("--providers", default=PROVIDERS_DEFAULT,
                        help="逗号分隔，默认同时顶掉 gps/network/fused")
    common.add_argument("--accuracy", type=float, default=5.0)
    common.add_argument("--alt", type=float, default=-1.0,
                        help="fix altitude in metres; -1 = keep whatever the publisher resolved")
    common.add_argument("--dry-run", action="store_true", help="print the device commands, send nothing")
    common.add_argument("--global", dest="global_provider", action=argparse.BooleanOptionalAction,
                        default=True, help="--no-global: only the Xposed module path, no system provider")
    common.add_argument("--gnss", dest="gnss_spoof", action=argparse.BooleanOptionalAction,
                        default=True, help="伪造卫星状态（假 gps 却 0 颗星正是一致性检测点）")
    common.add_argument("--blind-wifi", nargs="?", const="true", default="false", metavar="SPEC",
                        help="抹掉 getScanResults/WifiInfo：true=作用域内全部、false=关、或逗号包名列表"
                             "（会破坏目标 App 自己的 WiFi 功能）")
    common.add_argument("--blind-cell", nargs="?", const="true", default="false", metavar="SPEC",
                        help="抹掉基站信息，取值同上")
    common.add_argument("--probe", action=argparse.BooleanOptionalAction, default=False,
                        help="只记录不改值：目标 App 调了哪些定位 API、从它自己哪个类调的（进 logcat）")

    p_set = sub.add_parser("set", parents=[common], help="resolve + inject once")
    p_set.add_argument("--place")
    p_set.add_argument("--city", default="")
    p_set.add_argument("--coord", help="lat,lng (also accepts lng,lat)")
    p_set.add_argument("--datum", choices=["wgs84", "gcj02", "bd09"], default="gcj02")
    p_set.add_argument("--key", default=None, help="Amap Web服务 key")
    p_set.add_argument("--first", action="store_true", help="take geocode hit #1 (for scripts)")
    p_set.add_argument("--radius", type=float, default=0.0, help="jitter radius persisted for the module")
    p_set.add_argument("--interval", type=float, default=15.0, help="root-module refresh interval, seconds")
    p_set.add_argument("--netcut", action="append", default=[], metavar="PKG",
                       help="tier-2 per-uid network cut (the lever that moves Amap, A15)")
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

    p_off = sub.add_parser("off", parents=[common], help="remove the test provider and switch both layers off")
    p_off.add_argument("--wipe", action="store_true", help="also delete the persisted config")

    sub.add_parser("sync", parents=[common], help="把 App 里的选点/名单同步成模块配置（需 Shell root）")
    sub.add_parser("scope", parents=[common], help="读模块自己报的挂载名单：LSPosed 实际给了谁作用域")
    sub.add_parser("status", parents=[common])
    sub.add_parser("tiers", parents=[common], help="print the three privilege tiers and their commands")
    p_doc = sub.add_parser("doctor", parents=[common])

    args = parser.parse_args()
    if not args.cmd:
        interactive()
        return

    ns = args
    for name, value in (("radius", 0.0), ("interval", 15.0), ("persist", False),
                        ("dry_run", False), ("mode", "su"), ("serial", None), ("target", []),
                        ("global_provider", True), ("wipe", False), ("providers", PROVIDERS_DEFAULT)):
        if not hasattr(ns, name):
            setattr(ns, name, value)
    t = DryRunTarget(ns.mode) if ns.dry_run else Target(ns.serial, ns.mode)
    try:
        if args.cmd == "set":
            cmd_set(t, ns)
        elif args.cmd == "watch":
            cmd_watch(t, ns)
        elif args.cmd == "off":
            cmd_off(t, ns)
        elif args.cmd == "sync":
            cmd_sync(t, args)
        elif args.cmd == "scope":
            cmd_scope(t, args)
        elif args.cmd == "status":
            cmd_status(t, ns)
        elif args.cmd == "tiers":
            cmd_tiers(t, ns)
        elif args.cmd == "doctor":
            cmd_doctor(t, ns)
    except DeviceError as exc:
        print(f"[error] {exc}", file=sys.stderr)
        sys.exit(2)
    except KeyboardInterrupt:
        sys.exit(130)


if __name__ == "__main__":
    main()
