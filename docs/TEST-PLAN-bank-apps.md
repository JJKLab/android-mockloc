# 银行类 App（阳光惠生活 / 买单吧）闪退：结论与方案

> 目标：让「阳光惠生活」吃假位置。
> 设备：PJF110 / Android 16 / KernelSU + Stellar(Shizuku) + LSPosed。

---

## 结论（2026-11-19 真机定性）

**这类 App 用 LSPosed 方案必崩，且与本模块无关——是加固壳检测 LSPosed 框架本身。**

阳光惠生活 = `com.ebank.creditcard`（启动 `.MainActivity`），加固为**梆梆 DexP / Quinoxless**
（进程内加载 `libPassGuard.so`、`libdexjni.so`、`libcrashsdk.so`、`libsgmainso-*.so`）。

### 三组对照实验（同一个 `fault addr 0x79c` 的 SIGSEGV）
| 作用域 | 结果 |
|---|---|
| 不勾任何模块 | 正常启动 |
| 勾 **mockloc** | `:bank.creditcard` 子进程 SIGSEGV |
| 勾 **com.fankes.tsbattery**（完全无关的模块） | **一样崩**，同一地址 |

→ 触发条件是「LSPosed 往进程里注入模块」这件事本身，不是我们的代码。

### 排除过的一条线索
我们的模块曾打包 `hiddenapibypass`，它创建的 `org.lsposed.hiddenapibypass.CoreOjClassLoader`
确实会被加固壳枚举到并告警：
```
W bank.creditcard: Unsupported class loader: java.lang.Class<org.lsposed.hiddenapibypass.CoreOjClassLoader>
```
但**移除它之后崩溃依旧**（tsbattery 组更是从来没有这个类加载器）。所以它只是众多检测向量之一，不是根因。
（该移除会让 `GnssStream`/`AmapSdk` 的隐藏 API 反射失效，已回退。）

### 纯 root（档 0/2）也不行
把注入点设成哈尔滨 / 乌鲁木齐，`dumpsys location` 显示三个 provider 都是 mock，
App 也**确实收到 1 个定位**，但界面仍弹「系统定位到您在**清远**」（真实位置）。
→ App 读到 `isFromMockProvider` 后丢弃，走自己的定位链路。

---

## 可选出路（按可行性）

1. **放弃用 LSPosed 改这类 App**（最现实）。加固壳 vs Xposed 是框架级对抗，模块内无解。
2. **框架层隐藏 LSPosed**：让 LSPosed 对该进程不可见（随机包名 / 隐藏桥 / 换 Zygisk 实现），
   超出本仓库范围。
3. **换非 Xposed 的 hook 方案**（Frida / 原生 inline hook）：同样可能被加固壳检测，且更重。
4. **脱壳后重打包**：去掉反 hook 检测。成本最高，且涉及 App 条款，不建议。

---

## 日志怎么拿（复现用）
```bash
adb logcat -c
adb logcat -v time | grep --line-buffered -E "\[mockloc\]|LSPosedFramework|Unsupported class loader|Fatal signal|libc"
adb shell su -c 'tail -200 /data/adb/lspd/log/modules_*.log'      # 作用域是否生效
adb shell su -c 'ls -t /data/tombstones/ | head'                 # 原生崩溃栈
```
关键判据：崩溃前是否出现 `Unsupported class loader`、`[mockloc] attached to ...`、
以及 tombstone 的 `backtrace`（崩在哪个 `.so`）。

## 设备状态
- 作用域已还原：`com.android.phone` / `com.autonavi.minimap` / `com.tencent.mm` / `system`。
- 银行 App 已从作用域移除（避免每次启动都崩）。

---

## 补充：那「清远」到底是哪来的（2026-11-19 夜）

| 实验 | 结果 |
|---|---|
| 注入点 = 哈尔滨 / 乌鲁木齐 | App 城市始终是「清远」，不变 |
| 关掉 Wi-Fi（走移动数据） | 仍是「清远」 |
| App 的 gps 注册 | 只持续 876ms，`locations = 0`（没从 provider 拿到定位） |

→ 「系统定位」**不读 Android LocationManager**（既不读 test provider，也不随网络切换变）。
最可能是 **IP 归属地**（ISP 注册地），其次是 OPPO OLS 的基站/IP 引擎。

### 由此的可行替代
- **VPN / 代理**：用目标城市出口 IP，看它的「系统定位」是否跟着变。这是目前唯一
  不需要 root/Xposed 的路子（也是唯一可能奏效的）。
- 若它走的是**基站**归属地，则普通 VPN 也没用。

### 结论
对这类「梆梆加固 + 不用系统定位」的银行 App，本工具（无论档 0/2/3）都改不动它的城市。
建议：**别对它勾 LSPosed**（会崩），改用 VPN 试 IP 归属地。

---

## 2026-11-19 夜 · SystemWash 落地 + 「清远」真凶

### 已实现并真机验证：system_server 洗 mock 位（`SystemWash`，默认开）
- `setTestProviderLocation` 必然打 mock 位（官方文档），银行/支付类 App 读到就丢弃。
- 之前一直挂不上 system_server，是因为 **system_server 走 `onSystemServerStarting` 回调**，
  而我们只重写了 `onPackageReady`（实测后者在 system_server 里从不被调用）。
- 现在在 system_server 拦 `Location.writeToParcel`：只把 `isMock()==true` 的位置写成
  **去掉 `HAS_MOCK_PROVIDER_MASK` 位**的副本再跨 Binder；真实定位原样放行；失败回退原位置。
- **真机验证**：注入 45.8038,126.5349（哈尔滨），mockloc App 读回：
  `gps/fused/network/passive : 45.803800,126.534900 acc=5.0 mock=false`。

### 但阳光惠生活的城市仍不跟——真凶是 ColorOS OLS
- `dumpsys location` 证明 App **确实收到了**我们的定位：
  `gps provider delivered location[1] to 10414/com.ebank.creditcard`（且已洗成 mock=false）。
- 但界面仍弹「系统定位到您在**清远**」。
- **对照实验**：`pm disable-user com.oplus.location`（+ locationproxy）→ 「系统定位到清远」
  提示**消失**；`pm enable` 复启 → 提示**回来**。
  → 它的「系统定位」= **ColorOS 系统定位服务（OLS，`com.oplus.location`）**，不是 Android
  LocationManager。OLS 用自己的基站/Wi-Fi/IP 引擎，我们改不动。

### 由此解释「模拟器可用」
模拟器上没有 OLS，App 回退到 Android LocationManager → 我们（或任何虚拟定位）就能生效。
真机（ColorOS）优先走 OLS，所以注入没用。

### 结论与选项
- 对**检查 `isMock()`** 的 App：`SystemWash` 是通用解（已验证）。
- 对**走厂商定位服务**的 App（如阳光惠生活）：Android 定位路线无效；要么 hook OLS
  （它跑在 `android` 进程里，可在 system_server 层 hook），要么接受。

---

## 2026-11-19 深夜 · 挖到厂商定位引擎这一层

### 链路
```
阳光惠生活 → ColorOS「系统定位」(OLS, com.oplus.location) → 高通 IZat (com.qualcomm.location, 原生引擎)
```
- OLS 是独立应用（uid 10222，`NetworkLocationService`），对外暴露
  `OplusLocationService` / `NetworkLocationService` / `GeocoderService`。
- 它通过 `.oplus.OplusLbsConnection` 连到 `com.qualcomm.location` 的 IZat 引擎。

### 已排除
| 尝试 | 结果 |
|---|---|
| 注入 Android gps/network/fused + SystemWash 洗 mock 位 | App **确实收到**（`gps provider delivered location[1]`，`mock=false`），城市不变 |
| 把模块挂进 OLS 进程（钩子全部命中：`getAllCellInfo ×80`、`getNetworkOperator ×160`、`isMock ×300`） | 城市不变 → OLS 的 Java Wi-Fi/基站面不是它的数据源 |
| 把模块挂进 `com.qualcomm.location` | **LSPosed 从不挂钩它**（0 条记录，进程重启也没用）→ 原生/常驻服务，Java 模块进不去 |

### 结论
银行 App 的「系统定位」来自 **ColorOS OLS → 高通 IZat 原生引擎**。Android 定位这条线
（档 0/2/3 + SystemWash）到此为止；再往下是原生 hook，超出本模块（Java 层）的能力。

### 最后一条 Java 级线索（未试）
OLS 对外服务里有 `getCoarseLocation` / `getCurrentLocation` / `getLastKnownLocation`
等返回 Location 的方法。如果银行 App 是直接调 OLS 的 service，可以在 **OLS 进程**里拦这些
方法返回我们的点。前提是它确实走 service 而不是框架 `android.location.OplusLocationManager`
（后者在 App 自己进程里，挂钩=档3=被加固壳打崩）。

### 设备状态
- 作用域已还原（`com.android.phone` / `com.autonavi.minimap` / `com.tencent.mm` / `system`）。
- conf `MOCKLOC_ENABLED=false`。

---

## 2026-11-20 · 最终边界：IZat 进程是 persistent，LSPosed 挂不进去

REA（`npx rea-agents`，已配 JDK 21 + `jadx-headless-mcp-0.7.1`）把 OLS 与 IZat 的反编译结果对齐后，
链路和边界都确定了：

### IZat 的接口面（反编译）
- 输出：`com.qti.flp.IFlpService`（`registerCallback` / `pullLocations` / `startFlpSession` …）
- 输入：`IWWANAdReceiver`（`pushWWANAssistanceData`）、`IWiFiDBProvider`（`requestAPObsLocData`）
- OLS 侧：`NetworkLocationService`（Android network provider）、`OplusLocationService`

### 硬边界（已确认，非推测）
`com.qualcomm.location` 的 manifest 带 **`android:persistent="true"`**（还有 `directBootAware`）。
persistent 应用由系统在开机最早期拉起，**LSPosed 按设计不挂钩它们**——实测：勾上作用域、
`kill -9` 重启进程，LSPosed 仍然 0 条加载记录。

→ 能影响 IZat 的两个口子（输出 `IFlpService`、输入 `IWWANAd*/IWiFiDB*`）都在这个进程里，
**Java 层（本仓库模块）到此为止**。继续只有两条：
1. 让 `com.qualcomm.location` 不再 persistent（改系统包，需替换 `/system_ext/priv-app` 里的 APK）；
2. native 注入（Dobby/inline hook）——超出本仓库能力。

（详见 `docs/COLOROS-OLS-IZAT.md`。）

---

## 2026-11-20 · 光大信用卡 App（阳光惠生活）实测成效

**结论：基本不生效。** 它的「系统定位」不来自 Android LocationManager。

| 注入点 | App「系统定位」提示 |
|---|---|
| 北京 39.90,116.41 | 北京（唯一一次吻合，疑为首次读到的缓存被粘住） |
| 哈尔滨 45.80,126.53 | 清远（真实位置） |
| 乌鲁木齐 43.83,87.62 | 清远 |
| 三亚 18.25,109.51 | 清远 |

**证据**：
- App 确实收到注入：`dumpsys location` → `gps provider delivered location[1] to 10414/com.ebank.creditcard`；
- `SystemWash` 已生效（跨 Binder 后 `isMock()=false`），仍不改变它的城市；
- 等 45s、冷启动多次，提示都不刷新（粘在「清远」）。

**判读**：城市由 **ColorOS OLS → 高通 IZat** 决定（见 `docs/COLOROS-OLS-IZAT.md`），
Android 定位路线（档 0/2/3 + SystemWash）**到顶，无效**。那次「北京」是它首次读到的值被缓存，
不是跟随。

**归档**：整体交接见 `docs/HANDOFF.md`。
