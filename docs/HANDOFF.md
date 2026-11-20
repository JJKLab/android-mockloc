# 接手指南（HANDOFF）

> 给下一个碰这个仓库的人。写于 2026-11-20，基于 PJF110 / Android 16 / KernelSU 的真机结论。
> 详细报告在 `docs/AUDIT-2026-11-18.md`、`docs/COLOROS-OLS-IZAT.md`、`docs/TEST-PLAN-bank-apps.md`。

---

## 1. 这是什么

Android 虚拟定位工具（`com.mockloc`）。四档注入：

| 档 | 机制 | 需要 |
|---|---|---|
| 0 | App 内 `LocationManager` test provider | 授「虚拟定位应用」给 mockloc |
| 1 | Shizuku(ADB) shell 注入（uid 2000） | `--mode adb`，接 PC |
| 2 | root 守护循环（`rootmodule/`） | 刷模块（KernelSU/Magisk） |
| 3 | LSPosed 进程内 hook | 勾选作用域（微信/高德等） |
| — | `SystemWash`：system_server 洗 mock 位 | root + LSPosed + 作用域含 `system` |

## 2. 怎么构建（重要）

- **不要在本地编译**：本机没有 Android SDK / JDK17+ / gradle。
- 推 `main` → GitHub Actions（workflow `394990499`）→ 产物 `mockloc-apk` / `mockloc-root-module`。
- 仓库公开（`JJKLab/android-mockloc`）。下载产物需要 token：用本机 git 凭据调
  `GET /repos/.../actions/artifacts/{id}/zip`（`gh` 未安装）。

## 3. 本地判据（不需要手机，提交前必跑）

```bash
sh .toolchain/tc.sh                 # 全量 Kotlin 类型检查（含 pages/）→ 期望 0 error
sh .toolchain/runcheck.sh           # 7 个 JVM 校验器 → 期望全 ALL PASS
python tools/tests/test_coords.py
python tools/tests/test_manifest.py
python tools/tests/test_renderer_contract.py   # 渲染器↔Config↔CLI 键契约
python tools/tests/test_rootmodule_sync.py     # root 脚本写法守卫
```
> 新增 `MOCKLOC_*` 键时必须**同时**加进 `ModuleBridge.render()` 和 `Config`，否则契约测试红。

## 4. 真机验收（PJF110 / Android 16 / KernelSU + Stellar + LSPosed）

**环境**：LSPosed 管理器是 **LSPosed 本体**（**不是 Momo，也不是 QAuxiliary**）。
**Stellar 也不是 LSPosed 管理器**，它带的 Shizuku 兼容层是 Rish（`moe.nb4a`）。

**已验通过**（2026-11-19/20）：
- 路线推进（不再被自愈拉回）、注入三 provider `[mock]`、NETCUT 双栈、暂停/继续、
  16 维风险卡、`SystemWash` 挂载 + 行为（读回 `mock=false`）。
- 真机才抓到的 6 个坑全部已修（见 §6）。

**未验 / 待办**：见 §7。

## 5. 硬边界（别重复踩）

1. **ColorOS「系统定位」= OLS → 高通 IZat（原生）**。Android 定位路线（档 0/2/3 + SystemWash）
   影响不了走 OLS 的 App。`com.qualcomm.location` 带 `android:persistent="true"`，
   **LSPosed 不挂钩 persistent 应用**（实测 0 条加载记录）。→ 详见 `docs/COLOROS-OLS-IZAT.md`。
2. **银行类加固 App**（如阳光惠生活 `com.ebank.creditcard`，梆梆 DexP）：勾任何 Xposed 模块
   都会让 `:bank.creditcard` 子进程 `SIGSEGV`（`fault addr 0x79c`）。与本模块无关（换无关模块也崩）。
3. 阳光惠生活的「系统定位」实测**不跟随注入**（设哈尔滨/乌鲁木齐/三亚都显示真实城市），
   且 `SystemWash` 也不改变 → 它的城市由厂商引擎决定。
4. `setTestProviderLocation` 注入的位置**必然** `isMock()=true`（官方文档）；洗位只能靠
   SystemWash（system_server 层）。

## 6. 本轮（2026-11-19/20）修的东西

| 修复 | 文件 |
|---|---|
| 路线/抖动统一成唯一「应喂点」；巡检判据改用应喂点 | `Config.kt` / `MockService.kt` |
| 致盲 WiFi/基站/BLE 与掐网拆独立键 | `ModuleBridge.kt` / `pages/PolicyPage.kt` |
| NMEA 改 UTC；PkgHide 补 API33+ Flags；栈清洗补指纹 | `Nmea.kt` / `PkgHide.kt` / `StackClean.kt` |
| 泄露反推读钩子前原始面；GNSS 时钟基准自洽 | `SelfTest.kt` / `GnssStream.kt` |
| SDK 钩子走 per-App 输出坐标系 | `AmapSdk.kt` / `TencentSdk.kt` |
| `uninstall.sh` 双栈清理；CLI `watch` 修 provider=None | `rootmodule/` / `tools/mockloc.py` |
| CI 加渲染器键契约；update-binary 固定 commit | `.github/workflows/build.yml` |
| MainActivity 2027→534（拆 pages/）；16 维评分；悬浮摇杆 | `pages/` / `RiskScore.kt` / `Joystick.kt` |
| **`SystemWash`**：system_server 拦 `Location.writeToParcel` 抹 mock 位 | `SystemWash.kt` / `ModuleEntry.kt` |
| **NETCUT 缓存缺口**：名单没变也要确认规则还在 | `rootmodule/mockloc-lib.sh` |
| **PC inbox 优先**：消费 inbox 的那一轮让 App 偏好让路 | `rootmodule/mockloc-lib.sh` |

## 7. 待办 / 已知未做

- [ ] **Shizuku 档 1 App 内客户端**：`Shizuku.newProcess` 在 13.1.5 是 **private**，公开面只有
      `transactRemote` / `bindUserService`。加上本机 Rish 兼容性未验证 → **评估结论：暂不实施**
      （`docs/ASSESS-shizuku-inapp.md`）。
- [ ] `getRemotePreferences` 通道：文件通道（conf）已可靠，远程偏好待真机验。
- [ ] IZat 原生层（`IFlpService` 输出、`IWWANAd*/IWiFiDB*` 输入）——persistent 进程进不去。
- [ ] C31/C32 余下装机判据（见 `docs/TEST-PLAN-bank-apps.md` 第 2 节）。
- [ ] 路线/GPX/per-App 双点等装机项。

## 8. 工具与产物位置

| 东西 | 位置 |
|---|---|
| 全量走查报告 | `docs/AUDIT-2026-11-18.md` |
| ColorOS 定位链路分析 | `docs/COLOROS-OLS-IZAT.md` |
| 银行 App 测试方案/结论 | `docs/TEST-PLAN-bank-apps.md` |
| Shizuku 评估 | `docs/ASSESS-shizuku-inapp.md` |
| 计划 | `docs/REFACTOR-PLAN.md`（含 §9 真机走查、§10 系统层边界） |
| 本地类型检查 | `.toolchain/tc.sh`（**已含 `pages/`**） |
| JVM 校验器 | `.toolchain/runcheck.sh` |
| 真机事务留痕 | `.tmp/tx-*`（每次改动一份基线/diff/验证/回滚脚本） |
| REA 工具链 | `.tmp/rea/tools/`（JDK 21 + jadx-headless-mcp 0.7.1） |

## 9. 接手第一步建议

1. 跑一遍 §3 的本地判据，确认全绿；
2. 推 `main` 出包，装到已 root + LSPosed 的机器；
3. 按 `docs/TEST-PLAN-bank-apps.md` 第 2 节跑装机判据；
4. 想清楚 §7 里哪一项值得投入（个人建议：都不急，先把已修的回归稳）。
