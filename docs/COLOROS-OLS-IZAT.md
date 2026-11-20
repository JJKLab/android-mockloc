# ColorOS 系统定位链路分析（OLS → 高通 IZat）

> 目的：搞清 OPPO/一加 的「系统定位」到底怎么算出来的、边界在哪、能从哪里影响它。
> 工具：REA 6.7.0（`npx rea-agents`）+ JDK 21 + `jadx-headless-mcp-0.7.1-all.jar`。
> 对象：`com.oplus.location`（OLS）、`com.qualcomm.location`（高通 IZat），均为系统应用。

---

## 0. 工具链（可复现）

```bash
# JDK 21（REA 需要完整 JDK 17+，含 jdk.compiler）
# jadx MCP 桥（REA 不代下载）：
#   https://github.com/1013503897/jadx-headless-mcp/releases/download/v0.7.1/jadx-headless-mcp-0.7.1-all.jar
export REA_JADX_MCP_JAR=/abs/path/jadx-headless-mcp-0.7.1-all.jar
export PATH=/abs/path/jdk-21/bin:$PATH
npx rea-agents doctor          # jadx 组件必须 ok
npx rea-agents inspect-android-package <apk> --format md
npx rea-agents inspect-android-class  <apk> <fqcn> --format md
npx rea-agents inspect-android-method <apk> <fqcn> <method> --format md
```
> `analyze` 只吃**可执行文件**（Ghidra/Hopper/IDA/Kuna），不吃 APK 归档；APK 用上面三条
> `inspect-android-*`（jadx 后端）。

---

## 1. OLS（`com.oplus.location`）组件地图

来源：`rea inspect-android-package`（3883 个类）。

| 组件 | 作用 |
|---|---|
| `nlp.NetworkLocationService`（action `com.android.location.service.v2/v3.NetworkLocationProvider`） | **网络定位提供方**：system_server 的 `network` provider 就是它 |
| `nlp.GeocoderService`（action `com.android.location.service.GeocodeProvider`） | 地理编码 |
| `fused.FusedLocationService`（action `com.android.location.service.FusedLocationProvider`） | 融合定位提供方 |
| `OplusLocationService`（action `com.oplus.location.ServiceProvider`） | 对外「系统定位」binder（`onBind` 返回 `X4.h.m()`） |
| `geofence.GeofenceService` | 地理围栏 |
| `garage.*` / `detect.*` / `privacy.*` / `statistics.*` | 车库检测、干扰检测、隐私、统计 |
| `gnsstoolbox.*`（一大批 Activity） | 工程/售后工具：卫星信号、干扰扫描、功耗、Feature 校验、**Qcom NV 配置** |

权限里最关键的两个：
```
com.qualcomm.qti.permission.ACCESS_PRECISE_LOCATION_API   ← 精确位置 API（IZat）
com.qualcomm.qti.permission.ACCESS_SV_CONFIG_API          ← 星历/卫星配置 API
com.oplus.location.permission.provider                     ← OLS 自己的对外权限
android.permission.LOCATION_HARDWARE
```

## 2. 高通 IZat（`com.qualcomm.location`）组件地图

| 组件 | 作用 |
|---|---|
| `LocationService` | 定位服务主体 |
| `izat.IzatService`（AIDL `com.qti.izat.IIzatService`） | IZat 引擎的对外接口 |
| `.izat.IzatService$IzatServiceShutdownHandler` | 关闭处理 |
| `utils.IZatServiceContext` / `policy.SessionPolicyManager` | 引擎上下文与会话策略 |

IZat 是高通的原生定位引擎（含 `libizatCore.so`、`libloc_api_*` 等 native 库），Java 层只做
会话/策略/数据源编排，**真正的解算在 native**。

---

## 3. 链路

```
App（如银行/地图）
  │  ① Android LocationManager（gps/fused/network）
  │  ② ColorOS「系统定位」：android.location.OplusLocationManager → OLS ServiceProvider
  ▼
OLS（com.oplus.location）
  ├─ NetworkLocationService  → 注册为 Android 的 network provider
  ├─ FusedLocationService    → 注册为 fused provider
  └─ 通过 .oplus.OplusLbsConnection 连到 ↓
      高通 IZat（com.qualcomm.location，原生引擎）
        └─ 数据源：modem 基站 / Wi-Fi / GNSS，由 IZat native 解算
```

**关键判断**：OLS 的 Java 层可读、可 hook；但**位置的最终解算在 IZat 的 native 引擎**，
它直接吃调制解调器/射频数据，不经过 Android 的 `WifiManager`/`TelephonyManager` Java 面。

## 4. 对「改定位」的含义

| 层 | 能否影响 | 说明 |
|---|---|---|
| Android `LocationManager`（我们的档 0/2/3 + SystemWash） | 只影响读 Android provider 的 App | 对走 OLS/IZat 的 App 无效（实测：注入哈尔滨，银行 App 仍显示真实城市） |
| OLS Java 面（`NetworkLocationService` 等） | 可 hook（OLS 未加固） | 实测钩 `getAllCellInfo/getNetworkOperator/isMock` 后城市不变 → 数据源不在这层 |
| IZat Java 面（`izat.IzatService` 等） | 可 hook（未加固） | 只是会话/策略层，解算在 native |
| IZat native 引擎 | **未验证** | 需要 native 注入（Dobby/inline hook），超出本仓库 Java 模块能力 |

## 5. IZat 的内部结构（REA 已反编译）

`IIzatService` 是一组**子服务的入口**（`rea inspect-android-class com.qti.izat.IIzatService`）：

| 子服务 | 用途 |
|---|---|
| `getFlpService(): IFlpService` | 融合定位 |
| `getPrecisePositionService(): IPrecisePositionService` | 精确位置 |
| `getGnssConfigService(): IGnssConfigService` | 星历/卫星配置 |
| `getGeofenceService(): IGeofenceService` | 地理围栏 |
| `getGTPService(): IGTPService` | 地理触发 |
| **`getWiFiDBProvider()` / `getWiFiDBReceiver()`** | **Wi-Fi 数据库（网络定位的输入）** |
| **`getWWANAdReceiver(): IWWANAdReceiver`** | **WWAN=蜂窝，基站数据的接收端** |
| `getAltitudeReceiver()` | 高度 |
| `getTestService(): ITestService` | 测试接口 |
| `getDebugReportService()` / `getVersion()` | 调试 |

**含义**：IZat 引擎的「网络定位」输入就是 **Wi-Fi 库 + 蜂窝（WWAN）**，这两条都是
独立于 Android `WifiManager`/`TelephonyManager` 的通道（所以钩 Android 的 Java 面没用）。

## 6. IZat 的服务面（REA 反编译结果）

### 6.1 输出面（引擎 → 客户端）
`com.qti.flp.IFlpService`（FLP = Fused Location Provider）：
```
registerCallback(int, int, ILocationCallback, long)   // 注册取位置
pullLocations(ILocationCallback, long, int): int      // 拉位置
startFlpSession(int, int, long, int, long): int       // 起会话
startFlpSessionWithPower(...) / updateFlpSession(...) / stopFlpSession(int)
registerForSessionStatus(int, ISessionStatusCallback)
```
→ 这是 IZat 引擎**对外供位置**的接口。客户端（OLS / 框架）从这里取位置。

### 6.2 数据面（喂给引擎）
| 接口 | 方法 | 方向 |
|---|---|---|
| `com.qti.wifidbprovider.IWiFiDBProvider` | `requestAPObsLocData()`；`registerResponseListener(IWiFiDBProviderResponseListener, PendingIntent)` | IZat 向客户端**要** AP 观测数据 |
| `…IWiFiDBProviderResponseListener` | `onApObsLocDataAvailable(List<APObsLocData>, int)` | 客户端**回** AP 观测 |
| `com.qti.wwanadreceiver.IWWANAdReceiver` | `registerRequestListener(IWWANAdRequestListener)`；`pushWWANAssistanceData(int, boolean, int, byte[])` | 双向：IZat 请求/推送 WWAN 辅助数据 |
| `…IWWANAdRequestListener` | `onWWANAdRequest(int, byte[])` | IZat **向客户端要** WWAN 数据 |

### 6.3 OLS 侧的 binder
- `com.oplus.location.nlp.NetworkLocationService.onBind` → `e.c().getBinder()`（Android 的
  `network` provider 实现）。
- 高通侧 `com.qualcomm.location.oplus.OplusLbsConnection.onBind` → `mIOplusLbs`（连到 OLS）。

## 7. 结论：口子在哪、能不能用
| 口子 | 位置 | 可 hook？ |
|---|---|---|
| `IFlpService.registerCallback/pullLocations`（拿位置） | `com.qualcomm.location` 进程 | **LSPosed 从不挂钩该进程**（实测 0 条）→ Java 模块进不去 |
| `IWWANAdReceiver` / `IWiFiDBProvider`（喂数据） | 同上 | 同上 |
| OLS `NetworkLocationService`（Android network provider） | `com.oplus.location`（可挂钩） | 可挂钩，但实测钩它的 Java 面不影响城市 |

**所以**：能影响 IZat 的两个口子（输出 `IFlpService`、输入 `IWWANAd*/IWiFiDB*`）都在
`com.qualcomm.location` 进程里，而 **LSPosed 不挂钩它**——Java 层到此为止。
要么让 LSPosed 能挂它（框架层），要么做 native 注入。
