package dev.epay.observer.protocol

import android.app.Application
import android.os.Handler
import android.os.Looper
import dev.epay.observer.capture.CaptureState
import dev.epay.observer.parsing.Provider
import dev.epay.observer.security.PairingRuntime
import dev.epay.observer.storage.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Opportunistic trigger. WorkManager will reuse OutboxDelivery for durable
 * scheduling; this entry point alone does not promise background delivery. */
object UploadRuntime {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "observer-upload") }
    private val main = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private lateinit var database: ObserverDatabase
    private lateinit var delivery: OutboxDelivery
    @Volatile private var started = false
    @Volatile var status = "尚无网关接收回执；ACK 不代表充值"
        private set

    fun start(application: Application) {
        database = ObserverDatabase.open(application)
        delivery = OutboxDelivery(application, database)
        started = true
    }
    fun kick(retryAfterRepair: Boolean = false) {
        if (!started || PairingRuntime.busy || !running.compareAndSet(false, true)) return
        worker.execute {
            try {
                if (retryAfterRepair) Provider.entries.forEach { PairingStore(database).resumeUploads(it) }
                var attempts = 0
                while (attempts < 20) {
                    var work = false
                    for (provider in Provider.entries) {
                        if (attempts >= 20) break
                        if (delivery.sendNext(provider) != null) { attempts++; work = true }
                    }
                    if (!work) break
                }
                val counts = database.outboxDao().counts().associate { it.state to it.count }
                val pending = listOf("READY", "RETRY", "IN_FLIGHT").sumOf { counts[it] ?: 0 }
                val holds = Provider.entries.mapNotNull { provider ->
                    PairingStore(database).uploadHold(provider)?.let { reason ->
                        (if (provider == Provider.WXPAY) "微信" else "支付宝") + "发送暂停：" + when (reason) {
                            "AUTHENTICATION" -> "请修复设备凭据或时钟"
                            "SCOPE" -> "请核对网关授权范围"
                            "REDIRECT" -> "网关重定向被拒绝"
                            "CLOCK_RANGE" -> "请修复设备时间或更新过期凭据"
                            else -> "网关响应异常"
                        }
                    }
                }.joinToString("\n")
                status = "网关已收 ${counts["ACKED"] ?: 0} · 待发送 $pending · 暂停 ${counts["HELD"] ?: 0} · 拒绝 ${counts["TERMINAL"] ?: 0}\nACK 仅表示持久接收，不代表支付或充值完成" + if (holds.isEmpty()) "" else "\n$holds"
            } catch (_: Exception) {
                status = "发送未完成，事件保留；请核对网络、证书、时间和配对状态"
            } finally {
                running.set(false)
                main.post { CaptureState.changed() }
            }
        }
    }
    internal fun awaitIdle() { worker.submit { }.get(60, java.util.concurrent.TimeUnit.SECONDS) }
}
