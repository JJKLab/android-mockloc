# 评估：阶段五「App 内 Shizuku 客户端」

> 结论先行：**技术上可行，但当前无法给出"可写"的判据**——三个前提都要真机验证，
> 按项目纪律（没有可验证路径的客户端不盲编）**暂不实施**。本文把要验证什么、怎么验证写清楚。

---

## 1. 目标与现状

**目标**：让 App 自己通过 Shizuku 拿到 shell/root 权限去执行 `cmd location`（即把现在的
`--mode adb` 从"必须接 PC"变成"App 内直接可用"），服务没有 root 的机器。

**现状**：
- 档 1 的**权限路径已实测可用**：`adb shell`（uid 2000）跑 `cmd location providers add-test-provider`
  在本机成功建出 `gps provider [mock]`，`identity=1000/android[LocationService]`；
  `--mode adb set/off` 全流程走通（README A15）。
- 但 **App 内客户端没做**：README C29 记录的原因是"本机 Shizuku 兼容层是 Stellar 带的 Rish
  （`moe.nb4a` 1.4.2），服务未运行，且 13.1.5 的 `Shizuku.newProcess` 已是包内可见
  （要走 AIDL 或 user-service）——没有可验证路径不盲编"。

## 2. 技术方案（如果做）

```
依赖：dev.rikka.shizuku:api + dev.rikka.shizuku:provider
权限：Shizuku.requestPermission(...)（用户授权对话框）
执行：Shizuku.newProcess(String[] cmd, String[] env, String dir) → Process
     或 Shizuku.exec 之类的封装，把现在 RootInjector/AdbInjector 的命令数组喂进去
```

要点：
- 权限模型：Shizuku 的 `Shizuku.requestPermission` + `Shizuku.checkSelfPermission`；
-  provider 声明：`android:authorities="rikka.shizuku.provider"` 的 ContentProvider
  （`ShizukuProvider`），用于给 Shizuku 服务发广播；
- 兼容 Rish：Rish 声称兼容 Shizuku API，但** binder action / 包名是否一致未验证**。

## 3. 本机（PJF110 / ColorOS）可行性

| 前提 | 状态 |
|---|---|
| shell uid 能建 test provider | ✅ 实测（A15） |
| shell 能拿到 mock app-op | ⚠️ 需先由 root/设置授一次（ColorOS 摘了 `MANAGE_APP_OPS_MODES`，shell 不能自授，A0） |
| **Rish 能否被 Shizuku API 绑定** | ❓ **未验证**——这是最大不确定点 |
| Shizuku 服务在运行 | ❓ 本机服务未运行（README 记录） |

→ 即便客户端写好，本机也要先有一个跑起来的 Shizuku/Rish 服务并授权，才能验。

## 4. 阻塞点（按优先级）

1. **Rish 兼容性**：Stellar 的 Rish（`moe.nb4a`）是否实现 Shizuku 的 binder
   （action `moe.shizuku.privileged.api.action...` / provider authority `rikka.shizuku`）。
   不兼容就要走 Rish 自己的 API（`moe.nb4a` 的 AIDL），那是另一套。
2. **`Shizuku.newProcess` 的可见性**：13.1.5 里它不是公开 API 的话，要么反射、要么用
   `Shizuku.execCommand`（若有）、要么走 user-service。
3. **验证环境**：需要真机 + Shizuku 服务运行 + 用户授权。

## 4.5 本地核查结果（2026-11-20，已做，不需手机）
把 `dev.rikka.shizuku:api:13.1.5` 和 `provider:13.1.5` 的 AAR 拉下来 `javap` 看签名：

```java
// rikka.shizuku.Shizuku —— 公开面
public static IBinder getBinder();
public static void transactRemote(Parcel, Parcel, int);   // 裸 binder transact
public static int getUid();                               // 2000 或 0
public static void bindUserService(UserServiceArgs, ServiceConnection);
public static int peekUserService(...); unbindUserService(...);
public static int checkSelfPermission(); requestPermission(int); getVersion();
// ❌ newProcess(String[], String[], String) 是 **private static**
```
- **`Shizuku.newProcess` 是 private**（README C29 的说法得到证实），客户端拿不到。
- `ShizukuRemoteProcess` 的构造也是包内可见，外部无法直接构造。
- `provider` AAR 只是个 manifest 标记（`moe.shizuku.client.V3_SUPPORT=true`），没有组件。

**所以 App 内客户端只有两条路**：
1. **裸 transact**：`Shizuku.transactRemote()` + 自己按 `moe.shizuku.server.IRemoteProcess`
   的 AIDL 拼 Parcel（transaction code 随版本变，属于逆向 + 脆弱）；
2. **user-service**：`bindUserService` 让 Shizuku 在**你自己进程**里起一个 Service——
   但代码跑在你进程（你的 uid），并不能直接拿到 shell uid 去 exec。

→ 结论：**"App 内 Shizuku 执行命令"在 13.1.5 的公开 API 里没有干净路径**；
要么逆向 AIDL（脆弱、随版本坏），要么绕 user-service（拿不到 shell uid）。

## 5. 建议

- **先做一步零成本的本地核查**：把 `dev.rikka.shizuku:api` 的 AAR 拉下来，确认
  `newProcess` / `bindService` 的签名与可见性，以及它依赖的 provider authority——
  这一步不需要手机。
- **真机验证清单**（等手机）：装官方 Shizuku（或确认 Rish 兼容）→ 起服务 → 授权 →
  App 内跑一次 `cmd location providers add-test-provider gps` → `dumpsys` 看 `[mock]`。
- **在验证通过前不实施**：否则又是一条"没有判据的路径"。

## 6. 附带价值
即便本机用不上（app-op 被摘），App 内 Shizuku 客户端对其他**没摘 app-op 的 ROM** 有直接价值
（免 root 全局假位置），所以这条不是白做——只是**必须先验证**。

## 7. 结论（2026-11-20）
**建议不实施**，理由按权重：
1. 公开 API 没有干净的"执行命令"路径（`newProcess` private；`transactRemote` 要自己拼 AIDL）。
2. 本机 Shizuku 兼容层是 Rish，它是否实现同一套 `moe.shizuku.server.*` AIDL **未验证**。
3. 本机 ColorOS 摘了 `MANAGE_APP_OPS_MODES`，档 1 在这台机器上本来就拿不到 app-op（A0），
   App 内客户端在本机**无收益**；收益只对"没摘 app-op 的 ROM + 官方 Shizuku"成立。
4. 项目纪律：没有可验证路径的客户端不盲编。

**替代**：档 1 继续由 PC 侧 `--mode adb` 承担（已实测可用）；免 root 场景用档 0
（`--mode app`，本机可用）。
