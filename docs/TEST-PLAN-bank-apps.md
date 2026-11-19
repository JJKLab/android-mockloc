# 银行类 App 闪退：日志配置 + 真机测试方案

> 目标：定位并缓解「LSPosed 勾选 mockloc 后，阳光惠生活 / 买单吧 闪退」。
> 设备：PJF110 / Android 16 / KernelSU + Stellar(Shizuku) + QAuxiliary(LSPosed 管理器)。
> 已复现：`com.cebbank.yaoyao` 勾上 mockloc → 启动 ~1.4s `Fatal signal 11 (SIGSEGV)`，
> 且**在模块 onPackageReady 之前**（无 `[mockloc] attached to ...`）→ 原生反 hook 检测。

---

## 1. 日志怎么拿（按重要性）

### 1.1 实时 logcat（最关键，边操作边抓）
```bash
# 先清空，再只保留相关行（推荐）
adb logcat -c
adb logcat -v time | grep --line-buffered -E "\[mockloc\]|LSPosedFramework|cebbank|bankcomm|libc|FATAL|tombstoned|ActivityManager"

# 或者全量落盘，事后慢慢筛（更稳，推荐同时进行）
adb logcat -v time > D:\code\GPS\.tmp\test\mockloc-test.log
```

**关键字对照**
| tag / 关键字 | 含义 |
|---|---|
| `[mockloc]` | 我们模块自己的日志（`attached to <pkg>`、各组件 init、投递、失败） |
| `LSPosedFramework` | LSPosed 框架、作用域加载（格式 `(pkg)[模块,...]`） |
| `libc` / `FATAL` / `tombstoned` | 原生崩溃 |
| `ActivityManager: Process ... has died` | 进程死亡 |

### 1.2 LSPosed 模块日志（确认作用域是否真的生效）
```bash
adb shell su -c 'tail -200 /data/adb/lspd/log/modules_*.log'
```
看到 `(com.cebbank.yaoyao)[com.mockloc,...]` 才说明模块真的挂进了目标进程；
**没有这行**说明崩在挂载之前（当前就是这种）。

### 1.3 Tombstone（原生崩溃栈，定位是哪个 .so）
```bash
adb shell su -c 'ls -t /data/tombstones/ | head'
adb shell su -c 'cat /data/tombstones/<最新那个>'
```
重点看：`signal`、`Abort message`、`backtrace`（崩在哪个 `lib*.so`）、`#00..#10 pc`。

### 1.4 App 内诊断（辅助）
- 状态页 → 「诊断详情」：provider 读回、模块配置、本进程挂钩记录。
- 策略页 → 「免 root 档自检」三枚徽章。
- 确认 conf 与 App 一致（状态页顶部有「⚠ 不一致」提示）。

### 1.5 让日志更全的配置（建议先做）
1. 打开 App 的**探针**（策略页/状态页的 probe 开关）→ 会记录原始射频证据，便于判断模块有没有真跑起来。
2. 状态页展开「诊断详情」。
3. 用 App 内「同步到模块」或 `python tools/mockloc.py sync`，确保 conf 最新。
4. 保持 logcat 常开（1.1 的全量落盘），事后把 `.tmp\test\mockloc-test.log` 给我。

---

## 2. 测试方案（分阶段，每步都有预期与判据）

### 阶段 0 · 基线：不勾 mockloc
- LSPosed 里**不要**勾 阳光惠生活 / 买单吧。
- 启动 App，确认正常。
- **判据**：App 能正常进。→ 证明崩溃是「勾了 mockloc」引起，而非 App 自身。

### 阶段 1 · 只加作用域、不注入
- 勾上 mockloc → 启动 App。
- **预期**：很可能崩（当前症状）。
- **要拿的**：1.1 的 logcat + 1.3 的 tombstone。
- **判据**：
  - 崩，且**没有** `[mockloc] attached to ...` → 崩在挂载前（加固/反 hook 检测）。
  - 崩，但**有** `attached to ...` → 崩在我们的组件 init（可修）。

### 阶段 2 · 定位崩溃层（关键一步）
- 看 tombstone 的 `backtrace`：崩在哪个 `.so`（加固壳 / 安全 SDK / linker）。
- 看 logcat 里 `LSPosedFramework` 有没有打印作用域行。
- **目的**：区分「加固壳检测到 Xposed」还是「我们的 hook 触发」。

### 阶段 3 · 缓解手段（逐个试，每次只改一个变量）
1. **缩 hook 面**：只挂 `LocationManager` 相关，其余全关 → 看是否还崩。
2. **延迟挂载**：不在 `onPackageReady` 挂，改到首次定位回调里挂。
3. **反检测**：隐藏 Xposed/LSPosed 痕迹（类加载器、`XposedBridge`、栈帧）。
4. **换作用域**：只勾 `system` + 目标 App，不勾 `com.android.phone` 之类。
5. **兜底**：不勾 LSPosed，只用 root 管线（档 0/2）——银行 App 自带定位引擎时本来就推不动，
   但至少不崩。

### 阶段 4 · 验收
- 勾上后能进 App、不崩。
- 状态页「诊断详情」里 provider 读回 `mock=true`。
- 目标 App 的定位读数随注入点变化。

---

## 3. 设备当前状态（我留下的）
- 作用域已直接写入 `/data/adb/lspd/config/modules_config.db`，现在包含：
  `com.android.phone`、`com.autonavi.minimap`、`com.tencent.mm`、`system`、
  `com.cebbank.yaoyao`、`com.bankcomm.maidanba`。
- mockloc App 开关当前是**未开启**（我测试后还原过）。
- 如需改回作用域，改这个库即可（改完要重启或重启 LSPosed 才生效）。

## 4. 把日志给我
把 1.1 的落盘文件 + 1.3 的 tombstone + 1.2 的 lspd 日志放到
`D:\code\GPS\.tmp\test\`，或直接告诉我路径，我来分析。
