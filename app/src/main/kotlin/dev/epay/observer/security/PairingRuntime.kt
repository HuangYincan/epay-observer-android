package dev.epay.observer.security

import android.app.Application
import android.os.Handler
import android.os.Looper
import dev.epay.observer.capture.CaptureState
import dev.epay.observer.parsing.Provider
import dev.epay.observer.protocol.PairingFailure
import dev.epay.observer.storage.*
import java.util.concurrent.Executors

object PairingRuntime {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "observer-pairing") }
    private val main = Handler(Looper.getMainLooper())
    private lateinit var coordinator: PairingCoordinator
    @Volatile var active: Map<Provider, ActiveCredential> = emptyMap()
        private set
    @Volatile var status = "未绑定"
        private set
    @Volatile var busy = false
        private set

    fun start(app: Application) {
        coordinator = PairingCoordinator(app, ObserverDatabase.open(app))
        execute({ coordinator.recover() }) { }
    }
    fun inspect(origin: String, token: String, success: (PairingConfirmation) -> Unit) =
        execute({ coordinator.inspect(origin, token) }, success)
    fun confirm(confirmation: PairingConfirmation) = execute({ coordinator.confirm(confirmation) }) { }
    fun recover() = execute({ coordinator.recover() }) { }
    fun forget() = execute({ coordinator.forgetPending() }) { }
    internal fun awaitIdle() { worker.submit { }.get(20, java.util.concurrent.TimeUnit.SECONDS) }
    private fun <T> execute(action: () -> T, success: (T) -> Unit) {
        if (busy) return
        busy = true; status = "正在核对配对状态"; CaptureState.changed()
        worker.execute {
            try {
                val result = action()
                active = coordinator.active()
                status = if (active.isEmpty()) "未绑定" else active.keys.joinToString(" / ") { if (it == Provider.WXPAY) "微信已配对" else "支付宝已配对" }
                main.post { busy = false; CaptureState.changed(); success(result) }
            } catch (failure: Exception) {
                active = try { coordinator.active() } catch (_: Exception) { emptyMap() }
                val code = (failure as? PairingFailure)?.code
                status = when (code) {
                    "invalid_grant", "scope_denied", "binding_changed", "enrollment_conflict" -> "配对授权已失效；请撤销旧绑定后重新配对"
                    "rate_limited" -> "配对请求过于频繁；稍后重试恢复"
                    "redirect_rejected" -> "网关重定向被拒绝；请核对 HTTPS 地址"
                    else -> "配对未完成；请核对网络、证书和设备时间后重试恢复"
                }
                main.post { busy = false; CaptureState.changed() }
            }
        }
    }
}
