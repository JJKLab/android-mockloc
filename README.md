# mockloc — 安卓系统级虚拟定位（root / KernelSU）

给已 root（KernelSU / APatch / Magisk）的安卓机做虚拟定位。**档 1（无 root）已真机跑通**，
并且抓到了"只顶 gps 不顶 network 会泄露真实位置"这个致命缺陷并已修（见 A0）。
**阶段 3 的 App（搜索 / 地图点选 / 收藏）已实现并本地编译通过**（18 个 class 零告警），待 CI 出包与真机走查。
阶段 2 的 libxposed 模块（按 App 生效 + 抹掉 `isMock` 痕迹）代码就绪，需装 LSPosed 后才能验。

它和"开发者选项里选个虚拟位置 App"的区别：开发者选项只是把 app-op `android:mock_location` 设为 allow，
底层走的还是同一套测试 provider，所有 App 都能通过 `Location.isMock()` 看出来。这里阶段 1 用同一套系统能力但
**不需要装第三方定位 App、不需要点开发者选项、开机自动恢复**；阶段 2 直接在被 hook 的 App 进程里伪造
`Location` 对象，连测试 provider 都不建，`isMock()` 天生 false。

---

## 1. 准备

| 需要什么 | 说明 |
|---|---|
| adb | 本机已有（`D:\platform-tools\adb.exe`，37.0.1）。脚本用 `ADB` 环境变量覆盖路径 |
| 手机 root | KernelSU / APatch / Magisk 任一；`su` 可用即可 |
| 高德 key | 开放平台「**Web服务**」类型的 key（不是 Android SDK 类型）。个人需实名认证。地理编码免费 5000 次/天 |
| LSPosed | 只有阶段 2 需要 |

key 放这儿（三选一，优先级从高到低）：`--key`、环境变量 `AMAP_KEY`、`tools/config.json` 里写
`{"amap_key":"..."}`（该文件已 gitignore，不会被提交）。

**这三处只服务于 PC 上的 CLI**。APK 侧的 key 输入框是阶段 3 的活，且有一条不能破的不变量：
**key 永远不进 `/data/local/tmp/mockloc.conf`**。那个镜像文件被 root 模块 `chmod 664 + chcon shelldatafile`
是为了让 App 进程读得到，代价是机上**任何** App 都能读它——key 写进去等于把配额送给所有装机应用，
而且控制台看不出是谁刷的。手机侧只允许两个位置：App 私有 prefs，或 root-only 的 `/data/adb/`。
代码层面 `write_config()` 只接受显式字段白名单，结构上就没有写 key 的口子。
阶段 3 落地后，唯一日常填写处改为 App 设置页，CLI 优先 `su -c cat` 那个私有 prefs，读不到才退回上面三个入口
（留兜底是因为脚本/cron 必须非交互）。

## 2. 用法

不带参数就是问答式菜单，不用记命令：

```
python tools/mockloc.py
```

脚本化 / 定时任务用参数形式：

```powershell
# 搜地名 -> 反算 WGS-84 -> 注入 -> 写开机配置（全局：所有 App 同一个假位置）
python tools/mockloc.py set --place 东方明珠 --city 上海 --persist

# 只让指定 App 拿到假位置，其余 App 保持真实（不建系统 provider，isMock 天生 false）
python tools/mockloc.py set --place 东方明珠 --no-global --target com.example.app --persist

# 已有坐标（GCJ-02，即国内地图网页上抄出来的那组）
python tools/mockloc.py --mode adb set --coord "31.239703,121.499733" --datum gcj02

# 持续刷新：每 15s 重注入一次并带 25m 抖动（按 App 模式不需要它，模块自己每秒重抖）
python tools/mockloc.py watch --place 东方明珠 --radius 25 --interval 15

# 看设备现在报告了什么 / 逐项自检 / 关掉（两层一起关，坐标留着）
python tools/mockloc.py status
python tools/mockloc.py doctor
python tools/mockloc.py off
```

### 2.5 三种生效层级（这是"能不能过检测"的分水岭）

| 层级 | 靠什么 | 需要的权限 | 按 App 分流 | `isMock()` | 本项目 |
|---|---|---|---|---|---|
| 全局·App 内 provider | App 自己调 `addTestProvider`，app-op 由开发者选项授 | 无需 root，装 APK + 一次点选 | ❌ 全体 App 同一个位置 | **true**，自检可见 | ✅ `--mode app` |
| 全局·shell 直注 | `cmd location providers ...` | adb **且** ROM 把 `MANAGE_APP_OPS_MODES` 留给 shell（本机实测不通，见 A0） | ❌ | **true** | ⚠️ `--mode adb`，仅近 AOSP ROM |
| 按 App·进程内 hook | libxposed 改 `Location` 读数 | **root + LSPosed** | ✅ 只有列进 `TARGETS` 的 App | **false**（压根没有测试 provider） | ✅ `--no-global --target` |
| 全局·无痕 | 在 system_server 里就地改写 `LocationResult` | root + LSPosed，且要作用域加 `system` | ❌ 全体 | false | ⚠️ 未实现，见下 |

第三级没写的原因不是能力不够，而是风险不对等：它要改 `LocationProviderManager.onReportLocation`
并按调用方 uid 分流，写错的后果是 **system_server 软重启**（XposedFakeLocation 的 issue #50/#49 就是这类）。
在还没有真机可测的现在，我不会把它盲编盲推。参考实现的位置已记在附录 A3。


`--mode adb` 只用 shell 权限（uid 2000）就够了，**不需要 root**，适合临时验证；缺点是重启失效、且
`adb shell` 一拔线就断。`--mode su`（默认）走 root，配合 root 模块可以开机自恢复。

### 2.6 三档分别怎么测（建议顺序）

每档跑完都打开 mockloc 页面读那两行判据，再拿一个你真实关心的 App 复现一次。

| 档 | 前置 | 跑什么 | 页面预期 |
|---|---|---|---|
| 1 无 root | 装 APK → 开发者选项「选择虚拟定位应用」选 mockloc → 授定位权限 | `--mode app set --place 东方明珠 --first` | `LKL gps : ... mock=True`，`注入 : 未生效` |
| 2 root | 刷入 `mockloc-root.zip` | 档 2 那行（加 `--persist`） | `LKL gps : ... mock=True`，`注入 : 未生效`；**重启后再看一次应仍是假位置** |
| 3 root+LSPosed | 装 APK + 在 LSPosed 里启用模块并把目标 App 勾进作用域 | 档 3 那行（`--no-global --target`） | `注入 : 已生效`；目标 App 里 `mock=False`，未勾选的 App 拿到真实位置 |

先跑 `python tools/mockloc.py doctor`：它会把三档在这台机器上的可用性逐个实测出来
（su 能不能用、shell uid 能否建 provider、模块 APK 与 `/data/adb/lspd` 是否存在），
并且探针建的 provider 用完会自动删掉，不会把手机留在假位置状态。

档 1 还有个免 PC 的变体：用 Shizuku（或 Stellar 这类 fork）以 shell 身份执行同样的
`cmd location` 与 `appops` 命令——依据见 A5。

## 3. 编译（本机无安卓环境，全部交给 GitHub Actions）

**一次性设置（不做也能编，但每次签名都变）**：Actions 默认让 AGP 在 runner 上现生成 debug keystore，
于是每个构建签名不同 → 装新包必须先 `adb uninstall com.mockloc`，而**卸载会清掉开发者选项给的
mock_location app-op**，又得重点一次。破解办法是把仓库 secret `DEBUG_KEYSTORE_BASE64` 设成本地
`.local-debug.keystore.b64` 的内容（该文件不入版本库，自己 `keytool -genkeypair` 生成）：
仓库 → Settings → Secrets and variables → Actions → New repository secret。
没设时 CI 只发 `::warning::`，不会中断构建。`versionName` 会带上 commit 短 SHA，
`adb shell dumpsys package com.mockloc | grep versionName` 就能确认手机上装的是哪一版。

`.github/workflows/build.yml` 三个 job：

1. `logic` — 跑 `tools/tests/test_coords.py`，坐标层不对就直接卡住后面两个 job。
2. `apk` — JDK 21 + Gradle 8.9（**仓库里没有 gradle-wrapper.jar**，由 `gradle/actions/setup-gradle` 装指定版本）
   + AGP 8.7.0 + Kotlin 2.2.10 + compileSdk 36，产物 `mockloc-debug.apk`。
   runner 镜像自带 Android SDK（platforms 34–37、license 已接受），所以没有装 SDK 的步骤。
3. `root-module` — `sh -n` 语法检查每个脚本，再打进 `mockloc-root.zip`（`update-binary` 从 Magisk 仓库现取）。

版本组合是从 [XposedFakeLocation](https://github.com/noobexon1/XposedFakeLocation) 这套**正在出正式版**的工程里抄的，
不是猜的。libxposed 用 **`io.github.libxposed:api:102.0.0`**（Maven 元数据里 `<release>102.0.0</release>`，
aar 拉下来解出 `classes.jar` 后，本仓库 4 个 Kotlin 文件在它上面重新类型检查过：**零错误零告警**，
说明我的代码没有用到 101 与 102 之间有任何差异的 API）。协程/Compose 一律没用，运行时依赖为零。

## 4. 装到手机

```powershell
adb install mockloc-debug.apk                 # 模块 + App（四页：搜索/地图/收藏/状态）
adb push mockloc-root.zip /sdcard/            # root 模块，用 KernelSU/APatch 刷入
```

### App 里怎么用（阶段 3）

四个页签，对应你要的三种输入 + 一个判据：

| 页 | 干什么 | 坐标处理 |
|---|---|---|
| 搜索 | 填一次高德 Web服务 key（存本机私有 prefs）→ 输地点 → 点结果即注入 | 高德给 GCJ-02 → 自动反算 WGS-84 |
| 地图 | osmdroid + 高德瓦片，点任意位置 → 注入 / 收藏 | 瓦片是 GCJ-02 地理参考 → 点选值同样反算；屏上两行都显示，注入用 WGS 那行 |
| 收藏 | 点=注入，长按=删除 | 存的就是 WGS-84，不再转换 |
| 状态 | `selfCheck`、各 provider 回读、停止注入 | 这是唯一能看出"注入到底生效没"的地方 |

注入走的是**档 1 的应用内 provider**（`MockService` 前台服务），所以不装 Xposed、不 root 也能用；
装了 LSPosed 并勾作用域后，档 3 的按 App 无痕路径会叠加在上面。

### 为什么 UI 是 View 而不是 Compose

不是口味问题，是**可验证性**：Compose 代码需要 Compose 编译器插件，本项目的本地检查手段
（`kotlinc` + android.jar）编不了它，等于把整个 UI 层交给盲编。用 View 之后，`MainActivity` 连同
osmdroid 的全部调用都能在本机真编译 —— 这一轮就是这样抓到 tab 按钮被加进竖向容器、
`MapEventsOverlay` 用了废弃构造、`Marker.position` 属性化赋值不成立等若干处。

osmdroid 的真实 API 也不是靠记忆写的：`tools/classdump.py` 直接解析 jar 里的 `.class`
（本机只有 JRE、没有 `javap`），把 `XYTileSource`/`MapEventsOverlay`/`Projection.fromPixels`/
`Marker`/`MapController` 的构造与方法签名读出来再照着写。顺带确认 osmdroid 6.1.20 **零传递依赖**、
AAR 元数据 `minCompileSdk=1`，加它不给 CI 添风险。

---

## 附录 A 侦察记录

### A0 实机核验（OnePlus PJF110 / Android 16 / API 36 / KernelSU，2026-10-24）

这台机器把两条纸面结论改写了，全部有正/负对照：

| 探针 | 结果 |
|---|---|
| `id` | `uid=2000(shell) … context=u:r:shell:s0`（正常 shell 域） |
| `settings put secure ml_probe 1` | ✅ **正向对照通过**（随后 delete） |
| `dumpsys deviceidle whitelist +com.mockloc` | ✅ `Added:` 对照通过 |
| `appops set 2000 android:mock_location allow` | ❌ `SecurityException: uid 2000 does not have android.permission.MANAGE_APP_OPS_MODES` |
| `pm grant com.mockloc …` | ❌ `Neither user 2000 nor current process has GRANT_RUNTIME_PERMISSIONS` |
| `cmd location providers add-test-provider gps` | ❌ `android from uid 2000 not allowed to perform MOCK_LOCATION` |
| `su`（已在 KernelSU 里给 Shell 授权之后） | ❌ `su: inaccessible or not found`；`/data/adb/ksu/bin/su` 报 **Permission denied**（文件在，但没挂进 adb shell 的 namespace） |

两个正向对照是关键：shell 并非整体无特权，而是**这款 ColorOS 系固件（dumpsys 里可见
`com.coloros.findmyphone`）单独摘掉了 `MANAGE_APP_OPS_MODES` 与 `GRANT_RUNTIME_PERMISSIONS`**。
AOSP 原版 `Shell/AndroidManifest.xml` 是持有它们的，所以"adb 免 root 注入"只在 AOSP/近 AOSP ROM 成立。

同时那次失败把 A1 的机制**在运行时钉死了**，调用栈与源码逐帧对应：
`AppOpsManager.noteOp → SystemAppOpsHelper.noteOp → LocationManagerService.addTestProvider:1797`。
（我先前引的 1511–1577 是 AOSP 上游行号，OEM 构建里同一逻辑在 :1797 —— **行号不能当版本判据**。）

`adb shell su` 不可用这条决定了架构：**配置下发必须走 shell 可写通道**，于是 root 模块改为读
`/data/local/tmp/mockloc.in` 收件箱（实测 shell 可写），再由 root 侧 `sync_inbox` 镜像进 `/data/adb`。
副作用是不装 Xposed 也能只刷模块跑档 2，不需要 PC 端拿到 root shell。

**档 1 实机跑通，并抓到一个会致命的泄露。** 用旧版单 provider 注入东方明珠后，App 诊断页读到：

```
自身 appop : allow          secure mock_location : 0     ← 实锤 A1：选择器只写 app-op，不写 Settings.Secure
LKL gps     : 31.241703,121.495284 acc=5.0  mock=true    ← 注入值，与验收过的反算一致
LKL passive : 31.241703,121.495284 acc=5.0  mock=true    ← 框架把假位置传播进 passive
LKL network : 24.783651,112.358044 acc=35.0 mock=false   ← 真实位置原封不动泄露
```

只顶 `gps` 是不够的：**`network` provider 还在报真位置**（广东一带）。所以默认改为同时顶
`gps,network,fused`（`passive` 由框架自动继承）。同名替换对 `network` 是否成立也实测了：注入深圳后
`dumpsys` 出现 `network provider [mock]` 且 `passive` 同步跟进 —— AOSP 那句 "will replace any provider
with the same name" 对非 gps 名字同样有效。

收尾也验了：`cmd stop` + `am force-stop` 之后 `dumpsys | grep -c '\[mock\]'` 归零，诊断页回到
`network 24.783499,112.358101 mock=false`（与注入前差约 17 m，说明是活的网络定位在刷新，不是缓存）。
**test provider 随注册进程死亡而被系统回收**，这条是"关掉就恢复真实 GPS"的机制保证。

**框架层 mock 的边界（实测，不是推测）。** 注入生效期间 `dumpsys location` 里 `gps/network/fused`
三个 provider 全是 `[mock]`，我们 App 自己的状态页读回三行一致坐标；**但高德地图首页仍显示「清远市」**
（真实位置，与之前 network 泄露那组 `24.78,112.36` 吻合），而 `dumpsys location` 明确记录
`com.autonavi.minimap` 与 `com.tencent.mm` 都注册了 gps 客户端——它们拿到了喂入却不用。
这类 App 自带定位引擎（WiFi 列表 → 自家服务器算坐标），要么不吃 `LocationManager`，要么因
`mock=true` 主动丢弃后回落到网络定位。**这就是本项目开头那句"开发者选项的虚拟定位很多软件不认可"的机制。**

推论（推翻我第一轮给的选项）：只做"框架层 hook"不足以让高德/微信类 App 改位置，必须再加
**地图 SDK hook 层**（`com.amap.api.location.AMapLocation` 等，参考 A3 里 FakeLoc 的 `MapSdkHooks.kt`），
而这一层依赖 LSPosed——本机目前未装（`pm list packages | grep -c lsposed` = 0）。

### A1. 系统到底怎么放行虚拟定位（AOSP android16-release，逐条核过）

- **闸门是 app-op，不是权限**。`LocationManagerService.java:1511-1577` 只做
  `noteOp(AppOpsManager.OP_MOCK_LOCATION, identity)`；`ACCESS_MOCK_LOCATION` 是 `signature` 级、LMS 全程不读，
  开发者选项的定位 App 选择器只用它过滤候选列表。app-op 默认 `MODE_ERRORED`。
  补一条更硬的证据：API 36 的 `LocationManager.java`（3742 行）里 **`ACCESS_MOCK_LOCATION` 出现 0 次**，
  `addTestProvider` 三个重载的 javadoc 只写 `SecurityException if OPSTR_MOCK_LOCATION ... not set to MODE_ALLOWED`。
  也就是说：**只要 app-op 到位，任何 App 都能建测试 provider，平台签名权限根本不看**。
- **同名替换是官方语义**，javadoc 原文 "This provider will replace any provider with the same name
  that exists prior to this call"，走 `MockableLocationProvider.setMockProvider()`。
- **Android 16 的"选择虚拟定位应用"只写 app-op**：`MockLocationAppPreferenceController` 把选中的包设成
  `MODE_ALLOWED`、其余设回 `MODE_ERRORED`，**不写任何 Settings.Secure**。
- **root/shell 能静默授权**：`cmd appops set` 需要的 `MANAGE_APP_OPS_MODES` shell 包自带；root(0)/shell(2000)
  属 `NON_PACKAGE_UIDS`，可以按 uid 授权。
- **测试 provider 可以顶掉同名真实 provider**（`gps`/`fused`/`network`/`passive`），走
  `MockableLocationProvider.setMockProvider()`。官方文档承认这条路径。

`cmd location` 在 API 36 的**准确**语法（读 `LocationShellCommand.java` 原文，不是二手转述）：

```
cmd location is-location-enabled [--user <ID>]
cmd location set-location-enabled true|false [--user <ID>]
cmd location providers add-test-provider <PROVIDER>
      [--requiresNetwork] [--requiresSatellite] [--requiresCell] [--hasMonetaryCost]
      [--supportsAltitude] [--supportsSpeed] [--supportsBearing]
      [--powerRequirement <N>] [--accuracy <N>] [--extraAttributionTags <T,T>]
cmd location providers remove-test-provider <PROVIDER>
cmd location providers set-test-provider-enabled <PROVIDER> true|false
cmd location providers set-test-provider-location <PROVIDER>
      --location <LATITUDE>,<LONGITUDE> [--accuracy <米>] [--time <毫秒>]
cmd location providers send-extra-command <PROVIDER> <COMMAND>
```

三个只有读源码才能发现的坑：

1. `--location` 是**纬度在前**，而高德返回的 `location` 字段是**经度在前**（`121.49,31.23`）。写反了不会报错，
   只会把人扔到索马里附近。代码里所有转换都收敛在 `coords.parse_latlng` / `amap._split_location` 两处。
2. `add-test-provider` 的 `--accuracy` 是**整型档位**（`Criteria.ACCURACY_FINE=1`），而
   `set-test-provider-location` 的 `--accuracy` 是**浮点米数**，同名不同义。
3. 省略 `--time` 时框架自动打当前时间戳；但存进去的 `Location.time` 就此固定，App 过几分钟再读就判定"过期"。
   所以要 `watch` 循环重注入。

### A2. 版本收紧传闻的核实结果（重要：多数是讹传）

翻了 Android 8/10/12/13/14/15/16 的 behavior-changes 页 + AOSP api txt：

| 传闻 | 实际 |
|---|---|
| Android 12 起 mock 更难 | 只是把 `Location.isMock()/setMock()` 公开化，`isFromMockProvider()` 转废弃。没有新限制 |
| Android 13/14/15/16 收紧虚拟定位 | **没有任何一处行为变更**。API diff 里 app-op 闸门和默认模式一模一样 |
| Android 14 需要 device owner / `DISALLOW_MOCK_LOCATION` | AOSP 11–16 的 `UserManager.java` 里**根本没有这个常量** |
| 有个 `LocationMark` 检测机制 | AOSP public+system api txt **零命中**，是中文博客互相抄出来的东西 |
| Play Integrity 会报 mock 位置 | verdict 字段里**没有任何 location/mock 信号**。老 SafetyNet 的 `locMocked` 也查不到可靠出处 |

真正存在的检测面只有：`Location.isMock()` 那一个 bit、provider 字符串、自建 App 查自己 app-op、
以及"GnssStatus/卫星数为 0 但位置在变"这类**一致性推断**（这条只能靠 system_server/HAL 层解决，阶段 2 不管）。

### A3. 开源同类项目（都 clone 到 `refs/` 读过代码）

| 项目 | 机制 | 状态 | 抄它什么 |
|---|---|---|---|
| [XposedFakeLocation](https://github.com/noobexon1/XposedFakeLocation) | libxposed 101；`onSystemServerStarting` 注 system_server；按包名分流 | 活跃，compileSdk 36，自带 CI | 整套工程与 CI 配置（**本仓库的基线**） |
| [FakeLoc](https://github.com/Cobylinweiqi/FakeLoc) | libxposed + system_server + SettingsProvider/AppOpsService 三处反检测 | 活跃 | `MockTrace.kt` 清 `mFieldsMask` 的 `HAS_MOCK_PROVIDER_MASK`、把 op 58 打成 error、改写 `mock_location` 游标 |
| [Portal](https://github.com/ella8192/Portal) | 再加 Dobby native inline hook + GNSS/NMEA/WiLAN | 上游停更 | Xposed 自身被检测时的兜底；MIUI/Oplus 适配 |
| [gps-locator](https://github.com/0xfnzero/gps-locator) | 纯 mock-app + Nominatim 免 key + GPX 航线 | 活跃 | 航线插值 |
| [OpenRealGPS/magisk-module](https://github.com/OpenRealGPS/magisk-module) | 替换 GNSS HAL（仅 HIDL 1.0–2.1） | 2020 | HAL 路线在 AIDL 化（Android 13+）后已无人走，仅供参考 |

收敛出来的三条手法：

1. **在 system_server 的 `LocationProviderManager.onReportLocation` 处就地替换 `LocationResult.mLocations`**
   （而不是改返回值）——下游 GMS fused、passive 全部继承，且不在目标进程插桩，能扛进程内自检。
2. **手搓 `new Location(provider)` 而不是改真对象**——新对象的 mock bit 天生 false。
3. **配置下发**：libxposed 路线用 LSPosed remote preferences；root 路线写
   `/data/local/tmp/*.conf` + `chcon shelldatafile`（ContentProvider IPC 在 Android 11+ 会被包可见性挡住）。

### A4. 坐标实测

- 中国地图 API 返回 GCJ-02（百度再多一层 BD-09），Android GPS 栈要 WGS-84，陆家嘴实测偏移 **478 m**。
- 高德/百度的官方转换接口**只能转进去不能转出来**（法规不允许输出 WGS-84），必须本地反算。
- 东方明珠：GCJ-02 `31.239703,121.499733` → WGS-84 `31.241702,121.495284`（迭代反算，与
  Photon/OSM 直给的 `31.241946,121.495260` 差约 20 m，属锚点差异）。
- 配额：高德地理编码个人实名 **5000/天**；POI 搜索只有 100/天，所以默认走地理编码。
  `10001 INVALID_USER_KEY` 已用真请求实测（假 key），确认真实返回串与错误解析一致。

## 附录 A5 Shizuku（含 Stellar 这类 fork）能做到哪一步

先澄清事实：[roro2239/Stellar](https://github.com/roro2239/Stellar) 不是定位工具，它是 **Shizuku 的一个 fork**
（"让应用通过 ADB 或 Root 权限使用系统级 API"）。所以问题实际是"只有 Shizuku 级权限时，虚拟定位能做到什么"。

结论：**能做到全局虚拟定位，做不到无痕，也做不到按 App**。推理链是闭合的，不靠经验猜测：

1. Shizuku 的远程进程以 **shell uid 2000**（ADB 启动）或 root（Root 启动）运行，并且 shell 包自带
   `MANAGE_APP_OPS_MODES` → 因此 `appops set 2000 android:mock_location allow` 成立。
2. 而 A1 已证明：测试 provider 唯一的闸门就是这个 app-op，`ACCESS_MOCK_LOCATION` 平台签名权限**根本不被检查**。
   于是 Shizuku 里直接调 `LocationManager.addTestProvider/setTestProviderLocation`，或转手执行
   `cmd location providers ...`，都成立 —— 不需要 root、不需要点开发者选项。
   本仓库 `--mode adb` 走的就是这条 uid 2000 路径，已经算是对该结论的等价验证。
3. 但 `MockLocationProvider.setProviderLocation()` 在 **system_server 里**给 Location 打上
   `setIsFromMockProvider(true)` 才下发，Shizuku 没有任何进程注入能力，改不了这个 bit。
   所以 Shizuku 方案的定位永远 `isMock() == true`。
4. 按 App 分流同理不可能：provider 是系统级单一状态，要"只有某个 App 看到假位置"必须在**那个 App 的进程内**
   hook，而进程注入只有 root 上的 LSPosed/libxposed 办得到。

| 你手上只有 | 能拿到假位置 | 全体 App 一致 | 隐藏 mock 痕迹 | 指定某几个 App |
|---|---|---|---|---|
| Shizuku(ADB) / Stellar 同款 | ✅ | ✅ | ❌ | ❌ |
| Shizuku(Root 模式) | ✅ | ✅ | ❌（同样没有注入能力） | ❌ |
| root + LSPosed + 本项目模块 | ✅ | 可选 | ✅ | ✅ |
| 开发者选项选定位 App | ✅ | ✅ | ❌ | ❌ |

一句话：**Shizuku 是"免 root 的 app-op 分发器"，不是"免 root 的注入器"**。它的价值是让没 root 的机器也能全局假位置，
以及让 root 机器不用装 Xposed；一旦目标是过检测或按 App 分流，就必须上 libxposed。

> **实机修正（A0）**：上面第 1 步在这台 PJF110 上不成立——ColorOS 系固件把 `MANAGE_APP_OPS_MODES` 从 shell
> 摘掉了，所以 Shizuku(ADB 启动) 同样拿不到 app-op，`cmd location` 也被 `noteOp` 拒。
> 这类 ROM 上的"免 root 档"只剩一条路：**开发者选项「选择虚拟定位应用」由 Settings 进程写 app-op**，
> 然后由 App 自己调 `addTestProvider` —— 即本项目的 `--mode app`（`MockService`）。
> 结论修正为：Shizuku 能否做虚拟定位，取决于该 ROM 是否把 `MANAGE_APP_OPS_MODES` 留给 shell，**必须实测**。

## A6 LSPosed 发行版的核实（2026-10-24 实测）

用户给的下载地址是 `https://lsposed.zip/`，我把它整个拉下来比对了：

| 项 | `lsposed.zip` 根返回的包 | GitHub `LSPosed/releases/latest` = **v1.9.2** |
|---|---|---|
| 大小 | 6,431,714 B | 2,462,055 B（zygisk-release） |
| SHA-256 | `ed37de2d2097aa6b…311e74` | `0ebc6bcb465d1c4b…29622` |
| 内部条目压缩 | 方法 95(XZ) + 8(Deflate) 混编 | 常规 |
| 每个文件带 `.sha256` sidecar | 是 | 否 |
| 条目日期 | 2026-10-04 | 2024 年代 |
| 内核实参 | `daemon.apk framework.dex manager.apk lspd lspctl mazoku machikado.{arm,arm64,x64,`**`riscv64`**`} customize.sh action.sh module.prop emulated-soft-reboot.sh` | 同类但无 riscv64、无 soft-reboot 脚本 |

判读：**不是** v1.9.2 那份资产，但内部布局与 LSPosed 已知二进制命名（`mazoku` 是它的 Zygisk 加载器、
`machikado.*` 是它的注入辅助）完全一致，且多了 `riscv64` 与 KernelSU/APatch 的软重启脚本——
特征指向 **v1.9.2 之后的续作发行**，而不是随手打包的马甲。同时要注意：GitHub 仓库 README 的 Download
徽章仍只指向 `releases/latest`，全文没有出现 `lsposed.zip`，所以**哈希层面无法证明同源**。

因此记为"结构可信、出处未证"，并给出可执行的自证方法（本机做不了，需要两个可信参照）：

1. 装好后比对 `manager.apk` 的签名证书：`keytool -printcert -jarfile manager.apk`，
   与历史可信版本（例如你以前装的 1.9.2）的证书指纹是否一致——**签名一致才算同源，zip 内的 `.sha256` 只能自证未被截断**。
2. 看 LSPosed 官方渠道（README 里列的 Telegram `t.me/LSPosed`）是否公告了该域名为新下载地址。

对本项目的影响：框架必须实现 libxposed 现代 API，模块才会被列出；这个 2026-10 的构建正是实现 102 的那一支，
所以本项目按 102 编译、`minApiVersion` 仍写 100 以放宽加载。

## 附录 B 代码解析





### B1. 配置契约（三层共用同一组键）

```
CLI set --persist  ->  /data/adb/mockloc/config.prop      (root 专属，App 进程读不到)
                          |  service.sh 每周期 cp 一份
                          v
                       /data/local/tmp/mockloc.conf        (chmod 664 + chcon shelldatafile)
                          |  被 App 进程读取，1s 缓存
                          v
                       rootmodule service.sh  ->  cmd location providers ...  (系统测试 provider)
                       app/.../Config.kt      ->  SpoofLogic  ->  hooks
```

键：`MOCKLOC_ENABLED / GLOBAL / PROVIDER / LAT / LNG / ACC / RADIUS / INTERVAL / TARGETS / LABEL`。
配置入口是 **`/data/local/tmp/mockloc.in`（shell 可写的收件箱）**，root 模块的 `sync_inbox` 再把它镜像到
`/data/adb/mockloc/config.prop`。这么设计是因为 A0 实测：KernelSU 不给 adb shell 提供 `su`，
PC 端根本写不了 `/data/adb`——收件箱让"只刷模块、不依赖 su"也能跑通档 2。
**`ENABLED` 和 `GLOBAL` 是两条独立的开关**，这是"按 App"与"全局"能共存的关键：
`GLOBAL` 只被 root 模块的循环读（要不要维持系统测试 provider），`ENABLED`+`TARGETS` 只被模块读
（在哪些 App 进程里改写读数并顺手把 mock 痕迹规避掉）。所以"某些 App 假、其余真"= `GLOBAL=false`+`TARGETS` 填包名；
"全体假"= `GLOBAL=true`（此时被 hook 的 App 仍然享有无痕路径）。
`LAT/LNG` 存**中心点 WGS-84**，抖动由每个消费者自己算（避免"写入方抖一次、读取方读到死值"）。
`TARGETS` 为空 = 作用域内全部 App。

`config.prop` 用 `sed -n 's/^KEY=//p'` 逐键读而不是 `. config.prop`：LABEL 可能带空格，source 会断句。

### B2. 文件地图

| 文件 | 作用 |
|---|---|
| `tools/coords.py` | WGS84/GCJ02/BD09 互转，反算用**迭代**（朴素相减残留 2.2 m） |
| `tools/amap.py` | 高德 REST 客户端（纯 stdlib，无 requests 依赖） |
| `tools/mockloc.py` | CLI：菜单式入口 + 参数式入口，`--dry-run` 打印将执行的每条设备命令 |
| `tools/tests/test_coords.py` | 数值验收：两个已知锚点 + 往返恒等 + 偏移量级断言 |
| `rootmodule/service.sh` | 开机等 `sys.boot_completed`，授权 app-op、建 provider、循环重注入 |
| `rootmodule/mockloc-lib.sh` | `cfg_get` / `jitter`(awk) / `apply_once` / `publish` |
| `app/.../Config.kt` | App 进程侧配置快照，1s TTL，读不到文件时把原因留在 `lastError` |
| `app/.../SpoofLogic.kt` | 两个 hook 后端共用的决策层，防止行为漂移 |
| `app/.../ModuleEntry.kt` | libxposed 入口，注册在 `META-INF/xposed/java_init.list` |
| `app/.../MainActivity.kt` | 四页 View：搜索 / 地图点选 / 收藏 / 状态（`selfCheck` + 各 provider 回读） |
| `app/.../CoordTransform.kt` | `coords.py` 的 Kotlin 移植，与 Python 版对同一批锚点数值一致（477.87 m） |
| `app/.../AmapSearch.kt` | 高德 REST（`HttpURLConnection` + `org.json`，零三方依赖） |
| `app/.../AmapTiles.kt` | 高德瓦片源（GCJ-02 地理参考的 Web Mercator） |
| `app/.../Places.kt` | 收藏点，JSON 存私有 prefs（不落 world-readable 镜像） |
| `tools/classdump.py` | 解析 `.class` 拿真实方法签名（本机只有 JRE，没 `javap`） |
| `tools/kotlin/CoordCheck.kt` | 可运行的 Kotlin 数值验收，不进 APK |

模块注册只走 libxposed 现代 API 一条路（按 [libxposed/api](https://github.com/libxposed/api) 的约定：
不需要 `assets/xposed_init`，也不需要 manifest 里的 `xposedmodule` meta-data）。
`module.prop` 的键名以官方 [libxposed/example](https://github.com/libxposed/example) 的
`META-INF/xposed/module.prop` 为准（`minApiVersion / targetApiVersion / staticScope`，注意**不是** `isStaticScope`；
写错键名的表现是模块在 LSPosed 里直接不出现，不报错）。这里 `minApiVersion=100 / targetApiVersion=102`：
按 102 编译，但把下限放到 100，因为"框架实际对外报哪个版本号"只能在机器上确认，取低值只会更宽松、不会挡加载。

### B3. 阶段 2 为什么不需要测试 provider

hook `Location.getLatitude/getLongitude` 之后，App 从真实 GPS 拿到的每个 `Location` 对象**读出来的数字就是假的**，
但时间戳、卫星数、provider、`elapsedRealtimeNanos` 全是真的——比"换个假对象"更不容易被一致性检测发现，
也不需要 app-op，所以 `isMock()` 这条检测路径根本不会被触发。

代价：这个 hook 作用于该进程内**所有** `Location` 对象，App 自己构造用于计算的 Location 也会被改写。
FakeLoc/XposedFakeLocation 都有同样的取舍，并用"每个字段单独开关"来缓解；本项目把开关留在配置里，
阶段 3 的 UI 再暴露出来。

## 附录 C 待验证清单（必须上真机）

按优先级，每条都是"我写了但我不能在本机证明"的东西：

1. **CI 首次编译能否通过**——本机 JDK 11 且无 SDK，Kotlin 层只做了人工审读。
2. **KernelSU 的 `su -c` 语义与 SELinux 上下文**：`cmd location` / `appops` 是否能在 KernelSU 模块的
   `service.sh` 里跑通（Magisk 语境下可以，KernelSU 的域不同）。
3. **KernelSU/APatch 是否接受本模块 zip 格式**（`update-binary` 取自 Magisk master）。
4. `chcon u:object_r:shelldatafile:s0` 后 App 进程能否真读到 `/data/local/tmp/mockloc.conf`（SELinux 拒绝的话
   诊断页 `错误` 一栏会显示 `read failed`）。
5. LSPosed 加载模块后 `selfCheck` 是否变 true（阶段 2 成立与否的唯一判据）。
6. 真 key 下高德地理编码的正向返回（当前只验过 `10001` 错误路径）。
7. `awk` 抖动种子的时间粒度是秒；`MOCKLOC_INTERVAL` 不要设到 1s 以下。
8. 你手上这个 LSPosed 构建对外报的 libxposed API 版本号（决定 `minApiVersion=100` 够不够），
   以及它的 `manager.apk` 签名是否与历史可信版本一致（A6 里那两个自证动作）。
9. `doctor` 的档 1 探针：确认 shell uid 单独授权 app-op 后 `cmd location` 真的不报 SecurityException
   ——这是"无 root 也能全局"这整条结论的唯一直接证据。
   **已实测：本机不通**（ColorOS 摘了 `MANAGE_APP_OPS_MODES`），见 A0；免 root 档改走 App 内 provider。
10. **高德瓦片能不能显示**：`AmapTiles` 用 `{x}{y}{z}` 占位符式 URL，osmdroid 6.1.20 的
    `TileSourcePolicy` 是否替换占位符要靠真机看瓦片出没出来；不出就改成拼接式 URL。签名与投影都对，唯一变量是它。
11. Android 16 上从 Activity 前台 `startForegroundService` + `foregroundServiceType=location` 是否放行；
    未授 `POST_NOTIFICATIONS` 时通知不显示、服务是否仍在跑。
12. 真 key 下 App 内搜索的正向返回（REST 层目前只验过 `10001` 错误路径）。

## 附录 D 版本历史

- **v0.1.0**（2026-10-24）侦察完成 + 阶段 1（坐标层数值验收、CLI 三条路径 dry-run 实测、root 模块脚本语法与函数实测通过）
  + 阶段 2 代码（libxposed + legacy 双入口，未编译）。CI 工作流就绪，未跑过。
- **v0.2.0**（2026-10-24）按复核结果收口三件事：
  ① **只保留 libxposed**，删掉 legacy 入口与 `api.xposed.info` 依赖，模块描述改走 `android:description`；
  `module.prop` 键名以官方 example 仓库为准（`staticScope`，不是 `isStaticScope`），`minApiVersion` 下调到 100 以免被版本门挡住。
  ② 补 A5：**Shizuku/Stellar 级权限能做全局、不能无痕、不能按 App** 的完整推理链（依据是 API 36
  `LocationManager.java` 里 `ACCESS_MOCK_LOCATION` 零命中）。
  ③ 配置契约加 `GLOBAL`，把"按 App"与"全局"拆成两条独立开关，CLI 相应加 `--global/--no-global`、
  `off` 改为就地置双关（不再删配置）。Kotlin 层 4 个 class 重新编译零错误零告警。
  ④ libxposed 升到 **102.0.0**（对 102 的 `classes.jar` 重编通过，确认无 API 差异影响）；
  新增 `tiers` 与扩展 `doctor`，把"无 root / root / root+Xposed"三档做成可一条命令对照、且探针用完自清理。
  ⑤ 核实 `lsposed.zip` 发行包（见 A6）：与 GitHub v1.9.2 资产不同源、特征指向续作，签名一致性仍需在机上验。
- **v0.3.0**（2026-10-24）CI 首次出包成功（`logic`/`apk`/`root-module` 全绿，`META-INF/xposed/` 打包守卫通过），
  随后真机核验（见 A0）：ColorOS 系摘掉了 shell 的 `MANAGE_APP_OPS_MODES` 与 `GRANT_RUNTIME_PERMISSIONS`，
  KernelSU 也不给 adb shell 挂 `su` ⇒ 新增 `--mode app`（`MockService` 前台服务自调 `addTestProvider`，
  app-op 由开发者选项授，`android:permission=DUMP` 挡第三方调用），并把配置入口改成
  `/data/local/tmp/mockloc.in` 收件箱 + root 侧 `sync_inbox` 镜像，使档 2 不再依赖 PC 端 root shell。
  A5 的 Shizuku 结论按实机修正为"取决于该 ROM 是否把 MANAGE_APP_OPS_MODES 留给 shell"。
  本地类型检查又抓到 3 个错（`Service.onBind` 未实现、`ProviderProperties` 不在 `android.location` 包、
  一个不存在的 `android.os.ILooper` import），均在推 CI 前修掉。
- **v0.4.0**（2026-10-24）阶段 3 App：四页 View（搜索 / 地图点选 / 收藏 / 状态）。
  选 View 不选 Compose 是为了可验证性——Compose 需要编译器插件，本项目的本地手段编不了它，
  等于把整个 UI 交给盲编；改成本地可编后，一轮就抓到 tab 容器方向错、`MapEventsOverlay` 废弃构造、
  `Marker.position` 属性化赋值三处。osmdroid 的 API 用 `tools/classdump.py` 从 `.class` 里读真实签名
  （本机只有 JRE 没有 `javap`），并确认 6.1.20 零传递依赖、`minCompileSdk=1`。
  `CoordTransform.kt` 与 `coords.py` 对同一批锚点跑出**逐位一致**的结果（477.87 m / 4.77e-07），
  两套实现互验。新增 INTERNET 权限与 `AmapTiles` 高德瓦片源。
- **v0.4.1**（2026-10-24）真机走查阶段 3：inset 与瓦片两个渲染问题修完（见 A0 与 `AmapTiles` 注释），
  注入链路端到端验通（点选 → 反算 → 三 provider 全 `[mock]`）。
  新发现并记录框架层 mock 的能力边界：**高德地图在注入生效期间仍显示真实城市「清远市」**，
  尽管它确实注册了 gps 客户端（见 A0 末段）——要覆盖这类自带定位引擎的 App，必须加地图 SDK hook 层。
  状态页文案也改了：原来一行"注入: 未生效"把「LSPosed 模块未加载」和「定位没注入」混为一谈，
  现在拆成 `系统注入` / `无痕模块` 两行，并直接列出四个 provider 的读回值。
