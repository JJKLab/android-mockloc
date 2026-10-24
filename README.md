# mockloc — 安卓系统级虚拟定位（root / KernelSU）

给已 root（KernelSU / APatch / Magisk）的安卓机做虚拟定位。**阶段 1 已可用**（CLI + root 模块，坐标层有数值验收）；
**阶段 2 待 CI 编译验证**（Xposed/LSPosed 模块，把 `isMock` 痕迹去掉、按 App 生效）。

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

## 2. 用法

不带参数就是问答式菜单，不用记命令：

```
python tools/mockloc.py
```

脚本化 / 定时任务用参数形式：

```powershell
# 搜地名 -> 反算 WGS-84 -> 注入 -> 写开机配置
python tools/mockloc.py set --place 东方明珠 --city 上海 --persist

# 已有坐标（GCJ-02，即国内地图网页上抄出来的那组）
python tools/mockloc.py --mode adb set --coord "31.239703,121.499733" --datum gcj02

# 持续刷新：每 15s 重注入一次并带 25m 抖动
python tools/mockloc.py watch --place 东方明珠 --radius 25 --interval 15

# 看设备现在报告了什么 / 逐项自检 / 关掉
python tools/mockloc.py status
python tools/mockloc.py doctor
python tools/mockloc.py off
```

`--mode adb` 只用 shell 权限（uid 2000）就够了，**不需要 root**，适合临时验证；缺点是重启失效、且
`adb shell` 一拔线就断。`--mode su`（默认）走 root，配合 root 模块可以开机自恢复。

## 3. 编译（本机无安卓环境，全部交给 GitHub Actions）

`.github/workflows/build.yml` 三个 job：

1. `logic` — 跑 `tools/tests/test_coords.py`，坐标层不对就直接卡住后面两个 job。
2. `apk` — JDK 21 + Gradle 8.9（**仓库里没有 gradle-wrapper.jar**，由 `gradle/actions/setup-gradle` 装指定版本）
   + AGP 8.7.0 + Kotlin 2.2.10 + compileSdk 36，产物 `mockloc-debug.apk`。
   runner 镜像自带 Android SDK（platforms 34–37、license 已接受），所以没有装 SDK 的步骤。
3. `root-module` — `sh -n` 语法检查每个脚本，再打进 `mockloc-root.zip`（`update-binary` 从 Magisk 仓库现取）。

版本组合是从 [XposedFakeLocation](https://github.com/noobexon1/XposedFakeLocation) 这套**正在出正式版**的工程里抄的，
不是猜的；libxposed `101.0.1`、legacy `de.robv.android.xposed:api:82` 两个坐标今天都实测能解析。

## 4. 装到手机

```powershell
adb install app-debug.apk                     # 阶段 2 的模块 + 诊断页
adb push mockloc-root.zip /sdcard/            # 阶段 1 的 root 模块，用 KernelSU/APatch 刷入
```

阶段 2 装完后打开 mockloc，页面第一行会显示"注入：已生效/未生效"——这是判断 LSPosed 到底有没有把模块
加载进进程的唯一可靠办法（没生效不会报错，只会安静地什么都不做）。

---

## 附录 A 侦察记录

### A1. 系统到底怎么放行虚拟定位（AOSP android16-release，逐条核过）

- **闸门是 app-op，不是权限**。`LocationManagerService.java:1511-1577` 只做
  `noteOp(AppOpsManager.OP_MOCK_LOCATION, identity)`；`ACCESS_MOCK_LOCATION` 是 `signature` 级、LMS 全程不读，
  开发者选项的定位 App 选择器只用它过滤候选列表。app-op 默认 `MODE_ERRORED`。
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

键：`MOCKLOC_ENABLED / PROVIDER / LAT / LNG / ACC / RADIUS / INTERVAL / TARGETS / LABEL`。
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
| `app/.../ModuleEntry.kt` | libxposed 现代入口（`META-INF/xposed/java_init.list`） |
| `app/.../LegacyEntry.kt` | legacy Xposed 入口（`assets/xposed_init`）——与上面**同义不同栓** |
| `app/.../MainActivity.kt` | 诊断页：`selfCheck` 是否被改写 + 各 provider 回读值 |

双入口是为了对冲一个没法在本机验证的事实：**LSPosed 在 KernelSU + Android 15/16 上到底能不能正常加载
现代 libxposed API**。两条都编进同一个 APK，哪条通就用哪条，行为由 `SpoofLogic` 保证一致。

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

## 附录 D 版本历史

- **v0.1.0**（2026-10-24）侦察完成 + 阶段 1（坐标层数值验收、CLI 三条路径 dry-run 实测、root 模块脚本语法与函数实测通过）
  + 阶段 2 代码（libxposed + legacy 双入口，未编译）。CI 工作流就绪，未跑过。
