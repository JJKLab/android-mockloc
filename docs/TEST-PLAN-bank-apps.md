# 银行类 App（阳光惠生活 / 买单吧）闪退：结论与方案

> 目标：让「阳光惠生活」吃假位置。
> 设备：PJF110 / Android 16 / KernelSU + Stellar(Shizuku) + QAuxiliary(LSPosed)。

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
