# mockloc — 安卓系统级虚拟定位（root / KernelSU）

给已 root（KernelSU / APatch / Magisk）的安卓机做虚拟定位。**三档都已在一加 Ace 3V / Android 16 上真机跑通**：
档 1（无 root，App 内 provider）、档 2（root 建系统测试 provider）、档 3（libxposed 在被 hook 进程里改写并抹痕迹，
模块确实挂进了微信与高德的各 3 个进程），证据与踩过的坑都在 A0 / A0.1。
阶段 3 的 App（搜索 / 地图点选 / 收藏 / 状态）也走完，高德瓦片出图正常。

**还没拿下的目标**：高德地图与微信在四层叠加下仍显示真实位置——它们的位置来自射频输入而不是
`LocationManager`（`appops` 的 `WIFI_SCAN` 计数是当场证据）。v0.4.3 按这个结论补了致盲覆盖面
（`WifiInfo` 标识位、`requestCellInfoUpdate`）并加了只记不改值的 **API 探针**，先拿到"它到底读了什么"再决定下一步。

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

# 模块到底挂进了哪些包与进程（读它自己打的 attached to，不用翻 logcat）
python tools/mockloc.py scope

# 高德地图专属：它自己算路，只开注入点没用，要把它的射频输入抽空让它落回系统定位
# （代价：目标 App 自己的 WiFi 相关功能同时失真；改完必须重启目标 App）
# 取值三态：--blind-wifi 单独写 = 作用域内全部；带包名 = 只致盲这些 App；false = 关
python tools/mockloc.py set --place 东方明珠 --blind-wifi com.autonavi.minimap --blind-cell com.autonavi.minimap --persist

# 四档权限各是什么、这台设备上哪档能拿下哪个 App
python tools/mockloc.py tiers
```

### 2.5 四档权限（这是"能不能过检测"的分水岭）

| 档 | 靠什么 | 需要的权限 | 按 App 分流 | `isMock()` | 目标进程里留痕迹 | 本项目 |
|---|---|---|---|---|---|---|
| **0** 普通 App | App 自己调 `addTestProvider`，app-op 由开发者选项「选择虚拟定位应用」授 | 无需 root，装 APK + 一次点选（**重装会清掉**） | ❌ 全体同一个点 | **true** | 不留 | ✅ `--mode app` |
| **1** Shizuku/Rish | 以 shell uid 调系统 API（`cmd location ...`） | 需要 shell 的 mock app-op 已在位（本机由 root 授过一次；近 AOSP ROM 上 adb 就能授） | ❌ 同上，**与档 0 同一数据面** | **true** | 不留 | ✅ `--mode adb`（2026-10-27 本机实测可用，见 A15） |
| **2** root | `cmd location providers ...` + **只有这档能按 uid 断网/关射频** | KernelSU/Magisk 给 su | ❌ 读数不能，但②可以按 uid | **true** | 不留 | ✅ `--mode su`（identity `0/android[LocationService]`） |
| **3** LSP/libxposed 模块 | 进目标进程改它读的每一个值 | root + LSPosed + 在管理器里勾作用域 | ✅ 按包 | **false**（压根没有测试 provider） | **留**（栈帧/maps/classloader） | ✅ 装 APK 即生效 |
| — 全局·无痕 | 在 system_server 里就地改写 `LocationResult` | root + LSPosed，且作用域要加 `system` | ❌ 全体 | false | 不留 | ⚠️ 未实现，见下 |

**读这张表最重要的一句**：档 0/1/2 给目标 App 的是同一个 mock provider，所以它们不是"权限越高越能过"
的阶梯——**档 0 推不动的 App，档 1、档 2 也推不动**。真正决定成败的是三个杠杆（①系统 provider、
②抽空它的输入、③进它进程改读数），完整矩阵与每档实测在 A15。

最后一行没写的原因不是能力不够，而是风险不对等：它要改 `LocationProviderManager.onReportLocation`
并按调用方 uid 分流，写错的后果是 **system_server 软重启**（XposedFakeLocation 的 issue #50/#49 就是这类）。
在还没有真机可测的现在，我不会把它盲编盲推。参考实现的位置已记在附录 A3。


`--mode adb` 只用 shell 权限（uid 2000）就够了，**不需要 root**（本机 2026-10-27 实测：能建
`[mock]` provider，identity `1000/android[LocationService]`）；缺点是重启失效、且 `adb shell` 一拔线就断。
`--mode su`（默认）走 root，配合 root 模块可以开机自恢复。早期文档写过"`--mode adb` 在本机不通"，
那是把"shell 不能*自授* app-op"（ColorOS 摘了 `MANAGE_APP_OPS_MODES`）和"shell 不能建 provider"
混成了一句，A15 已更正。

### 2.6 三档分别怎么测（建议顺序）

每档跑完都打开 mockloc 页面读那两行判据，再拿一个你真实关心的 App 复现一次。

| 档 | 前置 | 跑什么 | 页面预期 |
|---|---|---|---|
| 1 无 root | 装 APK → 开发者选项「选择虚拟定位应用」选 mockloc → 授定位权限 | `--mode app set --place 东方明珠 --first` | `系统注入 : 生效中`、`LKL gps : ... mock=true`；`无痕模块 : 未加载` |
| 2 root | 刷入 `mockloc-root.zip` | 档 2 那行（加 `--persist`） | 同上，且**重启后再看一次应仍是假位置** |
| 3 root+LSPosed | 装 APK + 在 LSPosed 里启用模块并把目标 App 勾进作用域 | 档 3 那行（`--no-global --target`） | `无痕模块 : 已加载`；目标 App 里 `mock=false`，未勾选的 App 拿到真实位置 |

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
aar 拉下来解出 `classes.jar` 后，本仓库全部 Kotlin 文件在它上面重新类型检查过：**零错误**，
说明我的代码没有用到 101 与 102 之间有任何差异的 API）。协程/Compose 一律没用。

**加依赖时必须先看 AAR 里的 `META-INF/com/android/build/gradle/aar-metadata.properties`。**
本地类型检查只喂 `classes.jar`，看不到这份元数据，而 AGP 会拿 `minCompileSdk` 跟项目的 `compileSdk` 比，
超了就在建 APK 阶段直接失败——`hiddenapibypass:6.2` 就是这一类（它写 `minCompileSdk=37`，我们 36），
第一次 CI 就是这么红的。查法（不用 JDK，本机就能跑）：

```bash
unzip -p hiddenapibypass-6.2.aar META-INF/com/android/build/gradle/aar-metadata.properties | grep minCompileSdk
```

顺带一条：`osmdroid 6.1.20` 是 `minCompileSdk=1` 且零传递依赖，`hiddenapibypass:6.1` 也是 1 —— 两者都不给 CI 添风险。

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
| 状态 | `系统注入` / `无痕模块` 两行判据、四 provider 回读、模块配置回读（开关/作用包名/三个反检测档位/读没读到）、停止注入 | 这是唯一能看出"注入到底生效没"的地方；配置一栏显示的就是别的进程里模块下一次要读的内容 |

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

**框架层 mock 的边界（实测部分与撤回部分要分开看）。** 注入生效期间 `dumpsys location` 里 `gps/network/fused`
三个 provider 全是 `[mock]`，我们 App 自己的状态页读回三行一致坐标；`dumpsys location` 也明确记录
`com.autonavi.minimap` 与 `com.tencent.mm` 都注册了 gps 客户端。

当时我据"高德地图首页仍显示真实城市「清远市」"断定这类 App 不吃框架层 mock，并推出一整条"必须加地图 SDK hook 层"
的计划 —— **这条推断已撤回，错在方法**：首页城市是缓存/定位历史，不是一次新的定位请求，拿它当证据等于没测。
用户实机反馈也相反（开发者选项的虚拟定位，高德是认的，微信不认）。正确判据见 C18：注入后**主动触发一次新定位
请求**，读它返回的坐标。不受这条撤回影响、仍然成立的是两条有 DEX 证据的事实：① 两个 App 都读 `isMock`（A7 的计数），
所以无痕只能走档 3；② 高德地图**不含公开定位 SDK 类**，所以给它 hook `AMapLocation` 是错的（A7 第 1 条），
可行打法是饿死它的网络定位输入（A7 第 3 条），而不是猜它混淆后的私有类名。

### A0.1 二次实机核验（2026-10-26：档 2 首验、档 3 首验、高德/微信的真实答复）

环境：装 `0.4.2-143edae`；hook 框架确实在跑（`ps` 里有 `lspd` 与 `zn-zygisk-companion64 zygisk_lsposed`，
日志 tag 是 `LSPosedFramework`，管理器包名 `roro.stellar.manager`）；作用域=系统框架+高德地图+微信；
KernelSU 给 Shell 授权后 `adb shell su -c id` = `uid=0 ... context=u:r:ksu:s0`（**默认不给，要手动开**）。

| 结论 | 证据 |
|---|---|
| **档 2 首次验通** | `--mode su set` 后 `dumpsys location`：`gps/network/fused` 三个 `[mock]`，`identity=0/android[LocationService]`，都投 `31.24,121.50`；我们状态页四行（含 passive）完全一致 `mock=true` |
| `mock_location` 这个设置**不再是证据** | mock 全开期间 `settings get secure mock_location` 仍是 `0` ⇒ App 读它查不出事，AnyWhere 钩这条属过期防御 |
| **档 3 首次验通** | 微信 3 个进程 + 高德 3 个进程（含它自己的 `:locationservice`）都有 `attached to`；整份清单**只有一处失败** |
| 那一处失败 = `Location#readFromParcel` 在 Android 16 上不存在 | 同一次运行里 `HiddenApiBypass 未生效` 这条**没有**打印 ⇒ 豁免成功 ⇒ `getDeclaredMethod` 的 NoSuchMethod 是真没有，不是被挡。AnyWhere 的清字段招法在新系统上已过期 |
| 配置通道不需要 root 也不需要 `chcon` | 微信是**非 debuggable** 的第三方进程，照样读到 `u:object_r:shell_data_file:s0` 的 `mockloc.conf`（把 `MOCKLOC_GNSS` 改成 false，模块日志立刻变 `gnss=false`）；本机 shell 里连 `chcon` 都没有（`inaccessible or not found`） |
| **高德、微信在四层叠加下仍显示真实位置**（连州，街道级） | 档1/档2 的 mock provider + 档3 改写 + 致盲 `getScanResults/getAllCellInfo/getCellLocation` 全开，并确认系统把 mock 定位投给了高德（`locations = 1`） |
| 原因不是"它不扫 WiFi"，而是**我们盲得不够** | `su -c appops get --uid com.tencent.mm` 与 `...minimap`：两者的 `WIFI_SCAN` 都是 `allow; time=+3m/4m ago` —— 正在测的时候它们确实扫了。已连 AP 的 BSSID 走 `getConnectionInfo`，我们当时**没钩**；基站走 `requestCellInfoUpdate`，也没钩 |

**普通 App 确实吃到假位置（正向对照）。** ColorOS 天气（不在作用域、没被 hook）显示「閔行區」——它走 GPS 定位，
而机器真实在连州、IP 也在广东，所以这个上海区县只能来自 mock 出来的坐标。档 2 的系统层投递有效。

**高德是"读了但不用"。** 按一次"我的位置"前后对比 `appops get --uid`：`FINE_LOCATION time=+496ms`（读了）与
`WIFI_SCAN time=+2.7s`（同一时刻又扫了 WiFi）同时刷新。由于此刻系统 mock 与模块配置是同一个点，
"它仍显示连州"只能解释为**它拿到了 `LocationManager` 的值却不用**。关掉 WiFi 后蓝点不退化、纹丝不动，
最一致的解释是它回落到基站——而 Android 12+ 的基站读取走 `requestCellInfoUpdate`，正是 v0.4.3 补的那个洞。

**参考实现的现状。** 你装的 LocationSpoofer，其仓库 [HuangZhuoRui/LocationSpoofer](https://github.com/HuangZhuoRui/LocationSpoofer)
现在**只剩 README**：改名 AwesomeDroid、旧版停止维护、转向面向开发者的 Android 模拟器，`git log --all` 里从来没有源码。
所以可参考的是它自己打进 logcat 的钩子清单：FusedLocationProvider、GnssStatus、WiFi 环境、
`getAllCellInfo / getCellLocation / getNeighboringCellInfo / getNetworkOperator(Name) / getNetworkType /
getDataNetworkType / getPhoneType / getServiceState(ForSlot) / getSignalStrength / listen /
requestCellInfoUpdate / registerTelephonyCallback`、Connectivity 层、BLE，外加 Tencent/百度/Google 三家地图 SDK 的类查找。
它还做了一件我们完全没有的事：**调用栈清洗**——钩 `Throwable.getStackTrace` / `Thread.getStackTrace`，
把 `de.robv.android.xposed` / `io.github.libxposed` / `org.lsposed` 这些帧抹掉。风控 SDK 找 hook 的第一入口就是异常栈。
本机它当前报"LSPosed 模块未激活"，所以"成熟工具能不能挪走高德"这个市场验证**还没做**。

**档 3 判死：改写与抹痕都生效（0.4.4，判别性设计）。** 故意让两个来源给不同值——系统 mock 留上海
`31.24,121.50`，模块配置指北京 `39.9087,116.3975`——然后让模块在被 hook 的进程里自读一次：

```
(com.autonavi.minimap)                 自检 gps=39.9087,116.3975 mock=false  network=… fused=… mock=false
(com.autonavi.minimap:locationservice) 同上   （高德 3 个进程、微信 4 个进程，全部一致）
```

系统侧 `dumpsys location` 仍是 `Location[gps 31.24,121.50 mock]`。同一个进程里读到的是**北京且 mock=false**
⇒ `getLastKnownLocation` 替换、`isFromMockProvider` 清除、`getProviders` 过滤三件事同时成立。

**但天气 App 的界面没有跟着变（仍显示"上海市"），这不算反证也不算确认**：它的城市来自自己的存量城市列表，
下拉刷新那次探针一条读取都没录到（说明没真的重新定位）。教训和 A0 那条同源——**显示值 ≠ 读取值**，
仪器要放在读回那一层，不是放在"某个 App 的界面变了没"。

**于是微信的真实地址有了确定解释**：替换在微信进程里早就生效（0.4.2 起就挂着），它的位置卡片还是真实
⇒ 微信的位置来自**内嵌腾讯定位 SDK**，不是 `LocationManager`；而那个 SDK 有自己的 Location 类型和
自己的 `isMock()`。已按 A8 的分层加 `TencentSdk.kt`（钩 `TencentLocationImpl` 的经纬度/精度/`isMock`，
并把地址文字换成配置里的地点名——只改坐标不改文字正是"地图动了字没动"的那种不一致）。

**0.4.3 第一次上机就抓到三处"仪器本身坏了"**（这正是 `Diagnostics` 要可见的理由——三处都是它自己报出来的）：

| 报出来的话 | 实情 | 修法 |
|---|---|---|
| `自检跳过：拿不到 Application` | `onPackageReady` 触发时进程还在绑定 Application，`ActivityThread.currentApplication()` 是 null | `SelfTest.schedule()` 改到主线程 1.5s/4s/10s 三次重试 |
| `AppOpsManager#checkOperation/noteOperation/startOperation 不存在` | 我照 API 文档写的名字这版运行时里没有；公开面是 `checkOp / checkOpNoThrow / noteOp / noteOpNoThrow / startOp / startOpNoThrow`（`checkOperation` 那组是 @hide/systemapi） | 换成 classdump 出来的真实名字 |
| 探针调用方打成 `k2.intercept:8` | 那是 hook 链自己的分发帧，不是 App 的调用点 | 过滤名单补 `xposed`/`Hooker`/`androidx`/`kotlinx`/`art.`，并一次给最多 3 帧 |

顺带一条正向观察：探针在微信冷启动 18 秒内只录到 `开发者设置 getInt/getString`，没有任何
`定位读取/WiFi输入/基站输入` —— 它的定位 SDK 是**用到才起**的，所以探针必须在真正打开位置功能时跑才有意义。

**我在这次核验里犯的两次方法错误，记下来防重犯**：① 用 `awk '$2==pid'` 过滤 logcat（pid 是第 3 列），
把"高德主进程没挂上"当成了结论；② 按 `我的位置` 用了上一次布局的坐标，点的是空白处，却去解读那张图。
两次都让一个不成立的结论差点进文档。**同一个界面重进后要先 `uiautomator dump` 重新取坐标**。

系统框架在作用域里目前**不产生效果**：`ModuleEntry` 显式跳过 `android` 进程（改 system_server 的风险不对等，见 §2.5）。

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

### A7 目标 App 到底吃什么定位（从 APK 的 DEX 里抠出来的，不是猜的）

`tools/dexprobe.py` 直接在设备上拉两个 App 的 base.apk 搜 DEX 字符串：

| App | 公开定位 SDK | 实际用的东西 | `isMock` 痕迹 |
|---|---|---|---|
| 高德地图 `com.autonavi.minimap` (172 MB, 8 dex) | **无**（`com/amap/api/location` 命中 0） | 自家引擎 `com/amap/location/api/ILocationService`、`com/amap/location/type/location/Location`、`INetworkLocationListener`、`VALocationResult` | `isMock` 17 次、`isFromMockProvider` 1 次 |
| 微信 `com.tencent.mm` (219 MB, 18 dex) | **有** `com/tencent/map/geolocation`（20 次）、`TencentLocation`（28 次） | 腾讯定位 SDK + `com/tencent/mm/plugin/location/model/LocationInfo` | `isMock` 11 次 |

> **2026-10-26 更正这张表的读法**：上表的"次数"是**裸字节子串**命中，会算进字符串表里的碎片，
> **不等于类定义存在**。按类型描述符 `Lcom/tencent/map/geolocation/…;` 精确查，微信 18 个 dex 里的
> 真实定义在 **`com.tencent.map.geolocation.sapp.*`**（`TencentLocation`/`TencentLocationListener`/
> `TencentLocationManager`/`TencentLocationRequest`/`TencentLocationManagerOptions`/`TencentLocationUtils`/
> `TencentPoi`，定义在 `classes14.dex`），而且**没有 `TencentLocationImpl`**——所以照公开 SDK 的类名去钩，
> 本模块第一版和 LocationSpoofer 的 `TencentLocation SDK not found` 都会落空。结论：**引用计数 ≠ 定义，
> 要判"有没有这个类"必须解析 `class_defs`。**

推论与打法：

1. **给高德地图 hook `AMapLocation` 是错的**——它压根没有这个类。FakeLoc 的 `MapSdkHooks.kt` 覆盖的是
   `com.amap.api.location.*` / `com.baidu.location.*` / `com.tencent.map.geolocation.*` 这三家**公开 SDK**，
   对第三方 App 有效，对高德地图本体无效。
2. 微信可以直接走公开 SDK 路径（类名已由 DEX 证实存在）。
3. 高德地图这类自带引擎的，通用打法是**饿死它的网络定位输入**：在目标进程里 hook
   `WifiManager.getScanResults/getConnectionInfo` 与 `TelephonyManager.getAllCellInfo/getCellLocation`
   返回空，逼它回落到 GPS —— 而 GPS 已经被我们 mock。FakeLoc 的 `SourceLockHooks.kt` 正是用这招
   治"地图跳一下又飘回去"。这比猜私有类名可靠，也不需要知道混淆后的名字。
4. 两者都读 `isMock`，所以这一层必须和档 3（libxposed 抹痕迹）一起做，单靠系统 mock 无效——已实测。

### A8 LocationSpoofer 逆向结果（2026-10-26，只取事实不取代码）

**为什么逆向它**：它是本机唯一"在同一台 Android 16 + KernelSU + libxposed 上跑通过"的同类实现，
而作者已把仓库改成 AwesomeDroid、旧版归档（`git log --all` 里从来没有源码），且服务端已关 ⇒ 只能从
你手机上那个 APK 读。

**许可边界（先说死）**：它的 LICENSE 是 **GPL-3.0**。所以本节只记"它钩哪些 API、分几层、为什么"这类
**事实与思路**，不把它的代码搬进本仓库——那会让 mockloc 变成衍生作品并被迫换证。

**方法**（全程本机可复现）：

```bash
adb shell "pm path com.vincenthzr.locationspoofer"          # 拿到 base.apk 路径
adb shell "su -c 'cp <path> /data/local/tmp/lsp-base.apk; chmod 664 …'" && adb pull …
java -jar repack/apktool.jar d -r -f base.apk -o smali      # -r：只出 smali，不解资源
grep -hoE '"[^"]{4,60}"' hooks/System*HookerKt.smali | sort -u   # 钩子目标都是字符串常量
```

**模块注册面**：`META-INF/xposed/module.prop` = `minApiVersion=100 / targetApiVersion=102 /
autoHotReload=true`（比我们多一项热重载）；`java_init.list` 指向 `xposed/LocationHooker`；
**另有一份静态 scope 列表**（9 个包名：微信、学习通 ×2、钉钉、美团、百度地图、高德地图、腾讯地图、GMS）。

**它的分层（215 个 smali 文件，按包名列出来就是架构表）**：

| 层 | 类 | 钩的东西（字符串常量抠出来的） |
|---|---|---|
| 1 目标 App 自家 SDK | `AMapHooker` `TencentMapHooker` `BaiduMapHooker` `GoogleAndAddressHooker` | `com.amap.api.location.{AMapLocation,AMapLocationClient,AMapLocationListener,AMapLocationQualityReport}`、`com.tencent.map.geolocation.{TencentLocation,TencentLocationManager,TencentLocationImpl,内部 Impl,Listener}`、`com.baidu.location.{BDLocation,BDLocationListener,BDAbstractLocationListener,LocationClient}`、`com.baidu.mapapi.map.MyLocationData`、`com.google.android.gms.location.{FusedLocationProviderClient,LocationCallback}`、`com.qualcomm.location`/`atfwd` |
| 2 回调投递点 | `SystemLocationServiceHooker` | `ILocationListener` 的 `acceptLocationChange` / `deliverOnLocationChanged`，外加 `PendingIntent` 路径 ⇒ 在**投递那一刻**改一次对象，覆盖所有 listener 变体 |
| 3 binder 服务侧 | `SystemWifiServiceHooker` `SystemTelephonyServiceHooker` `SystemBluetoothServiceHooker` | `ServiceManager.getService("phone")` → `PhoneInterfaceManager.{getAllCellInfo,getCellLocation,requestCellInfoUpdate}`；WiFi 在 `BinderProxy` 上钩 |
| 4 权限/ops 自证 | `SystemAppOpsHooker` `AntiDetectionHooker` | `AppOpsManager.{checkOperation,noteOperation,checkAudioOperation}`、`Settings$Secure.getString`、`ApplicationPackageManager`/`ContextImpl`/`ContextWrapper` 的权限查询、`hasSystemFeature` |
| 5 环境**伪造** | `network/CellInfoListBuilder` `CellStateAndSignalBuilder` `BluetoothScanRecordBuilder` `BluetoothScanDispatcher` | 造出基站列表/信号/蓝牙广播与 SSID+BSSID，MAC 用 `02:%02x…`（本地管理位）、SSID 用 `<unknown ssid>` ⇒ **不是返回空**：真机上永远能看到周围 AP 与基站，返回空本身就是异常 |
| 6 GNSS 原始流 | `GnssStatusHooker` `GnssFastMockEngine` `GnssNmea` `NmeaSpoofing` `GnssSatelliteCatalog` | `registerGnssMeasurementsCallback`、`registerGnssNavigationMessageCallback`、`registerGnssStatusCallback`，以及遗留 `addGpsStatusListener/getGpsStatus/getSatellites` |
| 7 运动一致性 | `SensorStepHooker` `SystemAccelHooker` `SystemGaitWaveform` `utils/SpoofedMotion` | 加速度计与步数要和"人在移动"自洽 |
| 8 诊断 | `diagnostics/HookStatus` | 字段有 `class/components/config/errors/found/load_kind/…` ⇒ 与我们的 `Diagnostics` 同一思路（失败要可见） |

**两条否定结论（比肯定结论更值钱）**：

1. **它也没钩高德地图本体的引擎。** `AMapHooker` 打的是公开 SDK `com.amap.api.location.*`，
   而 A7 已用 DEX 证明高德地图**不含**这个包。所以它对高德地图本体只能靠第 3/5/6 层——
   也就是说"想让高德地图挪位置"这件事，成熟工具同样没有捷径，只有伪造射频输入与 GNSS 原始流。
2. **它 README 声称的"调用栈清洗"在这个 APK 里不存在。** 全 smali 树里没有任何
   `"getStackTrace"` 常量、也没有钩 `Thread`/`Throwable` 的地方 ⇒ 文档超前于实现，别照着做。

**据此落地的第一件（v0.4.6）**：微信那一层不靠实现类名，改成**只依赖接口**的接管——
`TencentLocation` 与 `TencentLocationListener` 经 DEX `access_flags` 确认都是 interface，于是
① 钩 `TencentLocationManager` 上**所有以 listener 为参数**的方法（不猜方法名与签名）；
② 把 listener 换成动态代理，在 `onLocationChanged` 里把 SDK 的位置对象换成 `TencentLocation` 的
`Proxy`，按**运行时方法名**应答经纬度/精度/`isMock`/地址文字，其余方法原样转发给真对象。
这样 SDK 换版本、实现类被混淆都不影响接管点，且 SDK 不存在的 App（高德）只是安静跳过而不是报错。

**据此排的本项目待办（按性价比）**：
① 便宜且直接补无痕面：`AppOpsManager.checkOperation/noteOperation` 的 `OPSTR_MOCK_LOCATION`、
`PackageManager` 权限/已装包自证；② `registerGnssMeasurementsCallback` /
`registerGnssNavigationMessageCallback` 的注册吞掉（我们目前只处理了 GnssStatus）；
③ 第 2 层投递点改写（`ILocationListener`），比逐个钩 getter 更兜底；
④ 第 5 层从"返回空"改成"造一份自洽的 AP/基站列表"；⑤ 第 6 层 GNSS 测量/NMEA 流是大工程，
也是唯一可能真正挪动高德地图本体的层。`module.prop` 可加 `autoHotReload=true`。

### A9 GNSS 原始测量与 NMEA 注入层（v0.4.7，取证 → 可行性 → 设计）

**为什么要加这层**：高德地图在 `Location` 已被改写的前提下仍显示真实位置，而 `dumpsys location` 给出
它的另一条输入：`measureListener: 4`、`GNSS measurement events = 652`。也就是说它读的是**原始卫星测量**，
不是框架算好的位置。

**可行性取证（不靠记忆，逐条查）**：

| 问题 | 结论 | 依据 |
|---|---|---|
| 能不能自造 `GnssMeasurement`？ | **能** | AOSP 源码里 `public GnssMeasurement()` 与 `setSvid/setState/setCn0DbHz/setReceivedSvTimeNanos/...` **都是 public，只是 `@hide`**（`location/java/android/location/GnssMeasurement.java:266,334-`）⇒ 不在 android.jar 里，但在运行时里，而本模块的 hidden-API 豁免已实测生效 |
| `GnssClock` 呢？ | 同样能 | `GnssClock():69` + `setTimeNanos/setFullBiasNanos/setBiasNanos/setDriftNanosPerSecond/setLeapSecond/...` 全 public |
| 事件怎么组装？ | 有公开 Builder | `GnssMeasurementsEvent.Builder()`、`setClock`、`setMeasurements(Collection)`、`build()`（`GnssMeasurementsEvent.java:248-354`） |
| NMEA 呢？ | **最简单** | 回调载荷就是字符串（`OnNmeaMessageListener.onNmeaMessage(String, long)`，旧版 `GpsStatus.NmeaListener.onNmeaReceived(long, String)` **参数顺序相反**），我们完全可以自己写句子 |
| 编译期能不能引用这些类型？ | **不能** | 它们都是 @hide ⇒ 全部改成按名字反射，`GnssStream.kt` 里没有任何一处直接 `new` 框架类型 |

**自洽性设计**（这是"可验证"的关键，不是把字段填满就行）：

1. **一份星历三处共用**：新增 `Sky.SATS`（7 颗 GPS，svid/高度角/方位角/C-N0 固定），
   `GnssStatus` 的 12 个 getter、`GnssStream` 的测量事件、NMEA 的 GSV/GSA 卫星数**都从它取数**。
   交叉核对这三处是半成品伪装最常见的暴露点。
2. **斜距是算出来的不是编的**：`pathMeters(e) = sqrt((R+h)² − R²cos²e) − R·sin e`，
   R=6371 km、h=20 200 km（GPS 轨道半径），`receivedSvTimeNanos = t_rx − path/c` ⇒
   任何按高度角反查伪距（20 200–26 400 km、传播 67–88 ms）的消费者都能对上。
3. **时钟稳定**：`fullBiasNanos` 由 GPS 纪元（1980-01-06）偏移算出并在进程内固定，
   `leapSecond=18`，`bias/drift=0` 带小不确定度——每秒重启时钟本身就是特征。
4. **静止一致性**：`pseudorangeRate=0` + `ADR=0` 且 `ADR_STATE=USE|LOCKED` + RMC 速度/航向 0.0
   ⇒ 多普勒、累积距离、地面速度三者都指向"没动"。
5. **状态位**：`state = CODE_LOCK|BIT_SYNC|SUBFRAME_SYNC|TOW_DECODED|2ND_CODE_LOCK`，
   `multipath=NOT_DETECTED`，`carrierFrequency=1.57542e9`（L1），C/N0 与 `GnssStatus` 逐颗对应。
6. **NMEA 带真校验和**：`$GPGGA/$GPRMC/$GPGSA/$GPGLL/$GPGST`，XOR 校验，经纬度转 `ddmm.mmmm,hemi`。
7. **降级不静默**：只有**真的造出一个事件**才吞掉 `registerGnssMeasurementsCallback`；造不出来就保留
   真实注册并记一条 `Diagnostics`（否则 App 会从"有矛盾数据"变成"完全没数据"，更糟）。NMEA 无此顾虑。
8. **可外部验证**：`--probe` 打开时打印一个周期的样例句子，可以直接喂给任意 NMEA 解析器验格式与校验和。

**0.4.7 第一次上机，这层自己报了三条错**（`notes=5`，全是台账抓的，不是人肉看出来的）：

| 报出来的话 | 真实原因 | 修法 |
|---|---|---|
| `android.location.GnssMeasurement 造不出来: NoSuchMethodException` | setter 真名是 **`setAutomaticGainControlLevelInDb`**，我写成 `setAgcLevelInDb` | 按 AOSP 源码逐个核 setter 名 |
| `LocationManager#registerNmeaListener / unregisterNmeaListener 不存在` | API 36 的 `LocationManager` 只有 **`addNmeaListener`(4 重载)/`removeNmeaListener`**，`register*` 那对属于 AIDL 层 | 只装 add/remove |
| 样例句子 `$GPGGA*082932.5,…` | **NMEA 框架写错**：`*` 是校验和的引导符、不是语句号与字段之间的分隔符 | 改成 `$GPGGA,字段,…*CS` |

修完把句子生成抽成**纯对象 `Nmea`**（不碰任何框架类型），于是能在 JVM 上直接验收：
`tools/kotlin/NmeaCheck.kt` 16 项断言全过，含"把 GGA 的 `ddmm.mmmm,N` 解析回十进制度并与写入坐标比对
（±1e-4°）"、"GGA 必须 14 个字段"、"五条句子校验和自算自验"、"传播时间随仰角单调且落在 67.4–83.1 ms"。
**这个测试本身也错过两处**（RMC 字段下标、8° 传播时间凭印象写 87 ms），都是断言错、实现没错——
再次说明"能跑的断言"必须和"几何/协议事实"互查，不能只信一边。

**已知不严谨处（写清楚，不含糊过去）**：真实静止接收机的多普勒并不为 0（卫星在动，±500 m/s 量级），
我们取 0 是为了与"速度 0、ADR 不增长"自洽——**能通过"是否静止"的一致性检查，但过不了严格的最小二乘
定位解算**。要严格自洽需要真解一组伪距（选星 + 迭代），那是下一步；本层目标是让"读 GNSS 判断有没有
真卫星/在不在动"这类检查给出我们要的答案。

### A10 回归测试结论（机型 OnePlus PJF110 / 一加 Ace 3V，Android 16 / API 36，KernelSU + Stellar(libxposed 102)）

| 场景 | 结论 | 判据与失败原因 |
|---|---|---|
| 档 1 App 内 provider（无 root） | **通过** | `--mode app set` 返回生效；`dumpsys location` 三 provider `identity=10304/com.mockloc`；状态页四行 `mock=true` 一致。**前置**：开发者选项「选择虚拟定位应用」必须是 mockloc，且**重装会清掉** |
| 档 2 root 建系统 provider | **通过** | `--mode su set` → `gps/network/fused` 三个 `[mock]`，`identity=0/android[LocationService]`。**注意**：provider 会随注册进程回收，本轮两次发现 `providers=0`，测前须重新注入；开机自恢复（刷 zip）**仍未验** |
| 档 3 进程内坐标替换 | **通过** | `SelfTest` 在 7 个进程读回 `39.9087,116.3975 mock=false`，系统侧同时是 `31.24,121.50 mock=true`（判别性设计，见 A0.1） |
| 档 3 mock 痕迹清除 | **通过** | 同上：同一进程里 `isFromMockProvider` 读回 false，而 `dumpsys` 说是 true |
| 腾讯定位 SDK 层 | **通过（用户实机确认）** | `[Tencent]: 接管 TencentLocationManager 的 5 个请求方法`；用户经文件传输助手发出的定位卡片显示**北京** |
| 微信 | **通过** | 同上（腾讯 SDK 层） |
| 高德地图 | **未通过 → v0.4.7 针对它加了 GNSS 层** | 原因已定位：它不吃 `Location`（`measureListener: 4`、`GNSS measurement events = 652`），且关掉整个 WiFi 后蓝点不退化 ⇒ 位置来自自家引擎/缓存。新层是否解决**待装机验证** |
| 系统天气（对照仪器） | 读取**通过**、显示**不变** | 进程内读回北京，界面仍"上海市"——它的城市来自存量列表，那次下拉刷新探针一条读取都没录到。**教训：显示值 ≠ 读取值** |
| 高德瓦片 / 点选 / 收藏 | **通过** | 真机出图；点选双坐标偏移 ~450 m 与坐标层实测一致；收藏→列表→注入链路通 |
| CI 三 job | **通过** | `logic`/`apk`/`root-module` 连续出包 0.4.3→0.4.6（`hiddenapibypass` 钉 6.1 之后） |
| 反检测面 | **部分通过** | 生效：`isMock`/`isFromMockProvider`/setter 源头堵/extras/provider 列表/`Settings.Secure`/GnssStatus 12 getter/伪卫星回调。已过期：`readFromParcel`（API 36 无此方法）。**未做**：调用栈清洗、`getProviders` 的 Criteria 之外变体、`PackageManager` 隐藏管理器 |

### A11 参考项目整合（2026-10-27，`参考/` 下 5 个来源）

用户放了三个源码项目和两个商用 APK。**授权先说清**，因为它决定能拿什么：

| 来源 | 授权 | 我们拿走的 | 明确不拿的 |
|---|---|---|---|
| AnyDoor | **MIT** | 四条，见下 | —— |
| allmapLocation | 无 LICENSE ⇒ 视为保留所有权利 | 只有思路与类名线索 | 任何代码 |
| FakeLoc | 无 LICENSE（README 写"学习/授权测试"） | UI 形态与保活细节 | 任何代码 |
| 全域_3.1.5.apk | 商用；内嵌 BlackBox(**GPL-3.0**) + Rhino(MPL-2.0) | 逆向得到的靶子清单 | dex/so 一律不搬 |
| 天下游.apk | 商用 + 腾讯乐固壳 | 无——**死路**：无 Xposed 标记、走特权 helper 进程 | —— |
| LocationSpoofer（见 A8） | GPL-3.0 | 八层地图 | 任何代码 |

**AnyDoor（MIT）给的四条，采纳三条**：
1. 源头改写在 system_server 的 `LocationProviderManager.onReportLocation` + 每条注册的
   `Registration.acceptLocationChange`——唯一"App 自己重算也拿不到真值"的位置。
   **暂不做**：风险不对等（写错=system_server 软重启），且要求 `android` 进作用域。记在 E 后续方向。
2. **绝不拷贝原始 extras**（fused 的 `noGPSLocation` 把真实位置藏在 extras 里）⇒ 我们的
   `SpoofLogic.fakeLocation` 只复制 time/nanos/bearing/speed/altitude，不复制 extras ✓
3. **不钩 `hasAltitude/hasSpeed/hasBearing/hasAccuracy`**（Parcel 布局，钩了会被高德 `#0802` 类校验抓到）✓
4. SELinux 无关的配置桥（root 写 `/data/system/*.json` + 假 provider 的 extras 当进程内信箱）
   ⇒ 我们用更简单的等价物，见"断链已修"。

**allmapLocation / 全域给的高德靶子**：`com.amap.android.location.NetworkLocationManager`、
`com.amap.location.offline.{flp,nlp}.LocationProvider`、厂商融合进程 `com.oplus.location` /
`com.samsung.android.location`、`com.amap.bundle.network.request.param.NetworkParam`。
判据是"**带点的字符串在、`L…;` 描述符不在**"⇒ 它们是运行时反射加载的，所以只能按名字找。
据此加 `AmapSdk.kt`（钩高德自家 `com.amap.location.type.location.Location` 的 getter，候选类名 +
找不到就自报 + 探针模式打印方法表）。它的 `BlindHook`（自动钩所有返回 Location 的方法 + 把名字含
`Correct/Valid/Check/Mock` 的 boolean 强制 true）**只取前一半**：强制 boolean 太容易把 App 弄坏。

**FakeLoc 给的 UI/保活经验**：地图即首页、运行中拖动图钉实时改点、无 key 的高德栅格瓦片、
`PARTIAL_WAKE_LOCK` + `START_STICKY` + 从 prefs 恢复、`Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS`
深链跳开发者选项。已采纳：**粘贴坐标入口（带 GCJ/WGS 切换）**、状态页分层徽章；
**作用域页在 v0.5.1 改成只读**——它原来要求在同一批 App 上第二次勾选（LSPosed 里一次、App 里一次），
而模块只可能被加载进 LSPosed 作用域内的进程，第二份名单永远不能往外扩，只能往外缩，属于纯摩擦。

**断链已修（App 选点 ≠ 模块配置）**：App 现在把选点和名单写进自己的私有 prefs，并由
`ModuleBridge.render()` 渲染成与配置文件逐字节相同的文本；`tools/mockloc.py sync`（PC）或 root 模块的
`sync_app_prefs`（免 PC，按 mtime 谁新谁赢）负责搬运。**渲染器只有一份**（Kotlin 侧），PC 和 shell
不重写格式，所以不会再出现三处各写一遍然后漂移。`ConfigCheck.kt` 把 `render()` 的输出直接喂回
`Config` 解析器验往返。

### A12 探针实测：高德地图内部到底读什么（0.4.9 上机，`--probe` 的方法表转储）

这是本项目第一次**用运行时证据**代替猜测，三个长期悬而未决的问题一次结清：

1. **`com.amap.location.type.location.Location` 确实存在且可钩**——`[Amap]: 已挂钩` 打印出来了。
   它暴露的远不止坐标：`getLatitude/getLongitude/getAccuracy/getAltitude/getBearing/
   getBearingAccuracyDegrees/getProvider/getSource/getPriority/getPoiid/getSemantics/getFloor/
   getLocationUtcTime/getLocationTickTime/getOptAttr*(String,…)`。0.4.9 只钩了坐标与精度，
   0.5.0 补上高度/来源/provider。
2. **`ILocationService` 是接口，钩它等于没钩**。~~真正在高德进程里应答的是 AIDL 客户端代理
   `com.amap.location.api.ILocationService$Stub$Proxy`~~ ——**这条 0.5.1 已被 A13 推翻**：
   全 8 个 dex 里这个接口**根本没有内部类**，229 个含 `$Stub` 的类中只有 3 个名字带 `ocation`，
   且都不带坐标（`com.amap.ams.IAMSLocationService$Stub` 只有车道匹配信息）。它是**普通 Java 接口、
   进程内实现**（6 个混淆类 `bu2/cu2/ev3/fv3/gv3/i81`）。方法表本身是对的（探针转储的确实是这张表）：
   `getLatestLocation()` / `getLatestLocation(int)` / `getLatestOriginalLocation()` /
   `getLatestGnssLocationList()` / `getSatelliteList()` / **`getFixSatelliteCount()`** /
   **`appCanLocation()`** / `isGnssOn()` / `isGnssLocating()` / `isLocating()` / `isLocationOn()` /
   `isLocationDim()` / `fakeNetworkLocation(boolean)` / `fakeRtkLocation(boolean)` /
   `enableUseSystemLocation(boolean)` / `registerSatelliteStatusCallback(IGnssSatelliteListener)` /
   `getOffsetLatlng(double,double)`。⇒ 但这些方法**不需要单独钩**：它们返回的都是 `Location` /
   `AmapLocation`，钩住那两个类就全覆盖了（见 A13）。
3. **这些类是运行时动态加载的**：`base.apk` 的 8 个 dex 里只有 `Lcom/amap/location/type/location/Location;`
   的**引用**、没有 class_def（`sdkh` 那层是插件化加载）。所以"静态扫不到"≠"不存在"——
   **这正是 A7 当初判错的方法原因**（我只查了 `com/amap/api/location` 且只看引用计数）。

顺带结清的第二件事：`自检 gps=30.2638,120.1927 mock=false`（你在 App 里选的杭州）与 `dumpsys` 里的上海
同时存在 ⇒ **App→prefs→sync→模块 这条链路端到端打通**，替换确实咬住了。

**仍未解**：钩完这些之后高德蓝点会不会动，0.5.0 装机才知道。若仍不动，剩下的嫌疑只有
`getSatelliteList()` 返回的 `com.amap.location.type.gnss.Satellite`（0.5.0 只转储它的方法表，还没钩）
和它自己的服务器算路。

### A13 静态全量扫 dex：高德"打开显示杭州、一刷新回广东"的确切原因（0.5.1）

0.5.0 装机后的现象是**冷启动假、刷新回真**——这排除了"完全没钩上"，指向"还有一条读路没盖住"。
这次不靠探针一条一条试，直接把装机版 `.toolchain/amap.apk`（17.01.0.2015 / 170000，8 个 dex、
66 256 个类定义、391 557 个方法、6 037 932 条指令全解码）扫了一遍。结论全部是 dex 字节层面核实过的：

1. **`sdkh.base.type.location.AmapLocation` 是与 `Location` 并行的第二个坐标载体**，它的 super 是
   `java.lang.Object`，**不是** `Location` 的子类；自己有 `latitude/longitude/accuracy/provider/
   source/satelliteCount` 全套 getter/setter，**外加两个 public 字段 `gcjLatitude/gcjLongitude`**。
   而 0.5.0 的 `AmapSdk` 写的是 `LOCATION_TYPES.mapNotNull{…}.firstOrNull()`——列表里 `Location` 在前，
   **`AmapLocation` 从来没被钩过**（26 处读点全在外面）。
2. **`OffsetUtils.getGcjLatlng(AmapLocation)` 用 `iget-wide` 直读那两个 public gcj 字段**，绕开所有
   getter；全 APK 只有这一个外部直读点，唯一调用者是 `bu2.b(AmapLocation) -> Location`（引擎→UI 的转换器）。
   地图蓝点画的正是 GCJ 坐标 ⇒ **这就是刷新回真的那一路**（getter 钩子结构上不可能命中它）。
3. **`Location` 的字段只有它自己碰**（`mLatitude/mLongitude/mProvider/mAccuracy` 各 2 读 2 写，
   全在类内），`protected` 守住了；所以钩 getter 对 `Location` 是够的。子类型只有两个：
   `LocationMatch`（不覆盖任何 getter）与支付宝的 `LBSLocation`（**覆盖了** getLatitude/getLongitude/
   getAccuracy，走自己的私有字段）⇒ 后者也要单独钩，否则支付宝地图那一路漏。
4. **卫星列表走构造函数，不走 setter**：`xm0.e(AmapSatellite) -> Satellite` 读 `AmapSatellite` 的
   10 个 public 字段后一次 `invoke-direct/range` 调 `Satellite.<init>(IIFZFFZZZF)V`。
   ⇒ 想改卫星只能钩构造函数或 getter，钩 setter 会完全落空（本轮未做，见附录 C）。
5. **高德自带一整套反虚拟定位判定**（顺手扫出来的，之前完全不知道）：
   `sdkh.environment.gnss.MockHelper.isMock(Context,Location)` / `isMock(android.location.Location)`
   （反射缓存 `isFromMockProvider` 再 `invoke`）/ `isMockSwitchOn(Context)`，
   `AmapLocationListener.isFilterMock()/setFilterMock(Z)`，
   `ForgeryLabeling.hitForgery(AmapLocation)I` + `NoMoveRecognizer.addGnssAndReturnIsFake` +
   `HighSpeedThenStopRecognizer.isHighSpeedThenStopFake`，`GeoUtils.isLocationCorrect` /
   `AmapLocation.isLocationCorrect`，`LocationNmeaTypeDetector.updateInsState`。
   ⇒ **这些会把我们塞进去的点判为假并丢弃，引擎随后重新交回真点**——症状与"打开是杭州、刷新回广东"一致。
   `MockTrace` 清了系统 `isFromMockProvider`，但那是框架对象；高德这条是它自己进程内的判定，必须自己答。

0.5.1 的改法按"读 / 写 / 判定"三个面，不再只钩 getter：

- 三个载体（`Location`、`AmapLocation`、`LBSLocation`）的 getLatitude/getLongitude/getAccuracy/
  getAltitude/getProvider/getSource 全部改写；
- **写侧**同时钩 `setLatitude/setLongitude/setAccuracy/setProvider/setSource`（+`setGcjLatitude/
  setGcjLongitude`），让字段本身就存假值——复制构造函数、`toString`、日志与 JNI `GetDoubleField`
  读的才是同一个值；
- `getGcjLatlng` 直接返回假 GCJ（`CoordTransform.wgs84ToGcj02`），并在首次读 `AmapLocation` 时把
  `latitude/longitude/gcjLatitude/gcjLongitude` 四个字段一并对齐，两套坐标系是同一个点的两种写法；
- 上面第 5 条那批判定在 `active()`（本包正在改写）期间一律答"真、不是 mock、没伪造"；
- 删掉那条被证伪的死钩子（`ILocationService$Stub$Proxy` 不存在，钩接口方法静默无效果）。

方法名与签名一律从 dex 取，`Class.forName` 失败或方法在本版本不存在就跳过（不报错、不写死混淆名），
所以高德下次改混淆映射不会把整个层带崩；被删掉的 `bu2/cu2/…` 路线正是为了不在这里埋版本地雷。

**证据可复现**：扫描脚本 `.toolchain/amap_ag.py`（查询 A–F）/ `amap_pass2.py`（全量字段访问与调用点
普查）/ `amap_pass3.py`（转换器、监听器、native 回调定向转储）/ `amap_repro_raw.py`（指令片段），
完整报告 `.toolchain/amap_repro.md`。注意 `.toolchain/dexiface.py` 的 `type_list` 读法用了 u32
（规范是 u16），多接口类的接口列表是错的——本轮已绕开它，别拿它的输出当证据。

### A14 装机实测：上面那套读/写/判定没能挪动高德蓝点，挪动它的是致盲（2026-10-27 19:15）

0.5.1-ad12786 装机后按 A13 的判据复测，**结论是 A13 的预测错了**，而且错得有价值：

- 挂钩本身全部命中：`Location 读+写 11 处`、`AmapLocation 读+写 12 处`、`LBSLocation 6 处`、
  `反 mock 判定 9 处`，三个高德进程（主 / `:locationservice` / `:widgetProvider`）的进程内自检都读回
  `30.2638,120.1927 mock=false`。
- **但蓝点仍在连州**。而且"打开显示杭州、刷新回广东"这个描述本身也不准：force-stop 冷启动后直接就是
  连州——之前那次"打开是杭州"是地图还停在上一屏的缓存中心，**又一次"显示值 ≠ 读取值"**。
- 第二轮 pass4（`.toolchain/amap_pass4.py`）解释了为什么：`AmapLocation` **没有任何 `getGcj*` 访问器**，
  `gcjLatitude/gcjLongitude` 的写入点**只有三个构造函数**；`OffsetUtils.getGcjLatlng` 全 APK 只有
  `bu2#b` 一个调用者。也就是说引擎的坐标在 native 侧生成、native 侧消费，Java 的 getter/setter
  钩子结构上碰不到它——`TypeConverter.transLocation` 的 8 个调用点（`GnssProvider$11/$12`、
  `SyslocProvider`、`SysNetworkLocProvider`、`NativeLocationCallback#getLastKnownLocation`）证明
  **系统定位只是它的输入之一**，它更信自己服务器算出来的那一版。

真正起作用的是**致盲输入**：同一版 APK、同一份坐标，只把 `MOCKLOC_BLIND_WIFI/CELL` 打开并重启高德，
蓝点就落在杭州（浙大华家池 / 凤起东路），**点"我的位置"重定位、下拉刷新、放两分钟都还在杭州**。
⇒ 输入被抽空后，引擎落回系统定位那条我们改得动的路。

顺带结清 A0.1 那条错判：当时"把 WiFi 整个关掉蓝点纹丝不动"被读成"它连 `LocationManager` 都不读"。
实际是**基站那一路还在给它的服务器喂输入**——单关一条射频不够，得把 `getScanResults` /
`getAllCellInfo` / `WifiInfo.getBSSID·getSSID·getMacAddress` / `requestCellInfoUpdate` 这组一起抹掉。
代价照旧：**开着致盲，目标 App 自己的 WiFi 相关功能会一起失真**，所以它仍是显式开关，
状态页有「致盲：开/关」按钮，CLI 有 `--blind-wifi/--blind-cell`。

`AmapSdk` 那三层钩子保留：对**引用**高德定位 bean 的宿主（第三方 App 里的高德 SDK）它仍然有效，
只是对高德地图本体的 native 引擎无效——这条边界现在写清楚了。

### A15 四级权限矩阵（2026-10-27 实测，OnePlus Ace 3V / Android 16 / KernelSU + Stellar）

用户提的问题是"低权限能不能做到，这样反检测面就小"。测下来的第一结论是**问题本身要换个问法**：
0、1、2 三档给目标 App 的是**同一个东西**——系统里多一个 mock provider。三档的差别只在"谁去建它"
（App / shell / root）、identity 字段（`10304/com.mockloc` / `1000·2000/android[LocationService]`）、
以及能不能保活，而 identity 目标 App 自己根本读不到。所以"这个 App 用档 1 就行、那个用档 2 就行"
**结构上不存在**：档 0 推不动的 App，档 1、档 2 一定也推不动。真正正交的是三个杠杆：

| 杠杆 | 谁能给 | 效果 | 在目标进程里留痕迹 |
|---|---|---|---|
| ① 系统 provider | 档 0 / 1 / 2 | 全局一个假点，`isFromMockProvider()=true` | 不留 |
| ② 抽空它的输入 | 档 2（按 uid 断网、关射频）或档 3（致盲扫描结果） | 逼它落回① | 不留 |
| ③ 改它进程内的读数 | **只有档 3** | 按 App 分流、`isMock=false` | **留**（栈帧 / maps / classloader / 管理器包名） |

#### 实测结果

| 档 | 怎么验的 | 结果 |
|---|---|---|
| **0 普通 App** | 开发者选项授权 → App 内 `addTestProvider`，模块 `ENABLED=false` | 美团首页仍「连州」、团购卡 `522m/1.2km` 全是真距离；高德、微信此前已测同样不吃 |
| **1 Shizuku/Rish** | `adb shell`（uid 2000，**完全不用 su**）跑 `cmd location providers add-test-provider gps` | **建 provider 成功**，`gps provider [mock]` + `identity=1000/android[LocationService]`，`--mode adb set/off` 全流程走通。**纠正 §2.5/A0 的旧结论**：这台 ROM 上 shell 不能*自授* app-op（`MANAGE_APP_OPS_MODES` 被摘），但**授权已在位时 shell 建 provider 是通的**——旧文档把这两件事混成一句"`--mode adb` 不通" |
| **2 root** | root 建三个 provider + `iptables -I OUTPUT -m owner --uid-owner 10328 -j DROP`，**模块完全关掉** | **高德蓝点落在杭州**（南肖埠社区/凤起东路），点"我的位置"重定位后仍在杭州 ⇒ **高德不需要 LSP 层**，①+② 就够 |
| **3 模块** | 0.5.1 三层钩子全命中 | 微信**只有这档**有效（它读内嵌腾讯 SDK 并看 mock 标志）；高德无效（native 生成、native 消费，见 A14） |

#### 每档还能扩什么（以及扩不了什么）

- **档 0 可扩（都不需要 root）**：注入间隔 15s→**1s**（参考项目一致取值：15s 的"死点"会促使 App 走自己
  那条重定位路）；假点补 `satellites/maxCn0/meanCn0` extras（高德系 SDK 会**直接丢掉**没有卫星信息的假点，
  AnyWhere/AnyDoor 都补）；API 31+ 改用 `ProviderProperties.Builder` 注册（10 参重载在 36 上还在但已弃用）；
  开机自恢复与前台保活；**自检 app-op 是否还在**（重装必清）+ 深链跳开发者选项（`ACTION_APPLICATION_DEVELOPMENT_SETTINGS`，
  开发者选项未开时退到 `ACTION_DEVICE_INFO_SETTINGS`；那个"选择虚拟定位应用"的 picker 没有公开 intent）；
  以及"飞行模式 + 假 GPS"这个档 0 版的②（代价：瓦片/搜索一起没）。
  **档 0 的硬缺口**：卫星面。`GnssStatus` 是系统回调，不进进程就改不了 ⇒ 假 GPS + 0 颗星，看卫星的 App 一眼识破。
- **档 1 比档 0 多出来的**：不用开发者选项、不用每次重装重授权、可脚本化、能 `force-stop` 目标 App 逼它重定位、
  能 `appops set`/`pm revoke`/`settings put`。⚠️ 其中 `pm revoke <pkg> ACCESS_FINE_LOCATION` 看着像②，
  实际无用——revoke 之后它连我们的假点也读不到，只会显示"未定位"。
  **档 1 做不到**：进别人进程、改卫星、擦 `isMock`——这三条就是档 3 的护城河。
  本机没嵌 Shizuku 客户端的原因：Stellar 带的是 Rish（`moe.nb4a` 1.4.2），当前服务未运行，且 13.1.5 的
  `Shizuku.newProcess` 已是包内可见（要走 AIDL 或 user-service）——**没有可验证路径的客户端不盲编**，
  所以这一档现在由 `--mode adb` 承担（已实测可用）。
- **档 2 独有**：按 uid 断网、关射频、开机自恢复。今天证明它加上①就能拿下高德，而这两条都不进目标进程。
- **档 3 独有**：③。代价是痕迹全在目标进程里。

#### 界面跟着这个模型改了什么

- 原「作用域」页改成**「策略」页**：列出**申请了定位权限**的 App（`QUERY_ALL_PACKAGES` + `requestedPermissions`），
  每行带上工具自己知道的结论标记（①/②/③/未测，来自本表），可编辑的只有②（按包致盲）——
  ③仍然只在 LSPosed 里勾一次，不在 App 里收第二遍。
- 免 root 档自检上页：app-op、开发者选项、本 App 定位权限三枚徽章 + 一键跳设置。
- 顶部标题重复修掉（manifest 之前是 `DarkActionBar`，系统栏和自绘 header 各印一次 mockloc）。
- 收藏加**名称+备注**输入与显示，长按从"直接删"改成"改名称/备注、删除"（`Place.note` 早就在数据结构里，UI 一直没给口）。
- 配置面：`MOCKLOC_BLIND_WIFI/CELL` 从布尔改成三态 `false | true | 逗号包名`，
  模块按包安装致盲钩子（`Config.blindWifiFor(pkg)`），渲染器与 `ConfigCheck` 同步覆盖（48 项断言）。

#### 与定位层无关的那一面（顺手记下来，免得以后又去找）

档 2 断网测高德时，抖音评论区显示的「3小时前 · **山西**」纹丝不动——**IP 属地是服务端按出口 IP 算的**，
不在定位这条面上。同类还有账号注册地、收货地址、基站/WiFi 位置数据库本身（在厂商服务器上）。
这三样任何一档权限都改不了。

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

键：`MOCKLOC_ENABLED / GLOBAL / PROVIDERS / LAT / LNG / ACC / RADIUS / INTERVAL / TARGETS / LABEL
/ GNSS / BLIND_WIFI / BLIND_CELL`。
后三个是档 3 的反检测开关，**只在进程 attach 时读一次**（挂钩本身是按进程装的，改完要把目标 App 重启才生效）；
`GNSS` 默认开，`BLIND_WIFI/BLIND_CELL` 默认关——致盲会真的破坏目标 App 自己的 WiFi/基站功能，不能默认打开。
缺键按默认值处理，所以旧配置在新模块上行为不变（`tools/kotlin/ConfigCheck.kt` 把这条契约钉住了）。
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
| `app/.../ModuleEntry.kt` | libxposed 入口：只负责**坐标本身**（`getLatitude/getLongitude` + `getLastKnownLocation`）|
| `app/.../HookKit.kt` | 挂钩管线：按名查类/方法、批量安装、`Chain.withArg`；外加 `Diagnostics` 失败台账 |
| `app/.../MockTrace.kt` | 抹掉系统测试 provider 留下的痕迹（mock 位、extras、`Settings.Secure`、provider 列表、Parcel 路径）|
| `app/.../Sensors.kt` | 一致性层：伪 `GnssStatus`（含每秒回调）、可选致盲 WiFi/基站 |
| `app/.../Probe.kt` | `--probe` 打开时的定位 API 调用记录（只记不改值），用来把"该钩什么"变成读日志 |
| `app/.../Nmea.kt` | 纯函数 NMEA 句子生成（`$`+语句号+逗号字段+`*`校验和），可在 JVM 上验收 |
| `app/.../GnssStream.kt` | GNSS 原始测量 + NMEA 注入（全反射造对象，字段自洽见 A9）|
| `app/.../Sky.kt`（在 GnssStream.kt 内） | 唯一星历表：GnssStatus / 测量流 / NMEA 三处共用，含斜距几何 |
| `app/.../AmapSdk.kt` | 高德自家定位栈那一层（`com.amap.location.type.location.Location` getter + 候选类方法表探针）|
| `app/.../ModuleBridge.kt` | App 侧唯一的配置渲染器；PC `sync` 与 root 模块只搬运不重写 |
| `app/.../Ui.kt` | 卡片与状态徽章的公共样式件（纯 View）|
| `app/.../TencentSdk.kt` | 微信内嵌腾讯定位 SDK 那一层的替换（它不吃 `LocationManager`，见 A0.1）：`TencentLocationImpl` 的经纬度/精度/`isMock` + 地址文字 |
| `app/.../SelfTest.kt` | 在**被 hook 的进程里**读回一次 LKL/providers/`mock_location` 并打日志——模块勾不进自己的作用域，这是唯一能证明"改写咬住了"的仪器 |
| `app/.../MainActivity.kt` | 四页 View：搜索 / 地图点选 / 收藏 / 状态（`selfCheck` + 各 provider 回读） |
| `app/.../CoordTransform.kt` | `coords.py` 的 Kotlin 移植，与 Python 版对同一批锚点数值一致（477.87 m） |
| `app/.../AmapSearch.kt` | 高德 REST（`HttpURLConnection` + `org.json`，零三方依赖） |
| `app/.../AmapTiles.kt` | 高德瓦片源（GCJ-02 地理参考的 Web Mercator） |
| `app/.../Places.kt` | 收藏点，JSON 存私有 prefs（不落 world-readable 镜像） |
| `tools/classdump.py` | 解析 `.class` 拿真实方法签名（本机只有 JRE，没 `javap`） |
| `tools/kotlin/CoordCheck.kt` | 可运行的 Kotlin 数值验收，不进 APK |
| `tools/kotlin/NmeaCheck.kt` | NMEA 框架/校验和/坐标往返 + 卫星几何量级的 JVM 验收 |
| `tools/kotlin/ConfigCheck.kt` | 在 JVM 上真跑 `Config.refresh()`，吃 CLI `--dry-run` 原样输出的配置体 |

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
FakeLoc/XposedFakeLocation 都有同样的取舍，并用"每个字段单独开关"来缓解；本项目把开关放在配置里
（CLI `--gnss / --blind-wifi / --blind-cell`，App 状态页只读显示当前值）。

### B4. 档 3 的反检测面（照 AnyWhere 的实机清单补，逐条对 API 36 重核）

`refs/AnyWhere/app/.../HideMockHook.java` 是唯一一份"在别人手机上跑通过"的检测面清单，值得抄；
但它写于旧版本 AOSP，**照抄会在 Android 16 上静默失效**，所以每条都用本地 `android.jar`（API 36）重核过：

| 检测面 | 这一版的做法 | 与 AnyWhere 的差异（核实依据） |
|---|---|---|
| `isMock()` / `isFromMockProvider()` | 读侧直接返回 false | 一致 |
| `setMock()` / `setIsFromMockProvider()` | 写侧把参数强制成 false（`Chain.withArg`），从源头堵 | `setIsFromMockProvider` 已从 API 36 公开面移除，按名查不到时**记录**而不是不提 |
| `getExtras()` 里的 `mockLocation` 键 | 在返回的 Bundle 上删键 | AnyWhere 用 `Location.removeExtra`；**API 36 的 `Location` 已经没有 putExtra/removeExtra 了**，只有 `Bundle.remove`（API 30） |
| Parcel 反序列化绕过读侧 hook | `readFromParcel` 之后反射清 `mMock`/`mIsFromMockProvider` 与 `mFieldsMask` 的 mock 位 | AnyWhere 这里**空 catch**，且没带 `HiddenApiBypass` —— 一旦被 greylist 挡住就是永久静默失败。本模块先 `addHiddenApiExemptions("")`，失败全部进 `Diagnostics` |
| `getCurrentLocation(…, Consumer)` | 包一层 Consumer，交付前清位 | `getLastKnownLocation` 不重复处理：B3 那条路径返回的是新建对象，mock 位天生 false |
| `Settings.Secure.getString("mock_location")` | 强制 `"0"` | 额外把 `getInt` 两个重载也盖住：`getInt` 内部走 `getString` 是实现细节而不是契约，不赌它 |
| `getProviders / getAllProviders / isProviderEnabled` | 只留 `gps/network/passive/fused` | 补 `getProviders(Criteria, boolean)` 这个重载 |
| `GnssStatus` 卫星视图 | 12 个 getter 全改（含 `getBasebandCn0DbHz`）| AnyWhere 钩的 `hasEphemeris/hasAlmanac` **在 API 36 上不存在**，真名是 `hasEphemerisData/hasAlmanacData`；钩不到就是零命中且不报错 |
| 造伪 `GnssStatus` 并每秒回调 | `Builder().addSatellite(...).build()` | AnyWhere 反射的是旧 7 参 `Builder.add(...)`；API 36 是 `addSatellite` **12 参且 constellationType 在前**。按 `parameterCount` 分 8/10/12 三种形状取值顺序 |
| 吞掉 `registerGnssStatusCallback` | 只有**造得出** dummy 才吞；造不出就保留真实注册 | AnyWhere 无条件吞 + 只钩 `(Callback)` 与 `(Callback, Handler)`；API 36 还有 `(Executor, Callback)`，漏了它那条路径就完全没数据 |
| WiFi / 基站致盲 | 默认**关**，`--blind-wifi/--blind-cell` 才开 | 这两条会真的破坏目标 App 自己的 WiFi 列表/基站信息，不能当默认值 |
| 致盲覆盖面（A0.1 实测后补） | `WifiInfo.getBSSID/getSSID/getMacAddress` 抹成空串；`requestCellInfoUpdate` 用 `CellInfoCallback.onCellInfo(空表)` 递交 | 第一版只钩 `getScanResults/getAllCellInfo`，留着两个洞：已连 AP 的 BSSID 单独就够服务器算位置（走 `getConnectionInfo`→`WifiInfo`），Android 12+ 的基站读取已改成回调式。证据是 `appops get --uid` 里两个 App 的 `WIFI_SCAN` 计数，以及 LocationSpoofer 自己报的钩子清单。**抹标识位而不是返回 null**：回调要求有对象回来 |
| API 探针（`--probe` / `Probe.kt`） | 只记不改值：目标 App 调了哪些定位 API、第几次、从它自己哪个类（取跳过框架/VM/本模块后的第一个栈帧） | 猜 SDK 内部已经让本项目错过两次（A7、A0.1）。这一层把"该钩什么"变成读日志 |

`Diagnostics` 是这套东西的"仪表盘"：任何类找不到、方法挂不上、反射被挡、重载数量对不上，都会记一条并
立刻 `module.log` 到 logcat；本进程的记录还会显示在 App 状态页。**故意不做**AnyWhere 那种
`catch (Throwable ignored) {}` —— 静默失败的 hook 在新系统上和正常工作的 hook 长得一模一样，
这是最难查的一类 bug。

## 附录 C 待验证清单（必须上真机）

按优先级，每条都是"我写了但我不能在本机证明"的东西：

1. ~~依赖改到 `hiddenapibypass:6.1` 之后 CI 能否转绿~~ **已验：转绿**，装到机上的 `0.4.2-143edae` 就是证据。
2. **KernelSU 的 `su -c` 语义与 SELinux 上下文**：`cmd location` / `appops` 是否能在 KernelSU 模块的
   `service.sh` 里跑通（Magisk 语境下可以，KernelSU 的域不同）。
3. **KernelSU/APatch 是否接受本模块 zip 格式**（`update-binary` 取自 Magisk master）。
4. ~~App 进程能否真读到 `/data/local/tmp/mockloc.conf`~~ **已验：能，而且不需要 `chcon`**（本机 shell 里也没有
   `chcon`）。正向对照是微信自己的进程（非 debuggable）读到了 `shell_data_file` 标签的文件、`gnss` 开关跟着文件变，见 A0.1。
   留一个尾巴：`rootmodule/mockloc-lib.sh` 的 `publish()` 里那句 `chcon ... 2>/dev/null` 在这类 ROM 上永远静默失败，
   要不要保留待你定。
5. ~~LSPosed 加载模块后 `selfCheck` 是否变 true~~ **模块确实加载了**（logcat 里微信/高德各 3 个进程 `attached to`，
   见 A0.1），但 `selfCheck` 这条判据要求把 **com.mockloc 自己**也勾进作用域（我们故意不 hook 自己进程，
   所以状态页那行 `未加载` 只代表"本 App 不在作用域"）。要不要勾由你定：勾上之后状态页读到的坐标就是被自己改写过的。
6. 真 key 下高德地理编码的正向返回（当前只验过 `10001` 错误路径）。
7. `awk` 抖动种子的时间粒度是秒；`MOCKLOC_INTERVAL` 不要设到 1s 以下。
8. 你手上这个 LSPosed 构建对外报的 libxposed API 版本号（决定 `minApiVersion=100` 够不够），
   以及它的 `manager.apk` 签名是否与历史可信版本一致（A6 里那两个自证动作）。
9. `doctor` 的档 1 探针：确认 shell uid 单独授权 app-op 后 `cmd location` 真的不报 SecurityException
   ——这是"无 root 也能全局"这整条结论的唯一直接证据。
   **已实测：本机不通**（ColorOS 摘了 `MANAGE_APP_OPS_MODES`），见 A0；免 root 档改走 App 内 provider。
10. ~~高德瓦片在真机上出不出图~~ **已验：出图**（上海全图、中文注记、地铁线），点选双坐标与收藏链路也走通，
    偏移量级与坐标层实测一致（约 450 m）。
11. Android 16 上从 Activity 前台 `startForegroundService` + `foregroundServiceType=location` 是否放行；
    未授 `POST_NOTIFICATIONS` 时通知不显示、服务是否仍在跑。
12. 真 key 下 App 内搜索的正向返回（REST 层目前只验过 `10001` 错误路径）。
13. **反射清 `mMock` 到底成没成**：`HiddenApiBypass 未生效` 这条**没打印** ⇒ 豁免成功；
    `readFromParcel` 找不到 ⇒ 那个入口在 Android 16 上不存在，所以清字段这条路径**当前根本不会被触发**
    （`clearMockField` 只在 Parcel/Consumer 交付时调用）。剩下的真问题：`getCurrentLocation(…, Consumer)`
    有没有被目标 App 用过——探针模式跑一次就知道。若三条都没打印才算通过，目前状态是"两条没打印、一条已过期"。
14. ~~`GnssStatus.Builder.addSatellite` 的参数形状~~ **已验：12 参形状对上了**（`notes=1` 里只有 readFromParcel，
    没有 `addSatellite() 有 N 个参数` 也没有"造不出 GnssStatus"）。
15. **吞掉 GNSS 注册会不会让 App 判定"GPS 不可用"**：现在只在造得出 dummy 时才吞。若目标 App 因此
    一直收不到回调（dummy 构造失败但注册被别的分支吞了），表现是"定位转圈"——关掉 `--no-gnss` 对比即可确认。
16. ~~致盲 WiFi/基站的副作用与效果~~ **v0.4.3 补齐后已复测：对高德地图有效**（A14：只开致盲 + 重启，
    蓝点落在注入点并且刷新不回真）。第一版无效的原因不是"钩子漏了"，而是**只抹了一条射频**——基站那一路
    还在给它的服务器喂输入。副作用面仍未系统评估（目标 App 自己的联网状态/附近设备会一起失真）。
17. ~~`setIsFromMockProvider` 是否还在 API 36 的框架里~~ **在**：那次运行 `notes=1` 只报了 readFromParcel，
    说明这个 setter 挂上了（SDK 公开面删了它，运行时类还在）。
18. ~~高德地图到底认不认框架层 mock~~ **按正确方法测完：不认，但原因比"不读"更具体**（档1/档2 mock 生效期间
    点它的"我的位置"，系统确实把 mock 定位投给了它 `locations=1`，蓝点仍在连州）。A14 补上机制：系统定位只是
    它多路输入里权重最低的一路，它更信自己服务器用 WiFi/基站算出来的那一版 ⇒ **要么把输入抽空让它落回系统那路
    （已验有效），要么改它自己的答案（native 侧，Java 钩子做不到）**。你之前"高德认开发者模式虚拟定位"的印象
    在这台机 + 这个版本上不成立。
19. ~~v0.4.3 新代码全部未上真机~~ 部分已验：`WifiInfo` 抹标识位、`requestCellInfoUpdate`、`Probe.kt` 均已上机，探针录到微信冷启动只读 `Settings.Secure`（定位 SDK 用到才起）。
    本地只有类型检查与 ConfigCheck（29 项）过。
20. **探针要跑一次才知道下一步钩什么**：`--probe` 打开后重启目标 App，`adb logcat | grep Probe` 会列出
    它调过哪些定位 API 与调用方类名。两种结果要分开看：只出现 `WiFi输入/WiFi身份` ⇒ 致盲路线对，继续补覆盖面；
    若出现 `定位读取` 却仍显示真实位置 ⇒ 它读了 `Location` 但**主动丢弃**（那就是检测逻辑，得看 `mock痕迹` 那组）。
21. **档 2 的开机存活仍未验**：`--mode su` 这条路今天验通了，但那是 root shell 手动跑的；
    `mockloc-root.zip` 刷进 KernelSU、`service.sh` 开机自启这条还没走过（C2/C3 一并挂着）。
    现在 Shell 有 root，可以直接 `su -c '/data/adb/ksud module install …'`，不必开 KernelSU 管理器点界面。

22. **`sync` 与 `sync_app_prefs` 两条搬运路径都还没在机上跑过**：前者要 Shell root，后者要刷 root 模块。
    判据：App 里选一个点 → 跑 `sync` → 状态页"App 选点与模块配置不一致"警告应当消失。
23. ~~AmapSdk 候选类名未验证~~ **已验（A12）并被 A13 推翻一半**：`com.amap.location.type.location.Location`
    挂钩成功是真的，但 `ILocationService$Stub$Proxy` **这个类根本不存在**（全 dex 扫过），0.5.0 那条钩子是静默空转；
    `AmapLocation` 反倒一直没被钩。0.5.1 按 A13 重写。
24. ~~0.5.1 的高德复测判据~~ **已测，且判据失败→结论已改（A14）**：读/写/判定三层钩子全部命中
    （`Location 11 / AmapLocation 12 / LBSLocation 6 / 反 mock 判定 9`，三进程自检都读回假点），
    **但高德地图本体的蓝点不动**——它的坐标在 native 侧生成与消费（pass4：`AmapLocation` 没有 `getGcj*`
    访问器，gcj 字段只在构造函数里写）。真正挪动它的是**抽空输入**：只开致盲 WiFi+基站并重启高德，蓝点就落在
    杭州，重定位/下拉刷新/放两分钟都不回真。
    **因果对照也已做（A15）**：模块完全关掉（`ENABLED=false`），只用 root 建 provider + 按 uid 掐高德网络
    ⇒ 蓝点同样落在杭州、重定位不回真 ⇒ **高德根本不需要 LSP 层**，暴露面可以从"微信+高德"缩到"只有微信"。
    仍未验：换城市/开飞行模式后是否仍稳；致盲对微信这类③档 App 有无副作用（微信现在没开致盲）。
25. **高德的卫星列表还没钩**：已证它是**构造函数**交付（`xm0.e` 一次 `invoke-direct/range` 调
    `Satellite.<init>(IIFZFFZZZF)V`），钩 setter 会完全落空；`getFixSatelliteCount()` 在混淆实现类上，
    名字随版本变，故意不写死。做之前先确认高德界面有没有露卫星数，别为看不见的字段冒版本兼容风险。
26. ~~`scope` 子命令与"作用域只在 LSPosed 里勾"改动尚未上机~~ **已上机**：`scope` 实测能列出微信 4 个进程、
    高德 3 个进程的 `attached to` 与挂钩小结；作用域页已改成「策略」页（见 A15）。
    **注意 logcat 只有 256 KiB**，探针开着时 `attached to` 会被挤掉——`scope` 因此改成读全缓冲区并顺手打挂钩小结。
27. **0.6.0 未装机**：档 0 的三处改动（间隔 15s→1s、假点补 `satellites/maxCn0/meanCn0`、
    `ProviderProperties.Builder` 注册）与「策略」页、收藏备注、NoActionBar 都要装机才算验过。
28. **美团的②/③还没测**：它不在模块作用域，档 0 实测无效。要判它到底要哪一层，得先在 LSPosed 里把它勾进作用域
    （或者用档 2 的按 uid 断网先试②）。抖音同理。
29. **档 1 没做 App 内 Shizuku 客户端**：本机 Shizuku 兼容层是 Stellar 带的 Rish（`moe.nb4a` 1.4.2），
   当前服务未运行，且 `dev.rikka.shizuku:api:13.1.5` 的 `newProcess` 已是包内可见（要走 AIDL 或 user-service）。
   **没有可验证路径的客户端不盲编**，这一档现在由 `--mode adb` 承担（已实测）。要接真客户端，先让用户把 Rish 起来。
30. **配置字段必须进渲染器**：`MOCKLOC_PROBE` 曾被 `ModuleBridge.render` 漏掉，于是每次 `sync` 都把探针静默关掉；
    致盲两个键也一度被写死成 `false`（0.5.2 修）。凡是「配置由渲染器唯一产生」的字段都要在 `ConfigCheck`
    的往返断言里出现（现在 64 项）。
31. **v0.7.0 的装机验收判据**（阶段一全部代码已完成，以下逐条待真机）：
    a. **开机恢复**：注入 → 重启 → 不碰手机，1 分钟内 `dumpsys location` 重新出现 `[mock]`；
       若 Android 16 拦 FGS 自启，应出现可点的「恢复注入」通知，点后 provider 回来。
    b. **自愈巡检**：注入后 `su -c 'cmd location providers remove-test-provider gps'` 手动拆掉
       一个 provider，≤10s 内日志出现自愈且读回恢复；连拆 5 次后巡检间隔变为 10s（防抖可观测）。
    c. **通知操作**：下拉通知点「暂停」→ 目标 App 立刻回到真实位置、通知文案变「已暂停」；
       点「继续」→ 回到注入点。
    d. **RootInjector**：KernelSU 管理器给 mockloc 授权后，App 里选点不接电脑：`dumpsys location`
       出现 `identity=0/android[LocationService]` 的三个 `[mock]`，且 `/data/adb/mockloc/config.prop`
       与 `/data/local/tmp/mockloc.conf` 内容一致；App 杀掉重启后模块循环接管（若已刷模块）。
    e. **海拔**：注入高海拔城市（拉萨/昆明）后 `dumpsys location` 的 fix altitude ≈ 真实地形值
       （±50m）；断网时回退 20m 且注入不失败。
    f. **LOOP_INTERVAL**：conf 写 `MOCKLOC_LOOP_INTERVAL=1` 后，模块循环每秒重注入（logcat 时间戳
       间隔 ~1s）；删掉该键回退 `MOCKLOC_INTERVAL`。
32. **v0.8.0 的装机验收判据**（阶段二/三/四代码完成，以下逐条待真机）：
    a. **路线三档推进**：路线页点 3 个航点 → 步行速度开始 → 高德蓝点沿路线连续移动、速度栏非 0；
       root 模式下 `dumpsys location` 每 tick 的坐标与 App 内 `Config.currentLat` 同一秒一致（同一锚点）。
    b. **GPX**：从 GoGoGo 导出一份 GPX 导入成功（航点数一致）；导出的 GPX 能被 gps-locator 读回。
    c. **per-App 双点**：微信独立点设北京、全局点上海 → LSPosed 作用域内微信 SelfTest 读回北京且
       `mock=false`，未列出的 App 读回上海。
    d. **NETCUT**：conf 填 `MOCKLOC_NETCUT=com.autonavi.minimap` → `appops get --uid` 的 WIFI_SCAN
       停止增长、高德蓝点落注入点；`iptables -S OUTPUT | grep mockloc` 可见规则；卸载模块后网络恢复。
    e. **栈清洗**：hook 进程内 `new Throwable().getStackTrace()` 无 libxposed/lsposed 帧（SelfTest 里
       加打一次即可核）。
    f. **泄露反推**：probe 开 → 重启目标 App → logcat 的 SelfTest 行给出 AP/基站计数与"致盲面闭合/
       有真实射频证据泄露"判定；致盲开关切换后判定跟着变。
    g. **传感器冻结**：计步类 App 读数冻结在首次值；加速度模长 ≈9.81；步数事件不再触发。
    h. **WiFi 伪造**：扫出 4 个确定性 AP（02: MAC、RSSI -48 起衰减）；换注入点后 AP 集随点变；连续
       两次扫描结果相同（确定性）。
    i. **GMS**：带 GMS 的机器上 `FusedLocationProviderClient.getLastLocation` 读回注入点；无 GMS 机器
       日志零新增。
    j. **暗色**：系统深色下五页逐页可读（卡片/正文/徽章/状态条），无白底残留。
33. **v0.8.1 的装机验收判据**（闪退修复 + 性能优化，真机 478e1f12/PJF110/Android 16 实测）：
    a. **闪退**：全新安装（无海拔缓存）后第一次点「注入」不再崩——崩溃栈为
       `Elevation.cached → FileNotFoundException → thread 未捕获`（0.8.0 实测复现），
       修复后同路径应出 toast + provider 起来（`dumpsys location` 三 provider `[mock]`）。
    b. **无权限路径**：撤掉定位权限后点注入 → toast 拒绝 + 普通通知提示，不崩（旧路径会在
       FGS startForeground 被吞后 5s 以 "did not call startForeground" 二次崩）。
    c. **冷启动**：`am start -W` TotalTime；0.8.0 基线 491ms（两个 MapView），惰性路线页后应下降。
    d. **策略页**：进页/过滤键入的帧耗时（gfxinfo 95th）；0.8.0 基线 950ms/帧（UI 线程枚举 593 包
       + 每键重建全部行），后台加载 + ListView 虚拟化后应到两位数 ms 量级；计数行显示加载耗时。
    e. **路线页惰性**：冷启动不建第二个地图（首次点「路线」tab 才建），已存路线回填。
    **复测结果（0.8.1-40da088，同机 2026-10-29 实测，全过）**：
    a ✓ 注入北京 39.9087,116.3975：无崩溃、gps/network/fused 三 provider `[mock]`、海拔缓存
    `elev_19953_58195` 新增（网络取值 + 落盘正常）；
    b ✓ 无权限路径走 toast 拒绝（未真机复测撤权分支——权限由 install -r 保留，代码路径已就位）；
    c ✓ 冷启动 TotalTime 484–497ms，与 0.8.0 基线 491ms 持平——**惰性化的实测收益不在启动时钟上**
    （第二个 MapView 的同步构造只有几十 ms），收益在路线页不打开就不起瓦片栈；诚实记录，不吹；
    d ✓ 策略页：切换帧 950ms → **31ms**（95th）；过滤键入 **30ms**；列表滚动 15ms；计数行显示
    "共 191 个可选项 · 列表加载 314ms"（后台完成，UI 零阻塞）；
    e ✓ 路线页首次点 tab 才构建，正常渲染（速度/开始/停止/GPX 四件全在）。
    **真机额外抓到并修掉的两个问题**：
    f. **策略页二次进入崩溃**（`ClassCastException: LinearLayout$LayoutParams cannot be cast to
    AbsListView$LayoutParams`，`ListView.removeUnusedFixedViews`）——ListView header 不能带预置
    LayoutParams，首次进入碰不到、重进 tab 才崩，本地 typecheck 结构上抓不到；修法：header 包一层
    裸 FrameLayout 让 ListView 自己生成 params。
    g. **force-stop 后注入不恢复**：test provider 随宿主进程回收，重开 App 时标记说在跑、系统里
    没了 mock——`refreshStatus` 增加 `maybeAutoResume`（10s 节流，暂停/停止有独立 prefs 标记不打架），
    复测 force-stop → 重开 6s 内三 provider 回来。
    **测试方法教训（又犯第三次）**：键盘弹出/列表滚动都会移动控件——**每次点击前必须重新
    `uiautomator dump` 取坐标**；一次点到「去开发者选项」跳进系统设置、一次 tap 坐标系漂移，
    白跑两轮。
34. **v0.8.2 的装机验收判据**（App 内一键同步，消灭"配置不一致要 PC 刷新"）：
    a. 真机诊断：conf 停在 PC 时代旧点（杭州）、App 选点上海、`/data/local/tmp/mockloc.in` 里还躺着
       10-27 的 `ENABLED=false` 僵尸收件箱、com.mockloc 未获 KernelSU su 授权——四件事叠加成
       "App 注了、模块不跟、只能 PC 救"。
    b. **App 内「同步到模块」**（状态页操作行）：force 重探 su（弹 KernelSU 授权框就点允许，等待从
       8s 放宽到 15s）→ `RootInjector.publishConfig` 直写 conf → 1.2s 后状态条回绿；失败弹引导对话框
       （①授权 ②刷 rootmodule ③PC）。
    c. **僵尸收件箱修复**（rootmodule/mockloc-lib.sh）：`sync_inbox` 改一次性语义（应用即删），
       `publish`/`cfg_get` 一律读 CFG（唯一权威）；`sync_app_prefs` 的比较基准从 PUB（每 tick 被
       publish 重写）改回 CFG——修掉"陈旧 App prefs 反复盖掉新鲜 PC 写入"的第二个竞态。
    d. 验收：注入后状态条无黄色不一致警告；`cat /data/local/tmp/mockloc.conf` 与 App 选点一致；
       微信（档3）读回新点；全程不碰 PC。


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
  状态页文案也改了：原来一行"注入: 未生效"把「LSPosed 模块未加载」和「定位没注入」混为一谈，
  现在拆成 `系统注入` / `无痕模块` 两行，并直接列出四个 provider 的读回值。
  ~~当时据"高德地图首页仍显示真实城市"断定框架层 mock 覆盖不了高德——**这条结论已撤回**：
  首页城市是缓存/定位历史，不是一次新的定位请求，用它当证据等于没测。正确测法（主动触发一次新定位、
  看返回坐标而不是看文案）记在 C 里，尚未执行。按 DEX 证据（A7）仍然成立的是：高德地图不吃公开 SDK，
  要覆盖它得走它自己的引擎或饿死它的网络定位输入。
- **v0.4.2**（2026-10-26）按 AnyWhere 的实机清单把档 3 的反检测面补完（见 B4），全部对 API 36 重核：
  ① 拆层：`ModuleEntry` 只管坐标，`MockTrace` 管痕迹，`Sensors` 管一致性，`HookKit` 管管线——没有一处 hook 被装两遍。
  ② **不静默吞异常**：新增 `Diagnostics`，类找不到/方法挂不上/重载数对不上/反射被挡一律记账并立刻 `module.log`；
  AnyWhere 那种空 catch 在它自己的新版系统上到底是死是活无人知晓，这里不留同样的洞。
  ③ 核实到 AnyWhere 清单在 Android 16 上会**静默失效**的四处：`hasEphemeris/hasAlmanac` 真名是
  `*Data`；`Location.putExtra/removeExtra` 已从公开面删除；`GnssStatus.Builder.add(7 参)` 变成
  `addSatellite(12 参，constellationType 在前)`；`registerGnssStatusCallback` 还有 `(Executor, Callback)` 重载。
  ④ 新依赖 `org.lsposed.hiddenapibypass:hiddenapibypass:**6.1**`（`implementation`，要在目标进程里跑）——
  清 `mMock` 字段需要它，AnyWhere 没带。**6.2 不能用**：它的 AAR 写 `minCompileSdk=37`，AGP 会拿我们的
  `compileSdk=36` 比，直接在建 APK 时报失败，而这一步本地类型检查看不见（详见 §3 的 AAR 元数据条）。
  ⑤ 配置契约加 `GNSS / BLIND_WIFI / BLIND_CELL` 三键（CLI `--gnss/--no-gnss`、`--blind-wifi`、`--blind-cell`，
  菜单里只在填了包名时多问一句）；状态页新增模块配置回读与 `Diagnostics` 记录。
  ⑥ `tools/kotlin/ConfigCheck.kt`：在 JVM 上真跑 `Config.refresh()` 吃 CLI `--dry-run` 的原样输出，
  26 项断言全过，其中两项第一次就抓到东西（1s 统计缓存让"删文件即停"要等一个 TTL 周期）。
  ⑦ 全部 Kotlin 源码本地类型检查（`kotlinc` + API 36 `android.jar` + libxposed 102 + osmdroid + HAB）零 error。
- **v0.4.3**（2026-10-26）真机把档 2、档 3 第一次跑通（A0.1），据此补致盲覆盖面并加探针：
  `WifiInfo.getBSSID/getSSID/getMacAddress` 抹成空串、`requestCellInfoUpdate` 递交空基站表（第一版只钩
  `getScanResults/getAllCellInfo`，被 `appops` 的 `WIFI_SCAN` 计数当场证伪为不够用）；新增 `Probe.kt` +
  `MOCKLOC_PROBE`/`--probe`，只记不改值地打印目标 App 调了哪些定位 API、从它自己哪个类调的。
  状态页加 `API 探针` 一行；`ConfigCheck` 长到 29 项断言。本地类型检查又抓到一个真错
  （`firstOrNull { it is Executor }` 不会智能转型，必须 `as? Executor`）。
  ⑧ 逆向 LocationSpoofer 之后补的两件事（见 A8）：`SelfTest.kt`（模块勾不进自己的作用域，只能在被 hook
  的进程里自读自证）与 `AppOpsManager.{checkOperation,noteOperation,startOperation,…}` 的
  `android:mock_location` 放行——只钩 `Settings.Secure` 会漏掉直接问 ops 服务的那条路。
- **v0.4.4**（2026-10-26）修 0.4.3 上机自报的三处仪器故障（A0.1 末表）：`SelfTest` 改为主线程延迟重试
  （`onPackageReady` 时 `currentApplication()` 还是 null）、AppOps 钩子换成 classdump 出来的真实方法名
  （`checkOp` 家族，`checkOperation` 那组这版运行时没有）、探针调用方过滤补 hook 框架帧并一次给 3 帧。
  判别性测试就位：系统 mock 留上海、模块配置指北京，两边值不同，`自检(进程内读回)` 一行即可判死改写有没有咬住。
- **v0.4.5**（2026-10-26）判别性测试跑完并**判死档 3 生效**（A0.1）：高德 3 个进程 + 微信 4 个进程读回全部
  是配置的北京且 `mock=false`，而系统侧仍是上海 `mock=true`。天气界面没变不构成反证（显示值来自存量城市，
  那次下拉刷新探针一条读取都没录到）——教训记成"仪器要放在读回层，不是放在某个 App 的界面"。
  据此确认微信的位置来自内嵌腾讯 SDK，新增 `TencentSdk.kt` 补那一层的替换。
- **v0.4.6**（2026-10-26）`TencentSdk` 第一版落空并给出正确形状：微信内嵌的是 **`com.tencent.map.geolocation.sapp.*`**
  且**没有 `TencentLocationImpl`**（A7 那张表的"子串计数"被更正为"不等于类定义"）。改成只依赖接口的接管
  （钩 `TencentLocationManager` 所有带 listener 参数的方法 + `Proxy` 换掉回调里的 `TencentLocation`），
  方法名与签名都不再需要猜；SDK 不存在时安静跳过。类型检查抓到 `String.ifEmpty` 与 `Any?` 转发两处类型错。
- **v0.4.7**（2026-10-26）按 A9 的取证加 **GNSS 原始测量 + NMEA 注入层**（`GnssStream.kt` / `Sky`）：
  全反射造 `GnssClock`/`GnssMeasurement`/`GnssMeasurementsEvent.Builder`（AOSP 里是 public-but-@hide），
  一份星历供 `GnssStatus`/测量流/NMEA 三处共用，斜距由地球半径+GPS 轨道半径按高度角算出，
  时钟偏置固定、多普勒与 ADR 与"静止"自洽，NMEA 五句带真 XOR 校验和；造不出事件就保留真实注册并记账。
  同时把 `Sensors` 的卫星数值改成从 `Sky` 取（原来各处按 index 现算，与测量流会不一致）。
  另加回归结论表 A10 与技术复盘附录 E。
- **v0.4.8**（2026-10-26）0.4.7 的 GNSS 层上机自报三条错并修完（setter 真名、`addNmeaListener` 而非
  `register*`、NMEA 的 `*` 位置）；句子生成抽成纯对象 `Nmea` + `tools/kotlin/NmeaCheck.kt`（16 项断言，
  含坐标往返与校验和自验，全过）。另修：App 选点与模块配置是两条断开的通道（见 C19）。

## 附录 E 技术复盘（2026-10-26）

### 采用的技术路径

三档分层，各自独立可验：**档 1** 应用内 `addTestProvider`（app-op 由开发者选项授，免 root）；
**档 2** root 建系统测试 provider（`cmd location`，开机自恢复交给 KernelSU 模块）；**档 3** libxposed
在被 hook 进程内替换坐标 + 抹痕迹 + 补一致性证据。档 3 再按"目标 App 从哪读"逐层下沉：
`Location` getter → `LocationManager` 拉取口 → mock 痕迹（标志位/extras/Settings/AppOps）→
`GnssStatus` → 腾讯定位 SDK → GNSS 原始测量与 NMEA。

**关键判断是"每一层都要有能自证的仪器"**：`Diagnostics` 台账（失败必须可见）、`SelfTest`
（在被 hook 的进程里读回一次）、`Probe`（只记不改值地打印目标 App 调了什么）、
`ConfigCheck`（在 JVM 上吃 CLI 的原样输出）。没有这四样，本项目会一直在"看起来生效"和"真的生效"之间打转。

### 借鉴的开源逻辑（只取思路，注明来源；LocationSpoofer 是 GPL-3.0，绝不搬代码）

- **AnyWhere**（`cxOrz/AnyWhere`，`HideMockHook.java`）：反检测清单的起点——标志位读写两端都堵、
  extras 删键、`Settings.Secure`、provider 列表清理、GnssStatus 伪造并吞注册、WiFi/基站致盲。
  它的**教训**同样重要：空 catch 让 hook 在新系统上静默失效（`readFromParcel` 就是），照抄等于继承一个未知。
- **FakeLoc / XposedFakeLocation**（`noobexon1/XposedFakeLocation` 等）：按 App 分流 + 公开地图 SDK
  （高德/百度/腾讯公开接口）的 hook 时机；以及"AGP 8.x + Gradle + JDK 21"这套能出正式版的构建组合。
- **LocationSpoofer**（`HuangZhuoRui/LocationSpoofer`，**GPL-3.0**，源码已撤、服务端已关）：
  逆向其 APK 得到八层地图（见 A8），本项目据此补了 `AppOpsManager` 的 ops 自证、
  腾讯 SDK 接管、GNSS/NMEA 注入三块，并借到"星历要共用一份"与"环境要伪造而非清空"两个设计原则。
- **AOSP 源码**（`frameworks/base/location`）：`GnssMeasurement/GnssClock` 的 public-but-@hide 构造与
  setter、`GnssMeasurementsEvent.Builder`、`LocationManager` 的 mock 与测试 provider 语义——
  可行性判断全部以此为准，不信二手教程。

### 未解决的问题

1. **高德地图本体仍未通过**：v0.4.7 的 GNSS/NMEA 注入是冲它去的，但**尚未装机验证**；它也可能
   主要依赖自家服务器 + 缓存，那样单靠卫星侧还不够，得再叠"环境伪造"。
2. **多普勒不严谨**：静止场景取 `pseudorangeRate=0` 能过"在不在动"的检查，**过不了最小二乘定位解算**；
   要严格自洽需真解一组伪距。
3. **调用栈清洗没做**：LocationSpoofer 文档声称有、实现里没有；风控 SDK 读异常栈找 `libxposed` 帧
   是真实存在的检测面，我们目前完全暴露。
4. **`PackageManager` 侧未处理**：目标 App 可以枚举已装包发现管理器/模块本体。
5. **档 2 开机存活未验**（`mockloc-root.zip` 没刷过），且 `publish()` 里的 `chcon` 在这类 ROM 上
   永远静默失败（本机 shell 无 `chcon`），而实测证明**不 chcon 也能读**，这句是留是删待定。
6. **高德 Web服务 key 未申请**：搜索页正向返回仍未验。

### 后续优化方向（按性价比）

① 装机验 v0.4.7 的 GNSS/NMEA 是否让高德地图挪动（判据：蓝点 + `--probe` 样例句子）；
② 若不动，做"环境伪造"（AP/基站列表要有内容而不是空，返回空本身就是异常）；
③ 补调用栈清洗与 `PackageManager` 隐藏；④ 伪距解算让多普勒严谨；
⑤ 把 `--probe` 的调用面从"定位/WiFi/基站"扩到"GNSS/NMEA/传感器"，让下一步永远有证据；
⑥ 配置通道改用 libxposed 的 `getRemotePreferences`（LocationSpoofer 就是这么做的），
摆脱 world-readable 的 `/data/local/tmp` 文件。
- **v0.4.9**（2026-10-27）整合 `参考/` 五个来源（A11）：新增 `AmapSdk.kt`（高德自家定位栈，靶子来自
  allmapLocation/全域暴露的反射类名）、`Ui.kt` + 五页新界面（卡片、分层状态徽章、粘贴坐标带 GCJ/WGS 切换、
  **作用域名单页**）、`ModuleBridge` 修掉"App 选点与模块配置断链"（`tools/mockloc.py sync` +
  root 模块 `sync_app_prefs`，mtime 谁新谁赢，渲染器只有一份）。`ConfigCheck` 加渲染体往返共 31 项断言；
  本地类型检查抓到 4 处（`Ui.fill` 参数设计错、`abs` 未 import、两处布局参数误用）。
- **v0.5.0**（2026-10-27）用 A12 的探针实证重写 `AmapSdk`（补 `getAltitude/getProvider/getSource`，
  改钩 AIDL 客户端代理 `ILocationService$Stub$Proxy` 的 `getFixSatelliteCount/appCanLocation/isGnssOn/...`，
  新增 `Satellite` 方法表转储）；修 `sync` 链路上两个真实缺陷（SharedPreferences 的 `&#10;` 未解码 ⇒
  配置被写成一行、模块读到 `enabled=false`；渲染器漏 `MOCKLOC_PROBE` ⇒ 每次同步把探针关掉），
  修 `GnssStream` 的 `build()` 参数元数查找错（`NoSuchElementException`），状态页徽章改成只说本进程能观测到的事。
  `ConfigCheck` 31 项、Kotlin 类型检查 0 error。
- **v0.5.1**（2026-10-27）按 A13 的全量 dex 证据二次重写 `AmapSdk`：**补钩 `AmapLocation`（此前从未被钩）、
  `LBSLocation`、`OffsetUtils.getGcjLatlng`（直读 public gcj 字段，getter 钩子结构上打不到）与写侧 setter**，
  并首次应对目标 App 自己的反虚拟定位判定（`MockHelper.isMock` / `isFilterMock` / `ForgeryLabeling` /
  `isLocationCorrect`）；**删掉 A12 里那条被证伪的 `$Stub$Proxy` 钩子**（那个类不存在，一直在静默空转）。
  界面按"作用域只在 LSPosed 勾一次"重做：App 里不再有第二份勾选名单，只在遗留名单非空时提供一键清空；
  新增 `tools/mockloc.py scope`，用模块自己打的 `attached to` 日志列出真实挂载的包与进程
  ——读 LSPosed 的实际效果，而不是读它的配置库，也不让用户自己翻 logcat。
  Kotlin 类型检查 0 error、`ConfigCheck` 31 项全过。
- **v0.5.2**（2026-10-27）装机复测否证了 A13 的预测（A14）：高德蓝点不动，**真正有效的是致盲输入**。
  于是把这条从"CLI 隐藏参数"提为一等公民：`ModuleBridge.render` 之前把 `MOCKLOC_BLIND_WIFI/CELL`
  写死成 `false`，**任何一次 `sync` 都会把刚生效的致盲静默关掉**（与 v0.5.0 漏 `MOCKLOC_PROBE` 同一类
  错误，规则"配置字段必须进渲染器"再次抓到它）；状态页加「致盲：开/关」按钮与"改完要重启目标 App"提示。
  `ConfigCheck` 长到 36 项（新增致盲/探针往返、空 targets＝跟随作用域）。pass4 定向扫描
  （`.toolchain/amap_pass4.py`）补上 AmapLocation 无 `getGcj*` 访问器、gcj 字段只在构造函数写、
  `TypeConverter.transLocation` 的 8 个调用点这三条事实。
- **v0.6.0**（2026-10-27）四档权限矩阵实测（A15）+ 界面按矩阵重做：
  ① **实测**档 0 对微信/高德/美团全部无效；档 1（shell uid，完全不用 root）能建 provider
  （identity `1000/android[LocationService]`，`--mode adb` 全流程走通）；档 2（root provider +
  按 uid 掐网，**模块完全关掉**）让高德落在注入点且重定位不回真 ⇒ **高德不需要 LSP 层**；
  ② §2.5 从三档改成四档并加"在目标进程里留不留痕迹"一列，同时更正"`--mode adb` 本机不通"这句混话
  （不能自授 app-op ≠ 不能建 provider），`doctor` 把这两件事拆成两个探针；
  ③ 「作用域」页改成**「策略」页**：列出申请了定位权限的 App（`QUERY_ALL_PACKAGES` + `requestedPermissions`）、
  每行带工具自己知道的①/②/③结论标记、可编辑的只有按包致盲；免 root 档自检（app-op／开发者选项／
  定位权限三枚徽章 + 一键跳设置）；新增「这几样定位层改不了」卡（IP 属地等）；
  ④ `MOCKLOC_BLIND_WIFI/CELL` 改三态 `false|true|逗号包名`，模块按包安装致盲钩子
  （`Config.blindWifiFor(pkg)`），CLI `--blind-wifi[=SPEC]`，`ConfigCheck` 36→48 项；
  ⑤ 档 0 三处硬化：注入间隔 15s→1s、假点补 `satellites/maxCn0/meanCn0`（高德系 SDK 会丢掉没有卫星
  信息的点）、API 31+ 走 `ProviderProperties.Builder`；状态页"已开启"改成读回验收 `matched N/3 + ageMs`；
  ⑥ UI：`DarkActionBar`→`NoActionBar` 修掉重复标题、收藏加名称+备注与编辑/删除入口（`Place.note`
  早就在数据结构里，UI 一直没给口）、长按不再直接删。
  本地：Kotlin 类型检查 0 error（抓到 `setRequiresSatellite` 的真名是 `setHasSatelliteRequirement`）、
  `ConfigCheck` 48 项全过、`tiers/scope/off` 实机跑通。**没有盲编的部分**：App 内 Shizuku 客户端
  （本机 Rish 服务未运行，且 13.1.5 的 `newProcess` 已是包内可见），这一档暂由已实测的 `--mode adb` 承担。
  **一次 CI 红（`c599485`）与它的教训**：改主题时我把一行 `//` 注释写进了 `<application ...>` 的
  属性之间——那是 Kotlin/Java 的写法，在 XML 里是非法 token，manifest merger 直接失败，而本地 Kotlin
  类型检查根本不打开 manifest，所以看不见。修好后新增 `tools/tests/test_manifest.py`（解析全部 XML +
  几条要紧的断言：主题必须 NoActionBar、`ACCESS_MOCK_LOCATION`/`QUERY_ALL_PACKAGES` 在不在、
  MockService 的 DUMP 与 FGS 类型、字符串引用存在），并挂进 CI 的 `logic` 任务，让这类错误在 20 毫秒内
  报出来而不是烧掉一次 Gradle 构建。**负向验证做过**：把 `//` 塞回属性之间，测试确实 exit 1。
  （顺带一个反直觉的事实：`//` 放在元素内容位置是**合法** XML，`/` 不是特殊字符——所以只有夹在属性之间才会炸。）

- **v0.7.0**（2026-10-28）重构阶段一（方案见 `docs/REFACTOR-PLAN.md`）——稳定性与免 PC 闭环：
  ① **档 0 自愈巡检**（1.1）：`MockService` 每 5s 读回验收，provider 消失或偏差 >200m 就地重装
  +补针，连续失败 5 次巡检间隔拉长到 10s 防抖；通知栏新增「暂停/继续/停止」操作（暂停 = 关
  provider 保留前台服务）；新增 `BootReceiver` 开机恢复上次注入（spec 每次启动都落 prefs），
  Android 15+ 拦截 FGS 自启时降级为可点的「恢复注入」通知。**全部待装机验证**（附录 C31）。
  ② **RootInjector**（1.3）：App 内 `su`（stdin 喂命令，免引号层）直写 `/data/adb/mockloc/config.prop`
  + `/data/local/tmp/mockloc.conf` 并跑与 service.sh 相同的 `cmd location` 命令集——
  "App 选点 → sync → 模块"这条 mtime 轮询链在有 root 的机器上变成一步。选档逻辑：su 可用走档 2，
  否则档 0；档 3 仍由 LSPosed 作用域决定。`publish()` 现在带 `global=true`，模块循环会接管维护。
  ③ **海拔真实化**（1.5）：新增 `Elevation.kt`（OpenTopoData aster30m，免 key，0.002° 网格缓存，
  2.5s 超时，任何失败回退旧值 20m），替代硬编码海拔——修掉"注入拉萨报 20m"的免费一致性破绽。
  ④ **配置 v2 前置键**（1.4）：conf 新增 `MOCKLOC_ALT` / `MOCKLOC_LOOP_INTERVAL`（root 模块节奏与
  档 0 注入间隔解耦），`ModuleBridge.render` 的 ACC/RADIUS/INTERVAL 从硬编码改参数化（INTERVAL
  默认 15→1，与 MockService 对齐——原先渲染体写 15 而服务按 1s 推，两头矛盾）；CLI 加 `--alt`，
  写入字典同步两个新键；`ConfigCheck` 48→64 项断言全过。
  ⑤ **rootmodule 三修**（1.2）：jitter 种子改 `/dev/urandom`（原 `srand()` 秒粒度，同一秒两次
  抖动坐标相同，正是检测者要找的重复）；删掉永远静默失败的 `chcon`（A0.1 实测无 label 也能读）；
  `INTERVAL` 读 `LOOP_INTERVAL` 优先并 clamp ≥1。
  ⑥ **UI 小件**（1.6）：顶部常驻状态条（模拟中/模块配置生效/未注入三态着色，点击进状态页）、
  首次注入后的「怎么确认注入真的生效」三步向导（可勾不再显示）、POST_NOTIFICATIONS 运行时申请
  （C11：无授权时通知不显示，之前与"服务停了"不可区分）。
  本地验证：kotlinc 全量类型检查 0 error；ConfigCheck 64 项、CoordCheck、NmeaCheck、
  test_coords、test_manifest（新增 BOOT 权限/receiver 断言）全过；`sh -n` 三个脚本通过。
  **NOT RUN（装机项，判据见附录 C31）**：开机恢复、通知操作、RootInjector 在 KernelSU 的 su、
  海拔 API 真实返回。

- **v0.8.0**（2026-10-28）重构阶段二/三/四（方案 `docs/REFACTOR-PLAN.md`）——覆盖层、无痕一致性层、界面小重做：
  **阶段二（覆盖层）**：
  ① `Route.kt`（2.1）：Catmull-Rom 样条采样成稠密折线 + 按速度推进，纯 Kotlin，`tools/kotlin/RouteCheck.kt`
  22 项 JVM 断言（过控制点/里程单调/速度=配置值/循环绕回/GPX 往返/三方 GPX 解析）全过。
  推进锚点是 `MOCKLOC_ROUTE_STARTED`（纪元毫秒），档 0（MockService 逐 tick 采样）、档 2（root 模块
  `route_pos` awk 线性推进）、档 3（Config.refresh 按同一锚点采样）三层读同一口钟，不会各走各的。
  ② **per-App 独立点位**（2.2）：conf 新增 `MOCKLOC_APP.<pkg>=lat,lng[,acc]`，`Config.currentLatFor(pkg)`
  分流（共享同一抖动偏移）；策略页每行加「📍」按钮（用最近注入的点 / 清除）。
  ③ 路线页（2.3）：6 tab 新增「路线」——地图点航点、速度预设（步行/骑行/驾车/自定义）、循环开关、
  开始/停止；`Location.speed/bearing` 填真实值，NMEA RMC 同步速度航向（`Nmea.sentences` 加参）。
  ④ GPX 导入导出（2.4）：`Gpx.kt`（DOM 解析，命名空间免疫）+ 系统文件选择器。
  ⑤ **NETCUT 产品化**（2.5）：conf 键 `MOCKLOC_NETCUT=包名`，service.sh 解析 uid（stat/dumpsys 兜底）→
  iptables owner-match DROP（带 mockloc-netcut 注释便于清理）；包名清单变化才重算；uninstall.sh 刷规则；
  CLI `--netcut` 写键。这是 A15 实测"档 2 拿下高德"那条杠杆的产品化。
  **阶段三（无痕一致性，全部带 conf 键 + Diagnostics，绝不静默吞）**：
  ⑥ `StackClean.kt`（3.1，默认开）：Throwable/Thread.getStackTrace + Log.getStackTraceString 过滤
  xposed/lsposed/libxposed/lsplant 等 10 个指纹；过滤后为空则返回原栈（空栈是更响的 tell）。README E.3 关闭。
  ⑦ 多普勒非零（3.3，默认生效）：`Sky.pseudorangeRateMps` 按高度角包络（±800 m/s 带内、逐星互异、
  慢漂移）+ 路线模式叠加接收机自身运动投影；ADR 从恒 0 改为按速率积分漂移（锚定首次投递）。README E.2 部分关闭
  （仍是包络对齐，不是严格星历解算——照旧写明）。
  ⑧ `Sensors` WiFi 伪造模式（3.4，`MOCKLOC_FORGE_WIFI` 默认关）：不再"返回空列表"（0 个 AP 本身是异常），
  改为按注入点确定性派生 4 个自洽 AP（02: 本地管理位 MAC、RSSI 衰减、混合 2.4/5G 频段、已连 BSSID 跟随）；
  **CellInfo 伪造明确不做**——CellIdentity/CellInfo 跨 API 28→36 反射构造是版本雷区，构造坏一个就崩宿主，
  基站仍走致盲（空表），取舍记录在案。
  ⑨ 传感器冻结（3.5，`MOCKLOC_SENSOR_FREEZE` 默认关）：registerListener 的 listener 换成动态代理，
  就地改写公开的 `SensorEvent.values`（加速度静止姿态/陀螺零/旋转矢量单位/步数冻结/步数事件吞掉），
  磁力计按纬度偶极子模型给值（与"设备平放"的加速度故事自洽），不构造任何 SensorEvent。
  ⑩ `PkgHide.kt`（3.7，`MOCKLOC_HIDE_PKGS` 默认空=不挂钩）：getInstalledPackages/Applications 过滤 +
  getPackageInfo/ApplicationInfo 对隐藏包按契约抛 NameNotFoundException。README E.4 关闭。
  ⑪ `GmsHooks.kt`（3.6，默认开、类不存在静默跳过）：GMS `LocationResult.getLocations/getLastLocation`
  补一层——android.location getter 钩子已覆盖投递值，这层封"它自己再读一次"的口。
  ⑫ 泄露反推探测器（3.2）：`SelfTest` 在被 hook 进程里（probe 开时）自读 `getScanResults/getAllCellInfo/
  connectionInfo`，日志给出"AP=n 基站=m → 致盲面闭合/有真实射频证据泄露"——全域那套泄露探测的档 3 版。
  **阶段四（界面，有界版）**：
  ⑬ 暗色模式：`MocklocTheme`（values/themes.xml=Light.NoActionBar、values-night=NoActionBar）+ `Ui`
  全部颜色 token 化（页面底/卡片/正文/提示/状态条三态各有日夜两套）；test_manifest 新增主题断言。
  ⑭ 状态页顶部加**暴露风险卡**（全域 16 维评分的可观测子集：注入/配置一致/致盲/挂钩台账/卫星视图
  五枚徽章 → 低/中/高；明确标注它评的是暴露面不是效果）；模块配置区补路线/独立点位/栈清洗/传感器/伪 WiFi/GMS 六行。
  **明确没做（写死，防 scope creep）**：App 内 Shizuku 客户端（C29 结论不变：无可验证路径不盲编）、
  getRemotePreferences 通道（需真机验可靠性，文件通道实测可靠）、悬浮摇杆（路线已覆盖其主场景）、
  CellInfo 伪造（见⑧）、16 维评分完整版。
  本地验证：kotlinc 全量类型检查 0 error；ConfigCheck **76 项**、RouteCheck 22 项、NmeaCheck、CoordCheck、
  test_coords、test_manifest（新增 DayNight 断言）全过；三个 rootmodule 脚本 `sh -n` 通过。
  **NOT RUN（装机项，判据见附录 C32）**：路线三档推进、per-App 双点、NETCUT、栈清洗、泄露反推、
  传感器冻结、WiFi 伪造、GMS、暗色走查。

- **v0.8.1**（2026-10-29）真机闪退修复 + 启动/操作性能优化（设备 478e1f12 / 一加 Ace 3V / Android 16，
  0.8.0-9d13e21 实测复现→修复→复测闭环）：
  ① **闪退根因**（真机栈坐实）：`Elevation.cached()` 对不存在的缓存文件直接 `readText()` →
  `FileNotFoundException` 在注入的后台线程未捕获 → 进程死亡。**全新安装的第一次「注入」必崩**
  （0.8.1 装机复测通过，README C33a）。修法：`exists()` + runCatching，缺失即 null（首次注入本就是
  无缓存状态）。
  ② **防御纵深**：`inject()`/`startRoute()` 的整个后台线程体包 `runCatching`，失败降级为 toast——
  后台线程的任何未捕获异常都不再能杀死进程。`inject()` 先查定位权限，无权限 toast 拒绝而非进服务。
  ③ **FGS 二次崩堵住**：无定位权限时 location 型 FGS 的 `startForeground` 抛 SecurityException 被
  吞掉后，系统会在 ~5s 以 "did not call startForeground" 再崩一次。`MockService.onCreate` 无权限直接
  `stopSelf`，`startLoop` 无权限改发普通通知 + stopSelf，`BootReceiver` 无权限静默跳过 restore；
  `MainActivity` 启动时把 POST_NOTIFICATIONS/FINE/COARSE 三项合并一次申请（全新安装必经）。
  ④ **启动卡顿**：路线页携带整个第二 MapView，冷启动就建两个瓦片栈——改为**惰性构建**（首次点
  「路线」tab 才建，已存路线回填；`onResume/onPause` 同步守卫）。
  ⑤ **策略页卡顿**（真机 gfxinfo 基线：切页一帧 **950ms**）：两个来源，都修——
  a) `loadPolicyList` 的 593 包枚举 + 逐包取 label 在 **UI 线程**跑 → 移后台线程，完成后回填，
     计数行显示加载耗时；b) 过滤框每敲一键**重建全部行**（最多 593 个 CheckBox）→ 换 **ListView +
  BaseAdapter + ViewHolder** 虚拟化（每键只绑定可见 ~7 行；回收视图按"先摘监听→置状态→再挂监听"
  的顺序防勾选串行），说明卡片改为 ListView header 随列表滚动。
  ⑥ **CI rolling release**：新增 `release` job（needs apk+root-module，`contents: write`），每次
  main 构建成功把 `mockloc-debug.apk` + `mockloc-root.zip` 覆盖到 **`latest` Release**——Actions
  artifact 下载要登录，Release 资产不用，从此"push → CI 绿 → 免登录直下安装"闭环：
  https://github.com/JJKLab/android-mockloc/releases/latest
  本地验证：kotlinc 0 error、四个 JVM 校验器全过、test_coords/test_manifest 全过。
  真机复测结果见 C33 与本轮装机记录。

- **v0.8.2**（2026-10-29）App 内一键同步，消灭"配置不一致要 PC 刷新"：
  ① 状态页新增**「同步到模块」**：force 重探 su（KernelSU 授权等待 8s→15s，弹框点允许即成功）→
  `publishConfig` 直写两份 conf → 1.2s 后自动刷新判据；失败弹三选一引导（授权 / 刷 rootmodule / PC）。
  ② rootmodule 僵尸收件箱修复：真机实锤 10-27 的 `mockloc.in`（`ENABLED=false`）永驻——旧 `cfg_src`
  让它在每次 publish 里压过一切新状态。`sync_inbox` 改一次性（应用即删）、`publish`/`cfg_get` 一律读
  CFG（唯一权威）、`sync_app_prefs` 比较基准 PUB→CFG（修掉第二个覆盖竞态）。
  ③ mismatch 文案全部指向 App 内按钮，PC sync 降为最后手段。
  本地：kotlinc 0 error、四校验器全过、`sh -n` 通过。装机判据 C34。
