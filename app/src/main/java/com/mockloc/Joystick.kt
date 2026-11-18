package com.mockloc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.cos
import kotlin.math.hypot

/**
 * 悬浮摇杆（阶段五）。
 *
 * 一个可拖的圆盘：拖动钮把**当前注入点**按偏移量平移，松手后钮回中、点位保留。
 * 半径 = 500m，所以拖满一格是 500m 的位移，细调靠拖一小段。
 *
 * 窗口是 TYPE_APPLICATION_OVERLAY，需要「显示在其他应用上层」授权；服务用前台服务保活
 * （Android 14+ 的 FGS 类型要求用 specialUse + 子类型声明）。
 */
class JoystickView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    private val radiusPx = 80f * density
    private val knobPx = 36f * density

    private var knobX = Float.NaN
    private var knobY = Float.NaN

    /** 拖动起点的注入点（WGS），由宿主提供。 */
    var pointProvider: (() -> DoubleArray?)? = null

    /** 每次拖动都把"起点 + 偏移"回给宿主（已含坐标系换算，宿主直接注入）。 */
    var listener: ((Double, Double) -> Unit)? = null

    private var anchor: DoubleArray? = null

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33202124; style = Paint.Style.FILL
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF6750A4.toInt(); style = Paint.Style.STROKE; strokeWidth = 3f * density
    }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF6750A4.toInt(); style = Paint.Style.FILL
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        knobX = w / 2f
        knobY = h / 2f
    }

    override fun onDraw(canvas: Canvas) {
        if (knobX.isNaN()) return
        val cx = width / 2f
        val cy = height / 2f
        canvas.drawCircle(cx, cy, radiusPx, basePaint)
        canvas.drawCircle(cx, cy, radiusPx, ringPaint)
        canvas.drawCircle(knobX, knobY, knobPx, knobPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val cx = width / 2f
        val cy = height / 2f
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                if (anchor == null) anchor = pointProvider?.invoke() ?: return true
                var dx = event.x - cx
                var dy = event.y - cy
                val d = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                if (d > radiusPx) {
                    dx = dx / d * radiusPx
                    dy = dy / d * radiusPx
                }
                knobX = cx + dx
                knobY = cy + dy
                invalidate()
                val a = anchor ?: return true
                val scale = 500.0 / radiusPx
                val dLat = -dy * scale / 111132.0
                val dLng = dx * scale / (111412.8 * cos(Math.toRadians(a[0])))
                listener?.invoke(a[0] + dLat, a[1] + dLng)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                knobX = cx
                knobY = cy
                invalidate()
                anchor = null
            }
        }
        return true
    }
}

/** 摇杆的前台服务：加窗 + 保活。 */
class JoystickService : Service() {

    private var view: JoystickView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())
        if (view == null) {
            view = JoystickView(this).also { v ->
                v.pointProvider = { JoystickBridge.point() }
                v.listener = { lat, lng -> JoystickBridge.apply(lat, lng) }
                val wm = getSystemService(WINDOW_SERVICE) as WindowManager
                val params = WindowManager.LayoutParams(
                    dp(220), dp(220),
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.BOTTOM or Gravity.END
                    x = dp(24)
                    y = dp(120)
                }
                runCatching { wm.addView(v, params) }.onFailure { stopSelf() }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        view?.let { v -> runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v) } }
        view = null
        getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putBoolean("joystick_running", false).apply()
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "悬浮摇杆", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "保持摇杆悬浮窗存活"
                },
            )
        }
        val pi = android.app.PendingIntent.getService(
            this, 0,
            Intent(this, JoystickService::class.java).setAction(ACTION_STOP),
            android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("摇杆运行中")
            .setContentText("点通知或状态页按钮关闭")
            .setSmallIcon(R.drawable.ic_tab_route)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "关闭", pi).build())
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.mockloc.joystick.STOP"
        private const val CHANNEL = "joystick"
        private const val NOTIF_ID = 0x5A
    }
}

/** 摇杆与 App 主进程之间的桥：服务与 Activity 同进程，静态委托够用。 */
object JoystickBridge {
    /** 当前注入点（WGS）；没有则回退到上海，避免拖出一个荒谬的坐标。 */
    var point: () -> DoubleArray? = { null }

    /** 应用新点位。 */
    var apply: (Double, Double) -> Unit = { _, _ -> }
}
