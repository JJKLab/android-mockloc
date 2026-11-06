package com.mockloc

import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.BluetoothLeScanner
import io.github.libxposed.api.XposedInterface

/**
 * BLE 盲化（对标 LocationSpoofer 的 BluetoothLeScanner 覆盖，进程内版）：射频盲化的第四路。
 * 蓝牙 Beacon 定位（iBeacon/Eddystone + 微信蓝牙连播、商场导航类 SDK）用真实 BLE 指纹自算位置，
 * 与高德吃 WiFi 同理——注入点之外的真实射频输入都会变成回跳源。WiFi/基站已在 [Sensors] 覆盖，
 * 这里补齐 BLE：作用域内的 App 调 startScan 一律"没扫到"。
 *
 * 不动 BluetoothAdapter.enable/disable 这类总闸——那会真实改变用户蓝牙状态，超出"只影响本 App
 * 的定位输入"的边界。old API `startLeScan`（deprecated，部分老 SDK 仍在用）一并覆盖，返回
 * false 表示"扫描没启动"，与系统语义一致。
 */
class BleBlind(
    private val module: XposedInterface,
    private val classLoader: ClassLoader,
    private val pkg: String,
) {
    private val tag = "[BleBlind]"
    private val kit = HookKit(module, tag)

    fun init() {
        Config.refresh()
        if (!Config.blindBleFor(pkg)) return

        var hits = 0
        kit.findClass(classLoader, BluetoothLeScanner::class.java.name)?.let { scanner ->
            // startScan 全部重载（callback 形态 + PendingIntent 形态）：void 返回，直接吞掉，
            // 扫描请求不发给系统。App 侧表现为"周围没有 Beacon"。
            hits += kit.install(scanner.declaredMethods.filter { it.name.startsWith("startScan") }) {
                null
            }
            // stopScan 原样放行：吞掉 start 后 stop 调用无害，且保持 App 内部状态机干净。
        }
        kit.findClass(classLoader, BluetoothAdapter::class.java.name)?.let { adapter ->
            hits += kit.install(adapter.declaredMethods.filter { it.name == "startLeScan" }) { false }
        }
        if (hits > 0) kit.log("$tag: BLE 盲化已挂钩 $hits 处（startScan*/startLeScan → 静默空结果）")
        else Diagnostics.note("$tag: 本进程没有 BLE 扫描 API，跳过（正常，比如系统框架）")
    }
}
