# mockloc 重构方案（v0.7 → v1.0）

> 依据：README v0.6.1 全量现状 + A0–A15 实测结论 + `app/`、`rootmodule/`、`tools/` 源码走查
> + `refs/`（AnyWhere / FakeLoc / fakelocation / GoGoGo / gps-locator / HideMockLocation /
> LocationSpoofer / XposedFakeLocation）+ `参考/`（AnyDoor / allmapLocation / FakeLoc / 全域 / 天下游）。
> 原则与 README 一致：**判据先行、配置渲染器唯一、本地可验证、真机实测才标"通过"**。
> 每一项工作都带验收判据；没有判据的条目不排期。

---

## 0. 结论摘要

1. **核心定位能力"不稳定"的真实原因已经查清，且大部分不是 bug 而是结构缺口**：
   - 三类目标 App 对应三个杠杆（①系统 provider / ②抽空射频输入 / ③进程内改写），A15 已实测
     分流矩阵。当前①②③都有实现，但**②只有"清空"没有"伪造"**、**③缺一致性层**（栈暴露、
     传感器、多普勒、PackageManager）、**档 0 缺保活与自愈**——这三块是"时灵时不灵"的主因。
   - 流程断点：App 选点要等 `sync`（mtime 轮询）才到模块；有 root 的机器上这是纯摩擦，
     App 内直写即可闭环。
   - 覆盖面：路线/轨迹、按 App 独立点位、GPX、摇杆，全部参考 App 有而本项目无。
2. **优先级**：P0 稳定层（保活/自愈/配置闭环）→ P1 覆盖层（per-app 点位/路线/海拔）→
   P2 无痕一致性层（档 3）→ P3 UI 重做 → P4 扩展档位（Shizuku/远程偏好）。UI 明确排在核心
   稳定之后，但"状态条 + 验证向导"这两个 UI 件服务于诊断判据，随 P0 一起做。
3. **不用 Compose、不引入 Material 三方库**：README 的"本地可验证性"纪律（kotlinc + android.jar
   全量类型检查）是本项目零 CI 浪费的根本，UI 重做在 View 体系内做设计系统。

---

## 1. 现状盘点与差距分析

### 1.1 能力矩阵：mockloc vs 参考实现

| 能力 | fakelocation | GoGoGo | gps-locator | AnyWhere | XposedFakeLocation | FakeLoc(kalun) | AnyDoor | 全域/AllMap(商用) | **mockloc 现状** |
|---|---|---|---|---|---|---|---|---|---|
| 地图选点/拖图钉 | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓（无拖图钉实时注入） |
| 地名搜索 | POI | ✓ | 200+国家免key | ✓ | ✓ | ✓ | ✓ | ✓ | 仅高德 geocode（需 key） |
| **路线/轨迹模拟** | ✓ | ✓ | ✓+GPX | ✓ | ✗ | ✗ | ✓ | ✓（Catmull-Rom+行为建模） | **✗** |
| GPX/KML 导入导出 | ✗ | ✓ | ✓ | ✗ | ✗ | ✗ | ✗ | ✗ | **✗** |
| 虚拟摇杆 | ✓ | ✓ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | **✗** |
| **按 App 独立点位** | ✓"独立模拟" | ✗ | ✗ | ✗ | ✓（一个点） | ✗ | 全局 | ✓ | **✗（全局单点）** |
| 抖动半径 | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓（仅 CLI，UI 无口） |
| 无 root 档 | ✓ | ✓ | ✓ | ✓ | ✗ | ✓ | ✗ | ✗ | ✓ 档0 |
| root 档 | ✓ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✓ 档2 |
| Shizuku 档 | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | 半（仅 CLI `--mode adb`） |
| Xposed 进程内改写 | ✗ | ✗ | ✗ | ✓ | ✓ | ✗ | ✓ | ✓ | ✓ 档3（libxposed 102） |
| mock 痕迹清除 | ✗ | ✗ | ✗ | ✓（清单过期） | 部分 | ✗ | ✓ | ✓ | ✓（对 API 36 重核） |
| 致盲 WiFi/基站 | ✗ | ✗ | ✗ | ✓ | 部分 | ✗ | ✓ | ✓ | ✓（实测对高德有效） |
| **伪造**射频环境（造 AP/基站列表） | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✓ | **✗（只有清空）** |
| GNSS 测量 + NMEA | ✗ | ✗ | ✗ | ✗ | 部分 | ✗ | ✗ | ✓ | ✓（A9 层；多普勒为 0） |
| 传感器一致性 | 步数 | ✗ | ✗ | ✗ | ✓ | ✗ | ✗ | ✓（13 种物理自洽） | **✗** |
| 调用栈清洗 | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✓ | **✗（README E.3 自认缺口）** |
| PackageManager 隐身 | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✓ | **✗（README E.4 自认缺口）** |
| GMS fused 改写 | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✓（GMS 进程 5 类） | ✗ |
| 真实海拔 | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✓（OpenTopoData） | **✗（硬编码 20m）** |
| 效果自检/评分 | ✗ | ✗ | ✗ | ✗ | ✗ | 系统回读 3/3 | ✓ | ✓（16 维评分+泄露反推） | 部分（Diagnostics/SelfTest/Probe 零散） |
| 开机自恢复 | ? | ✗ | ✗ | ? | ? | ✗ | ? | ? | 档2 脚本在但**未验**；档0 **无** |
| Material/现代 UI | ✓ | ✓ | ✓ M3 | ✓ | ✓ | ✓ | ✓ | ✓ | ✗（裸 View） |
| 多设备 ADB 控制 | ✗ | ✗ | ✓ | ✗ | ✗ | ✗ | ✗ | ✗ | ✓（CLI） |

判读：**注入深度上 mockloc 已经是第一梯队**（四档矩阵 + 致盲有效 + GNSS 流自洽设计都是
本地实测/源码级核实的），**产品面上（路线、per-app、保活、UI）全面落后于参考**。重构重心
因此不是补 hook，而是把已验证的能力变成"装上就稳、选了就通、看着就懂"的产品。

### 1.2 定位生效不佳的根因（按层拆开，全部有实测依据）

**A. 注入层（档 0/1/2）的稳定性缺口**

| # | 现象 | 根因 | 依据 |
|---|---|---|---|
| A1 | 重启手机后档 0 失效 | `MockService` 只有 `START_STICKY`，无 `BOOT_COMPLETED` 恢复，也无从 prefs 重注入 | `MockService.kt:45`；FakeLoc README 明确"从 prefs 恢复" |
| A2 | 注入一段时间后"悄悄失效" | test provider 会被系统/宿主回收；`verify()` 每 20s 只打日志，**不自愈**（不重装 provider、不补针） | `MockService.kt:145-158`；全域的"偏差 >200m 补针、连续失败重装"模式 |
| A3 | 通知栏无法操作 | 通知没有"停止/暂停"动作，停止必须回 App | `MockService.kt:214-226` |
| A4 | 档 2 开机自恢复从未验证 | `service.sh` 循环写了但 README 附录 C21 明示未验；`chcon` 在本机 ROM 永远静默失败 | `rootmodule/service.sh`；README C4 |
| A5 | 档 2 抖动种子是秒级 | `jitter` 用 `awk srand()`（秒粒度），`INTERVAL<1s` 不支持 | `mockloc-lib.sh:26`；README C7 |
| A6 | 档 2 的②（按 uid 断网）只能手工 iptables | A15 实测"root provider + 掐网"能拿下高德，但这套动作没产品化 | `README A15` |
| A7 | 注入间隔/精度/抖动三参数 UI 无口 | `ModuleBridge.render()` 硬编码 `ACC=5.0 / RADIUS=0.0 / INTERVAL=15`，且与 `MockService` 的 1s 默认值**互相矛盾** | `ModuleBridge.kt:126` vs `MockService.kt:246` |
| A8 | 档 0 的卫星视图 | 结构性缺口：GnssStatus 是系统回调，不进目标进程改不了 → 假 GPS + 0 颗星。已在假点 extras 补 `satellites`，但真正的 GnssStatus 读回仍是真值 | `README A15 档0硬缺口` |

**B. 目标 App 分流（已有矩阵，剩余缺口）**

| # | 缺口 | 影响 | 对标 |
|---|---|---|---|
| B1 | 致盲 = 返回空列表/空串 | "周围 0 个 AP / 0 个基站"本身就是异常信号；全域/LocationSpoofer 都选**伪造自洽环境**（本地管理位 MAC、按注入点周边生成） | 全域 3.1 WiFi 行、LocationSpoofer 层 5 |
| B2 | 无传感器一致性 | 检测面：位置不动但加速度计在晃、计步在涨。静止模式至少要"加速度静止姿态 + 计步冻结" | 全域 native 传感器；XposedFakeLocation 有传感器数据 |
| B3 | 无 GMS fused 改写 | 装了 GMS 的机器上走 `FusedLocationProviderClient` 的 App 拿到的是 GMS 侧值 | LocationSpoofer 层 1 |
| B4 | 调用栈全暴露 | 风控 SDK 读异常栈找 `libxposed`/`LSPosed` 帧是真实检测面；README E.3 自认 | 全域 3.3；LocationSpoofer 文档声称有（实现没有） |
| B5 | PackageManager 全暴露 | 目标 App 枚举已装包即可发现 LSPosed 管理器与模块本体 | 全域 3.3 隐身模块 |
| B6 | GNSS 多普勒 = 0 | 能过"在不在动"，过不了严格最小二乘解算（README E.2 自认） | A9 已知不严谨处 |
| B7 | 微信已通、高德已通（①+②），**美团/抖音/支付宝/银行类未测未标注** | 策略页对未测 App 无引导；支付宝 `LBSLocation` 钩子已在 AmapSdk 里但没验收记录 | A13.3 |

**C. 一致性面**

- 海拔硬编码 `20.0`（`MockService.kt:129`、`SpoofLogic.fakeLocation` 不设海拔）——注入广州（≈11m）
  与拉萨（≈3650m）给同一个海拔，是白送的矛盾点。
- 时区不一致未提示（全域按经度推算并提示）。
- 不可修项（IP 属地、账号注册地、厂商基站库）已在策略页标注 ✓ 保持。

**D. 覆盖面（参考全有、本项目全无）**：路线、GPX、摇杆、per-app 点位——见 §2。

**E. 流程摩擦**

- E1：App 选点 → prefs → 等 root 模块 `sync_app_prefs`（mtime 轮询、秒级延迟）才到模块。
  **App 内有 root 时可直接写**（KernelSU 给 App 的 su 授权与给 shell 的无关），一步到位。
- E2：档 1（shell uid）没有 App 内入口，只有 PC CLI。
- E3：改动致盲/探针后要"重启目标 App"才生效，App 里只有 toast 提示，没有可跟的引导。

**F. UI/UX**：单 Activity 801 行代码式拼 View、状态页是一面墙文本（`statusText` 一个
TextView 塞 20 行）、无状态绑定（手动刷新）、无深色模式、列表是裸 `ArrayAdapter<String>`、
593 包策略列表刚做完性能修复（bb36783/83c2174）、无图标无动效无空态。

---

## 2. 分层优化清单

### 2.1 人有我有（补齐参考标配，全部本地可实现）

| # | 项 | 对标来源 | 落点 |
|---|---|---|---|
| P1 | 路线/轨迹模拟（步行/骑行/驾车 + 循环 + 循迹推进） | GoGoGo / gps-locator / AnyWhere / 全域 | `Route.kt` + 路线页 |
| P2 | GPX 导入导出 | GoGoGo / gps-locator | `Gpx.kt`（JVM 可测） |
| P3 | **按 App 独立点位**（微信北京、美团上海同时成立） | fakelocation"独立模拟" / 全域 | `Config` per-app 键 + 策略页 |
| P4 | 开机自恢复（档 0）+ 通知栏停止/暂停 | FakeLoc | `BootReceiver` + 通知 action |
| P5 | 注入自愈巡检（provider 消失重装 / 偏差超限补针） | 全域 4.2 | `MockService.verify()` 升级 |
| P6 | 免 key 搜索兜底（OSM Nominatim，WGS-84 直出） | gps-locator | `net/Nominatim.kt` |
| P7 | 海拔真实化（OpenTopoData，0.002° 网格缓存） | 全域 4.1 | `net/Elevation.kt` |
| P8 | 摇杆悬浮窗（`SYSTEM_ALERT_WINDOW`） | fakelocation / GoGoGo | P4 期，量大后置 |
| P9 | 现代化 UI（地图即首页、状态条、深色模式、组件化） | FakeLoc / gps-locator M3 | §6 |
| P10 | 电池优化/自启动引导（OEM 路径提示） | FakeLoc README | 我的→设置 |

### 2.2 人无我有（差异化，做出来就是独有）

| # | 项 | 说明 |
|---|---|---|
| D1 | **四档一体的策略页**：每个 App 一行，标注实测判定的杠杆（①/②/③/未测）+ 一键切杠杆（致盲开关 / per-app 点位 / 引导进 LSPosed） | 参考实现都只有"勾作用域"，没有"每个 App 该用哪一层"的实测结论沉淀 |
| D2 | **验证向导**：注入后三步 checklist（重启目标 App → 主动触发定位 → 读返回坐标），把 README 里"显示值≠读取值"的教训产品化，防"看着没动就判死" | 全域的 14 步校验是仪器向的；这个是给用户的 |
| D3 | **档 3 泄露反推探测器**：SelfTest 在被 hook 进程里自读 `getScanResults`/`getAllCellInfo`/`getConnectionInfo`，把"它此刻拿到的真实射频证据数"打到诊断页——致盲没开够一目了然 | 全域有泄露探测；在档 3 内做比在 App 内做**更贴近检测者视角**，且实现便宜 |
| D4 | **判据即文档**：每个开关的说明直接引自 README 的实测行（"致盲是高德唯一认的开关"），App 内永不出现未实测的承诺 | 与项目"Tri-state 证据"文化一致 |
| D5 | 坐标系三视图常态化：地图/结果面板同时显示 WGS-84 与 GCJ-02，粘贴双向识别（已有一半） | 补齐 BD-09 第三态 |

### 2.3 人优我优（比参考做得更细）

| # | 项 | 比谁细 |
|---|---|---|
| S1 | 反检测面对 **API 36 逐条重核**：AnyWhere 的过期清单（`readFromParcel`/`hasEphemeris`）在本项目是已知教训，继续保持"每条 hook 带 Diagnostics 记账"，参考实现全是静默 catch | AnyWhere / HideMockLocation |
| S2 | 星历三处共用（GnssStatus/测量流/NMEA）+ 斜距几何，比"造一堆卫星数"自洽 | LocationSpoofer 层 6 |
| S3 | 配置渲染器唯一 + ConfigCheck 往返断言（48 项），新键全部进断言——参考实现的三处配置漂移 bug 在本项目结构上不可能复发 | 全部参考 |
| S4 | per-App 致盲三态（`false|true|包名`）而非全局开关 | fakelocation 的全局 WiFi 模拟 |
| S5 | 探针（只记不改值）作为常态诊断工具，"下一步该钩什么"变成读日志 | LocationSpoofer 逆向花费两轮才得到的结论，我们一次给用户 |
| S6 | 城市级注入模板：收藏点带"一键注入 + 备注"，历史自动记录最近 N 次 | gps-locator 有历史；我们把收藏/历史/路线统一成一个 Store |

---

## 3. 模块划分

### 3.1 目标包结构（单 APK 内分包，Gradle 仍单 module——CI-only 构建不增加复杂度）

```
com.mockloc/
├── core/                 # 纯 Kotlin，JVM 可测（tools/kotlin 继续吃它们）
│   ├── CoordTransform.kt        # 现有，三系互转 + BD-09
│   ├── Nmea.kt / Sky.kt         # 现有（Sky 从 GnssStream.kt 拆出）
│   ├── Route.kt                 # 新：航点→Catmull-Rom→按速度推进→(lat,lng,bearing,speed)
│   ├── Gpx.kt                   # 新：GPX 1.1 解析/序列化
│   ├── Config.kt                # 现有 + v2 键（§5.1）
│   ├── Scope.kt                 # 从 Config.kt 拆出（现在就是独立类）
│   ├── Profile.kt               # 新：per-app 点位/开关的查询结构
│   └── Diagnostics.kt           # 现有，加"分类 + 去重 + 计数"
├── inject/               # 档 0/1/2 的统一注入面
│   ├── Injector.kt               # 新接口：apply(point) / teardown() / verify() → InjectReport
│   ├── AppInjector.kt            # 现 MockService 的 provider 循环，实现 Injector
│   ├── MockService.kt            # 前台服务壳：通知 action、BOOT 恢复、自愈巡检
│   ├── RootInjector.kt           # 新：App 内 su 执行 cmd location（免 PC 闭环）
│   └── BootReceiver.kt           # 新：BOOT_COMPLETED → 从 prefs 恢复上次点位
├── module/               # 档 3（libxposed，全部在目标进程内跑）
│   ├── ModuleEntry.kt            # 现有
│   ├── hooks/
│   │   ├── LocationHooks.kt      # 现 ModuleEntry.installCoordinates
│   │   ├── MockTrace.kt          # 现有
│   │   ├── Sensors.kt            # 现有 + 传感器静止化（P2）
│   │   ├── GnssStream.kt         # 现有 + 多普勒模型（P2）
│   │   ├── TencentSdk.kt / AmapSdk.kt   # 现有
│   │   ├── GmsHooks.kt           # 新（P2）：FusedLocationProviderClient 候选类名 try-load
│   │   ├── StackClean.kt         # 新（P2）：Throwable/Thread.getStackTrace 帧过滤
│   │   └── PkgHide.kt            # 新（P2）：PackageManager 白名单过滤
│   ├── Probe.kt / SelfTest.kt    # 现有；SelfTest 扩"泄露反推"（D3）
│   └── HookKit.kt                # 现有
├── bridge/               # 配置契约（唯一渲染器）
│   └── ModuleBridge.kt           # 现有 + v2 键 + 渲染断言同步
├── data/
│   └── Store.kt                  # 收藏/历史/路线/per-app 点位，JSON 文件 + 版本迁移
├── net/
│   ├── AmapSearch.kt / AmapTiles.kt   # 现有
│   ├── Nominatim.kt              # 新（免 key 搜索）
│   └── Elevation.kt              # 新（真实海拔 + 磁盘缓存）
└── ui/
    ├── MainActivity.kt           # 只做路由 + 底导航 + 状态条，<200 行
    ├── pages/                    # 每页一个类：view 构建 + refresh()，无共享可变状态
    │   ├── MapPage.kt / TargetsPage.kt / RoutePage.kt / MinePage.kt / DiagPage.kt
    ├── Design.kt                 # 设计 token + 组件库（§6.2，取代 Ui.kt）
    └── Wizard.kt                 # 验证向导（D2）
```

依赖方向：`ui → inject/bridge/data/net → core`；`module → core`（module 不依赖 inject；
两者通过 conf 文件通信）。`core` 不 import 任何 android.*（Route/Gpx/Config 解析可被
`tools/kotlin/*Check.kt` 直接 JVM 验收，延续 ConfigCheck 纪律）。

### 3.2 保持不变的两条铁律

1. **本地可验证**：新增 UI 组件只用 `android.jar` 内的类；若评估后确要引 Material，先按 README
   §3 的 AAR 元数据检查流程验 `minCompileSdk`，并把 classes.jar 加进本地 typecheck classpath——
   默认不引。
2. **配置渲染器唯一**：§5.1 的每个新键必须同时出现在 `ModuleBridge.render()` 与
   `ConfigCheck` 断言里（README C30 的教训：漏键 = 每次 sync 静默重置）。

---

## 4. 核心流程改造

### 4.1 统一注入管线 `Injector`

现状：档 0（App 内 addTestProvider）、档 1（PC `cmd location`）、档 2（root 脚本循环）
是三份互不相干的实现。目标：一个接口三个实现，App 内自动选最高可用档。

```
Injector
 ├─ AppInjector   档0：前台服务 + addTestProvider（现状 MockService 循环）
 ├─ RootInjector  档2：Runtime.exec("su") 逐条跑 cmd location（App 进程内 root，KernelSU 授权）
 └─ AdbInjector   档1：Shizuku user-service 执行同一组命令（P4 期，见 §7）
```

选档逻辑（Tiers 探测，全部实测可判）：
`root 可用（su 试运行 id）→ RootInjector`；否则 `app-op 已授 → AppInjector`；
都不行 → 引导页（开发者选项深链 + 图文）。**档 3 永远叠加**，与注入档正交（现状语义保留）。

验收判据：
- 选 RootInjector 时，`dumpsys location` 出现 `identity=0/android[LocationService]` 的 `[mock]`；
- 关 App 重开地图，注入不中断（root 侧是独立进程，不受 Activity 生命周期影响）；
- AppInjector 行为与现状一致（读回验收 matched=3/3）。

### 4.2 免 PC 闭环（消灭 sync 等待）

- App 有 root：`ModuleBridge.publish()` 直接写 `/data/adb/mockloc/config.prop` +
  `/data/local/tmp/mockloc.conf`（`su -c`），mtime 即最新，`sync_app_prefs` 天然让位。
- 无 root：维持现链路（prefs → root 模块搬运 / PC sync），但状态页把"待搬运"显示成
  明确的徽章而不是现在藏在 mismatch 文本里。
- 顺带修 `render()` 与 `MockService` 的参数矛盾（A7）：`MOCKLOC_LOOP_INTERVAL`（root 模块用）
  与 `MOCKLOC_INTERVAL`（档 0 用）拆开，route 模式两者都 1s。

### 4.3 档 0 自愈巡检（MockService）

每 5s 巡检（现 verify 20s 一次只打日志）：
1. `getLastKnownLocation` 读回与目标点偏差 > 200m 或 provider 消失 → `removeTestProvider` +
   `addTestProvider` 重装 + 立即补针（全域同款参数：连续失败 5 次后间隔拉长到 10s 防抖）；
2. 通知栏带「暂停 / 停止」两个 action（PendingIntent 广播回 Service）；
3. `BOOT_COMPLETED` receiver：读 prefs 上次点位 → `startForegroundService` 恢复；
   （Android 15+ 对 BOOT 启动 FGS 有限制，实机验不过就降级为"开机后通知栏一键恢复"）；
4. 设置页提供电池优化白名单深链（`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 需用户
   主动点，不用静默申请）+ 华为/小米/OPPO 自启动设置路径提示（FakeLoc README 清单）。

验收判据：force-stop 一个 provider（`cmd location providers remove-test-provider gps`）
后 ≤10s 内恢复；重启手机后（授权仍在的 ROM）注入自动恢复。

### 4.4 档 3 一致性栈补齐（P2 期，每项独立开关 + Diagnostics 记账）

| 子项 | 实现要点 | 默认 |
|---|---|---|
| 栈清洗 `StackClean.kt` | hook `Throwable.getStackTrace`、`Thread.getStackTrace`、`Log.getStackTraceString`，过滤 `xposed/lsposed/libxposed/edxposed` 帧。LocationSpoofer 文档声称有而实现没有——做之前先跑一次 Probe 确认目标 App 真读栈，避免过度伪装 | on |
| 包隐藏 `PkgHide.kt` | `getInstalledPackages/getPackageInfo/getApplicationInfo` 过滤配置清单（默认含本包与 LSPosed 管理器候选名）。**白名单式、per-App 配置**：过滤太广会弄坏 App 功能 | off（默认清单为空） |
| 射频伪造（升级致盲） | `getScanResults` 返回 3–6 个伪 AP（`02:xx` 本地管理位 MAC、`<unknown ssid>`、按注入点衰减的 RSSI）；`getAllCellInfo` 返回 1–2 个伪基站（LteCellIdentity 位置字段按注入点）；`WifiInfo` 标识位伪造成"注入点周边 AP"。保留"清空"模式作为对照 | off（默认维持清空，伪造模式 opt-in 实测后再反转默认） |
| 多普勒非零 | 静止接收机的伪距率来自卫星轨道运动：按 Sky 的仰角/方位角投影星历切向速度（≈±0.4–0.9 m/s 视界内），`pseudorangeRate` 不再为 0，同时保持 `ADR` 增长与之一致 | on |
| 传感器静止化 | hook `SensorManager.registerListener`：加速度给 `(0,0,9.81)+微小噪声`、陀螺 0、计步冻结；路线模式按 `Route` 的 speed 换算步频（全域同思路，只取模型不取代码） | off |
| GMS fused | `com.google.android.gms.location.FusedLocationProviderClient` 的 `requestLocationUpdates` 回调包装——候选类名 try-load，GMS 不存在时静默跳过（国产机常态） | on |

**约束**：全部走 HookKit + Diagnostics（失败可见，绝不静默吞）；每项一个 conf 键，
进渲染器 + ConfigCheck；装机后按"判别性设计"验收（系统 mock 与模块配置给不同值，读回判生效）。

### 4.5 路线引擎（`core/Route.kt`，JVM 可测）

- 输入：航点序列 + 模式（步行 1.4 / 骑行 5.5 / 驾车 8–16 m/s 随机带限 / 自定义）+ 循环开关。
- 平滑：Catmull-Rom 样条插值（全域同模型，实现自写）；推进：分段累计里程 + 每秒步进
  `speed*dt`；`bearing` 取相邻点方位角、`speed` 填 Location（与假点一致性联动，静止假设
  自动失效）。
- 停顿建模（v2）：随机停顿 25–120s、转弯降速——先不做，路线直行版先上真机验。
- 注入侧：`Injector` 每 tick 问 `Route.sample(elapsed)`；root 模块侧由 service.sh 循环
  `apply_once` 推进（INTERVAL=1s，`MOCKLOC_ROUTE_INDEX` 记进度，重启续走）。
- 配套 UI：地图长按加航点、列表拖拽排序、里程/时长预估、开始/暂停。
- GPX：导入（`<trkpt>`）、导出（收藏点串成路线）。

验收判据：`tools/kotlin/RouteCheck.kt` 断言（里程单调、速度不超上限、Catmull-Rom 过控制点、
GPX 往返等值）；真机上高德/微信位置沿路线连续移动且速度栏读数合理。

---

## 5. 数据结构调整

### 5.1 配置契约 v2（conf 文件，全部向后兼容）

现 13 键不动。新增（**全部进 `ModuleBridge.render()` + ConfigCheck**）：

```
MOCKLOC_ALT=<米|-1=自动取真实海拔>          # 修 A-海拔硬编码
MOCKLOC_SPEED_MPS=<float>                   # 路线推进速度
MOCKLOC_ROUTE_WAYPOINTS=lat,lng;lat,lng;…   # 航点序列
MOCKLOC_ROUTE_INDEX=<int>                   # 推进进度（重启续走）
MOCKLOC_ROUTE_LOOP=true|false
MOCKLOC_LOOP_INTERVAL=<秒>                  # root 模块循环间隔（与档0注入间隔解耦，修 A7）
MOCKLOC_NETCUT=<包名逗号表>                 # 档2：按 uid iptables 断网（A15 实测路径产品化）
MOCKLOC_APP.<pkg>=lat,lng[,acc]             # per-App 独立点位（键内含点号，现有解析器 indexOf('=') 天然支持）
MOCKLOC_STACK_CLEAN=true|false
MOCKLOC_HIDE_PKGS=<包名逗号表>              # PkgHide 白名单
MOCKLOC_FORGE_WIFI=true|false               # 伪造 vs 清空（默认 false）
MOCKLOC_FORGE_CELL=true|false
MOCKLOC_SENSOR_FREEZE=true|false
MOCKLOC_GMS=true|false
```

`Profile.kt`：`pointFor(pkg) → 覆盖点 or 全局点`；`SpoofLogic.isActive(pkg)` 不变，
`Config.currentLat/Lng` 的取值改为 `profile-aware`（模块侧每 tick 按 pkg 取点，jitter 仍每
消费者自算）。

### 5.2 App 侧存储（`data/Store.kt`）

SharedPreferences 里的裸键迁到私有目录 JSON 文件（带 `version` 字段，向后迁移）：

```jsonc
// places.json
[{"id":"uuid","name":"东方明珠","note":"…","lat":31.241702,"lng":121.495284,"group":"上海","ts":1698000000}]
// routes.json
[{"id":"uuid","name":"通勤","mode":"walk","loop":true,"waypoints":[[31.1,121.1],[…]]}]
// history.json（环形，最近 50）
[{"ts":1698000000,"lat":…,"lng":…,"label":"…","via":"tier0|root|module"}]
// app_profiles.json
{"com.tencent.mm":{"point":[39.9087,116.3975],"blind":"only:com.tencent.mm"}}
```

价值：收藏/历史/路线/profile 一处管理；导出/导入（设置页一键备份，参考 gps-locator 的
加密备份，我们本地明文 JSON + 用户自管）。

---

## 6. UI 与交互重做

### 6.1 信息架构（5 Tab → 4 Tab + 常驻状态条）

```
┌──────────────────────────────────────────┐
│ ● 模拟中 · 东方明珠 · 档0+档3  ← 常驻状态条（点击进诊断页）│
├──────────────────────────────────────────┤
│                                          │
│            地图（首页，全屏）              │
│   [搜索悬浮条]                            │
│   [图钉/标记]                             │
│   ┌────────────────────────────┐        │
│   │ WGS 31.2417,121.4953       │ ←坐标卡 │
│   │ GCJ 31.2397,121.4997       │        │
│   │ [开始模拟] [收藏] [路线]     │        │
│   └────────────────────────────┘        │
├──────────────────────────────────────────┤
│   地图      目标      路线      我的       │
└──────────────────────────────────────────┘
```

- **地图**（首页）：地图即首页（FakeLoc 同款）；运行中坐标卡变成"运行中面板"（目标点、
  已运行时长、档位徽章、暂停/停止）；拖图钉 = 持续更新坐标（FakeLoc 交互）。
- **目标**（现"策略"页强化）：顶部「全局点位 / 按 App 独立点位」分段控件；App 列表每行 =
  图标 + 名称 + 杠杆徽章（①/②/③/未测）+ 该 App 当前生效点（全局/独立）+ 致盲 inline 开关；
  搜索过滤保留；**独立点位点击 → 进地图选点模式，选完回填**（D1）。
- **路线**：航点编辑 + 模式/速度/循环 + 开始暂停 + GPX 导入导出（§4.5）。
- **我的**：收藏（分组 + 名称/备注，现状保留）、历史（最近 50，一键重注入）、设置
  （key、精度、抖动半径、注入间隔档位、海拔来源、探针、伪造/清空模式、包隐藏清单、
  电池优化引导、备份导出导入）。
- **诊断页**（从状态条进）：风险评分卡（见下）→ 四 provider 读回 → 模块配置回读 →
  Diagnostics 台账 → 探针开关 → SelfTest/泄露反推 → **验证向导**（D2 三步 checklist）。

### 6.2 设计系统 `ui/Design.kt`（取代 Ui.kt）

保持 View 体系（§3.2 铁律），内容：

- **色 token**：`primary #1A73E8`、语义四色（沿用现 Ui.State 的 OK/WARN/OFF/BAD 值域）、
  背景 `#F6F7F9`、卡面 `#FFFFFF`、暗色成套（`values-night` + `Theme.DeviceDefault.DayNight`）。
- **字阶**：title 16sp/600、body 14sp、caption 12sp，全部经 token 取值。
- **组件库**：`Card`（12dp 圆角、ripple）、`Pill`（状态徽章）、`InfoRow`（label + value +
  badge）、`PrimaryButton / TonalButton / GhostButton`、`SwitchRow`（标题+说明+开关整行可点）、
  `SectionHeader`、`EmptyState`、`BottomPanel`（地图页坐标卡/运行面板，自定义 ViewGroup）。
- **图标**：无三方依赖——自绘 `VectorDrawable`（res/drawable，本地可画可查）或 unicode 字形；
  底导航用自绘 selector。
- **MainActivity 拆分**：页构建逻辑从 801 行拆到 `pages/*`，每页 `View build() + void refresh()`，
  状态条统一 `refresh()` 入口，消灭"手动点刷新才知道状态"。

### 6.3 交互流程

选点 → 坐标卡确认（双坐标系 + 与上一点距离）→ 「开始模拟」→ 状态条变绿 + Toast →
首次注入弹**验证向导**（重启目标 App → 主动定位 → 读返回坐标；可勾"不再提醒"）。
停止：通知栏按钮 / 运行面板 / 状态条。所有破坏性操作（删除收藏、清空策略）二次确认（现状已对）。

### 6.4 策略页性能（延续 bb367bb 的修复）

包列表改为 `ListView + 自定义 Adapter + ViewHolder`（现 CheckBox 逐个 addView 在 593 包时
每键渲染全量），过滤在 `policyAll` 数据层做；图标用 `loadIcon` 缓存 `LruCache`。

---

## 7. 分阶段实施计划

> 每阶段独立可发布、可回滚（Copy-first + 四件套惯例不变）。阶段内条目按依赖排序。

### 阶段一 · 稳定层（P0，先做，约 2–3 个版本）

> **进度（2026-10-28，v0.7.0）**：1.1–1.6 代码全部落地（`BootReceiver`/`RootInjector`/`Elevation`
> 三个新文件 + `MockService`/`MainActivity`/`Config`/`ModuleBridge`/manifest/rootmodule 改造）。
> 本地验证：kotlinc 0 error、ConfigCheck 64 项、CoordCheck、NmeaCheck、test_coords、test_manifest
> （新增 BOOT 断言）、`sh -n` 全过。**装机验收判据集中在 README 附录 C31**——开机恢复、自愈巡检、
> 通知操作、RootInjector 的 KernelSU su、海拔 API、LOOP_INTERVAL 均为 NOT RUN，待真机逐条勾。

| # | 任务 | 验收判据 |
|---|---|---|
| 1.1 | `MockService` 自愈巡检 + 通知 action + BOOT 恢复 | §4.3 判据；杀 provider 10s 内恢复 |
| 1.2 | rootmodule：开机自恢复实机验证；`jitter` 种子换 `/dev/urandom`；删 `chcon` 死语句；`INTERVAL` 下限 clamp 1s | 重启后 `dumpsys location` 仍 `[mock]`（C21 关闭） |
| 1.3 | `RootInjector`：App 内 su 直写 conf + cmd location | 免 PC 全流程：选点→高德蓝点移动，全程不接电脑 |
| 1.4 | 配置 v2 前置键（`MOCKLOC_ALT/LOOP_INTERVAL/SPEED_MPS`）+ 渲染器/ConfigCheck 同步 | ConfigCheck ≥ 60 项全过；render 与 MockService 参数不再矛盾 |
| 1.5 | 海拔真实化（OpenTopoData + 缓存，失败回退现值） | 注入拉萨读回海拔 ≈ 3650m（±50m） |
| 1.6 | 状态条 + 验证向导（UI 先行件） | 新装用户按向导走完能自己判"生效/没生效" |

### 阶段二 · 覆盖层（P1）

> **进度（2026-10-28，v0.8.0）**：2.1–2.5 全部落地。`Route.kt`/`Gpx.kt` 纯 Kotlin（RouteCheck 22 项
> JVM 断言全过）；路线推进锚点统一为 `MOCKLOC_ROUTE_STARTED`（档 0/2/3 同一纪元钟）；per-App 点位
> `MOCKLOC_APP.<pkg>` + 策略页「📍」；路线页（6 tab）+ GPX 导入导出；`MOCKLOC_NETCUT` 产品化（service.sh
> uid 解析 + iptables 注释规则 + uninstall 清刷 + CLI `--netcut`）。装机判据见 README C32 a–d。

| # | 任务 | 验收判据 |
|---|---|---|
| 2.1 | `Route.kt` + RouteCheck（JVM） | §4.5 数值判据全过 ✅ 本地 |
| 2.2 | per-App 独立点位（conf 键 + Profile + 策略页 UI） | 微信=北京、美团=上海同时读回；未配置 App 仍走全局 |
| 2.3 | 路线页 UI + 注入联动（档 0 与 root 模块双路推进） | 真机高德沿路线连续移动、速度栏非 0 |
| 2.4 | GPX 导入导出 + 收藏/历史 Store 迁移（数据版本化） | GoGoGo 导出的样例 GPX 导入成功；旧 prefs 升级不丢收藏（Store 统一迁到阶段四后段；本轮 GPX/路线已落地，收藏仍在 Places prefs——向后兼容，不阻塞） |
| 2.5 | `MOCKLOC_NETCUT`（档 2 按 uid 断网产品化，service.sh 解析包名→uid→iptables） | conf 填高德包名后，`appops get --uid` 的 WIFI_SCAN 停止增长且蓝点落注入点 |

### 阶段三 · 无痕一致性层（P2，全部档 3 内，每项独立开关）

> **进度（2026-10-28，v0.8.0）**：3.1–3.7 全部落地（除 CellInfo 伪造一项，明确不做，理由记录在案）。
> 新文件：`StackClean.kt` / `PkgHide.kt` / `GmsHooks.kt`；`Sensors.kt` 扩 WiFi 伪造 + 传感器冻结；
> `Sky.pseudorangeRateMps` 多普勒包络 + ADR 漂移 + NMEA 速度航向；`SelfTest` 泄露反推。
> conf 新键 `MOCKLOC_STACK_CLEAN`（默认开）/`MOCKLOC_HIDE_PKGS`（默认空）/`MOCKLOC_SENSOR_FREEZE`（默认关）/
> `MOCKLOC_FORGE_WIFI`（默认关）/`MOCKLOC_GMS`（默认开），全部进渲染器 + ConfigCheck（76 项）。
> 装机判据见 README C32 e–i。

| # | 任务 | 验收判据 |
|---|---|---|
| 3.1 | 栈清洗 StackClean | 判别测试：hook 进程内 `Throwable.getStackTrace` 无 `libxposed` 帧 |
| 3.2 | 泄露反推探测器（SelfTest 扩展） | 致盲关：诊断页显示"真实 AP x 个 / 基站 y 个"；致盲开：显示 0/0 或伪环境 |
| 3.3 | 多普勒非零模型（GnssStream + Sky 扩展） | 伪距率与仰角单调、静止时各星互不相同（包络对齐，非严格解算——已在代码注释写明） |
| 3.4 | 射频"伪造"模式（伪 AP；伪基站**明确不做**，版本雷区） | mock 检测类 App 读不到 0-AP 异常态 |
| 3.5 | 传感器静止化（加速度/陀螺/计步/磁力计偶极子） | 计步类 App 读数冻结；加速度模长 ≈ 9.81 |
| 3.6 | GMS fused hook（候选类名 try-load） | 有 GMS 真机：fused 路径读回注入点；无 GMS：Diagnostics 零新增 |
| 3.7 | PkgHide 白名单（默认空） | 配置过滤后 `getInstalledPackages` 不含目标包；未配置时零行为变化 |

### 阶段四 · UI 全量重做（P1.5，可与阶段二并行推进）

> **进度（2026-10-28，v0.8.0，有界版）**：暗色模式（DayNight 主题双套 + Ui 全 token 化）、风险评分卡
> （全域 16 维的可观测子集）、状态页配置区扩展已落地。**页拆分（pages/*）未做**——MainActivity 仍是
> 单文件（现 ~1200 行），拆分与底部导航重排属纯机械重构，放在路线/per-App 真机验收之后，避免在
> 未验证的交互上叠二次重构。装机判据见 README C32 j。

### 阶段五 · 扩展（P2+，视真机余量）

> **进度（2026-10-28，v0.8.0）**：本阶段**有意未开工**，理由与各条现状：
> - **Shizuku 档 1 App 内客户端**：README C29 结论不变——本机 Rish 服务未运行、`dev.rikka.shizuku:api`
>   13.1.5 的 `newProcess` 已包内可见（要走 AIDL/user-service），没有可验证路径不盲编；
> - **getRemotePreferences 通道**：需真机验 LSPosed 远程偏好的可靠性后再切，文件通道实测可靠，
>   实验开关形态保留在方案里；
> - **悬浮摇杆**：路线引擎已覆盖其主场景（沿路径移动），摇杆是交互增强，待 UI 拆分后评估；
> - **16 维风险评分完整版**：可观测子集已随阶段四落地（状态页风险卡），完整版依赖装机遥测。

### 明确不做（写死，防 scope creep）

- system_server 源头改写（`onReportLocation`）：风险不对等（软重启），维持 README 决定；
  AnyDoor 已验证的类名与思路记录在案，等有可快照的测试机再评估。
- 高德地图本体 native 引擎改写：Java 钩子结构上够不到（A14 已定性），路数就是①+②。
- IP 属地/收货地址/厂商基站库：定位层改不了，策略页已如实标注。
- 天下游路线（特权 helper 进程，腾讯乐固）：死路，不碰。

---

## 8. 风险与依赖

| 风险 | 缓解 |
|---|---|
| App 内 `su` 在部分 KernelSU 配置下不可用 | Injector 探测失败自动降级档 0，UI 标注原因（现 Tiers 探测模式） |
| BOOT 启动 FGS 在 Android 15+ 受限 | 降级方案：开机通知栏一键恢复按钮 |
| OpenTopoData 可用性 | 磁盘缓存 + 失败回退现值 + 超时 2s |
| per-App 点位键被旧版 root 模块丢弃 | 旧脚本按行读不认识的键会忽略，但 publish 是整文件拷贝——无风险；CLI 侧 mockloc.py 的白名单写入需同步加键（write_config 白名单） |
| 伪造射频环境的检测反噬 | 默认关 + 判别性实测后再考虑反转默认值（与致盲当初的引入路径相同） |
| UI 重做引入回归 | 每页拆分独立 + `tools/tests/test_manifest.py` 扩展断言（主题/权限/服务声明），UI 结构性错误 20ms 内暴露 |
