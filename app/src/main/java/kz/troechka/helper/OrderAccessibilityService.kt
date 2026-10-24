package kz.troechka.helper

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Local-only scanner. Navigates the LIST, not the Accept slider.
 * Order details stay in RAM; preferences contain numerical statistics only.
 */
class OrderAccessibilityService : AccessibilityService() {
    data class UiState(
        val status: String = "Включи службу специальных возможностей",
        val phase: String = "IDLE",
        val orders: List<OrderRecord> = emptyList(),
        val pages: Int = 0,
        val elapsedMs: Long = 0L,
        val lastDetailId: String? = null,
        val sliderVisible: Boolean = false,
        val lastNodeCount: Int = 0,
        val lastTextCount: Int = 0,
        val lastScrollableCount: Int = 0
    )

    companion object {
        private const val TARGET = "taxi.id1702"
        @Volatile var live: OrderAccessibilityService? = null
            private set
        @Volatile var state = UiState()
            private set
        val isRunning: Boolean get() = live != null
    }

    private enum class Phase { IDLE, RESET_SCAN, SCAN, RESET_FIND, FIND, WAIT_DETAIL }
    private var phase = Phase.IDLE
    private var startedAt = 0L
    private var currentPages = 0
    private var steps = 0
    private var unchanged = 0
    private var lastFingerprint = ""
    private val known = linkedMapOf<String, OrderRecord>()
    private var selected: OrderRecord? = null
    private var waitingDetailChecks = 0
    private val handler = Handler(Looper.getMainLooper())
    private var scheduled = false
    private var lastDiagnosticAt = 0L
    private var lastNodeCount = 0
    private var lastTextCount = 0
    private var lastScrollCount = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        live = this
        publish("Служба включена. Запусти сканирование из Помощника.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.toString() != TARGET) return
        if (phase != Phase.IDLE) {
            schedule(180)
        } else if (SystemClock.elapsedRealtime() - lastDiagnosticAt > 280L) {
            lastDiagnosticAt = SystemClock.elapsedRealtime()
            val root = rootInActiveWindow ?: return
            if (root.packageName?.toString() != TARGET) return
            val view = OrderParser.inspect(root)
            updateNumericalDiagnostics(view)
            publish(if (view.detail) "Открыта карточка заказа" else "Служба наблюдает за экраном")
        }
    }

    fun startScan() {
        known.clear()
        selected = null
        phase = Phase.RESET_SCAN
        startedAt = SystemClock.elapsedRealtime()
        currentPages = 0
        resetScrollTracking()
        publish("Сканирование запущено. Открой вкладку «Свободные».")
        schedule(500)
    }

    fun findAndOpen(key: String): Boolean {
        val order = known[key] ?: return false
        if (!order.complete) {
            publish("Адреса распознаны не полностью: открыть карточку автоматически нельзя.")
            return false
        }
        selected = order
        phase = Phase.RESET_FIND
        startedAt = SystemClock.elapsedRealtime()
        resetScrollTracking()
        publish("Поиск выбранного заказа. Открой вкладку «Свободные».")
        schedule(500)
        return true
    }

    fun cancel() {
        phase = Phase.IDLE
        selected = null
        publish("Остановлено. Результаты сканирования сохранены до закрытия процесса.")
    }

    private fun resetScrollTracking() {
        steps = 0
        unchanged = 0
        lastFingerprint = ""
        waitingDetailChecks = 0
    }

    private fun schedule(delay: Long) {
        if (scheduled) return
        scheduled = true
        handler.postDelayed({
            scheduled = false
            tick()
        }, delay)
    }

    private fun tick() {
        if (phase == Phase.IDLE) return
        val root = rootInActiveWindow
        if (root == null || root.packageName?.toString() != TARGET) {
            publish("Ожидаю экран Троечки…")
            schedule(800)
            return
        }
        val view = OrderParser.inspect(root)
        updateNumericalDiagnostics(view)

        if (phase == Phase.WAIT_DETAIL) {
            waitingDetailChecks++
            if (view.detail) {
                val target = selected
                val texts = getVisibleText(root).lowercase().replace(Regex("""\s+"""), " ")
                val pickup = target?.pickup?.lowercase()?.replace(Regex("""\s+"""), " ").orEmpty()
                val destination = target?.destination?.lowercase()?.replace(Regex("""\s+"""), " ").orEmpty()
                val priceMatches = target != null &&
                    Regex("""\b""" + target.price.toString() + """[,.]00\b""").containsMatchIn(texts)
                val addressesMatch = pickup.isNotEmpty() && destination.isNotEmpty() &&
                    texts.contains(pickup) && texts.contains(destination)
                phase = Phase.IDLE
                if (priceMatches && addressesMatch) {
                    publish("Карточка найдена и сверена. Ползунок обнаружен: " +
                        if (view.acceptTextVisible) "да. Свайп НЕ выполнялся." else "нет. Свайп НЕ выполнялся.",
                        view.orderId, view.acceptTextVisible)
                } else {
                    publish("Карточка открылась, но совпадение НЕ подтверждено. Ничего не принято.",
                        view.orderId, view.acceptTextVisible)
                }
                return
            }
            if (waitingDetailChecks >= 7) {
                phase = Phase.IDLE
                publish("Карточка не открылась. Ничего не принято.")
                return
            }
            schedule(400)
            return
        }

        if (view.detail || view.acceptTextVisible) {
            publish("Сейчас открыта карточка. Вернись к списку «Свободные».")
            schedule(900)
            return
        }
        if (view.cards.isEmpty()) {
            publish("Не вижу цен заказов. Открой вкладку «Свободные».")
            schedule(850)
            return
        }

        val fingerprint = view.cards.joinToString("#") { it.record.key }
        if (fingerprint == lastFingerprint) unchanged++
        else {
            unchanged = 0
            lastFingerprint = fingerprint
        }

        when (phase) {
            Phase.RESET_SCAN, Phase.RESET_FIND -> {
                if (unchanged >= 2 || steps >= 30) {
                    phase = if (phase == Phase.RESET_SCAN) Phase.SCAN else Phase.FIND
                    resetScrollTracking()
                    publish("Дошёл до начала списка. " +
                        if (phase == Phase.SCAN) "Читаю заказы…" else "Ищу выбранный заказ…")
                    schedule(300)
                } else if (scroll(view, backwards = true)) {
                    steps++
                    publish("Прокручиваю к началу списка…")
                    schedule(500)
                } else {
                    phase = if (phase == Phase.RESET_SCAN) Phase.SCAN else Phase.FIND
                    resetScrollTracking()
                    schedule(300)
                }
            }
            Phase.SCAN -> {
                currentPages++
                for (item in view.cards) {
                    if (item.record.complete) {
                        known[item.record.key] = item.record
                    }
                }
                if (unchanged >= 2 || steps >= 40) {
                    phase = Phase.IDLE
                    publish("Готово. Найдено полных карточек: " + known.size +
                        ". Вернись в Помощник и выбери заказ.")
                } else if (scroll(view, backwards = false)) {
                    steps++
                    publish("Сканирую список: найдено " + known.size + " карточек…")
                    schedule(500)
                } else {
                    phase = Phase.IDLE
                    publish("Сканирование завершено. Полных карточек: " + known.size)
                }
            }
            Phase.FIND -> {
                val target = selected
                if (target == null) {
                    cancel()
                    return
                }
                val matches = view.cards.filter {
                    it.record.complete && it.record.key == target.key
                }
                if (matches.size == 1) {
                    if (openVisibleCard(matches.single(), root)) {
                        phase = Phase.WAIT_DETAIL
                        publish("Открываю совпадающую карточку. Принятие отключено.")
                        schedule(750)
                    } else {
                        phase = Phase.IDLE
                        publish("Не получилось нажать на карточку. Ничего не принято.")
                    }
                } else if (matches.size > 1) {
                    phase = Phase.IDLE
                    publish("Найдено несколько одинаковых карточек. Ничего не открываю.")
                } else if (unchanged >= 2 || steps >= 40) {
                    phase = Phase.IDLE
                    publish("Заказ больше не найден (возможно, исчез из списка).")
                } else if (scroll(view, backwards = false)) {
                    steps++
                    publish("Ищу заказ среди остальных строк…")
                    schedule(500)
                } else {
                    phase = Phase.IDLE
                    publish("Заказ не найден. Вероятно, список обновился.")
                }
            }
            else -> Unit
        }
    }

    private fun scroll(view: ScanView, backwards: Boolean): Boolean {
        val node = view.scrollNode ?: return false
        val action = if (backwards) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                     else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        if (node.performAction(action)) return true

        // Fallback only for scrollable LIST, never inside a detail screen.
        if (view.detail || view.acceptTextVisible || view.cards.isEmpty()) return false
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.height() < 320 || rect.width() < 200) return false
        val x = rect.centerX().toFloat()
        val high = (rect.top + rect.height() * 0.30f)
        val low = (rect.top + rect.height() * 0.75f)
        val path = Path().apply {
            moveTo(x, if (backwards) high else low)
            lineTo(x, if (backwards) low else high)
        }
        return dispatchGesture(
            GestureDescription.Builder().addStroke(
                GestureDescription.StrokeDescription(path, 0, 330)
            ).build(), null, null
        )
    }

    private fun openVisibleCard(card: VisibleOrder, root: AccessibilityNodeInfo): Boolean {
        val window = Rect()
        root.getBoundsInScreen(window)
        var node: AccessibilityNodeInfo? = card.priceNode
        repeat(8) {
            val current = node ?: return@repeat
            val bounds = Rect()
            current.getBoundsInScreen(bounds)
            if (current.isClickable && bounds.width() >= card.priceBounds.width() &&
                bounds.height() < window.height() * 0.75 &&
                bounds.height() > card.priceBounds.height()) {
                if (current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            }
            node = current.parent
        }
        val point = card.priceBounds
        if (!window.contains(point.centerX(), point.centerY())) return false
        val path = Path().apply {
            moveTo(point.centerX().toFloat(), point.centerY().toFloat())
        }
        return dispatchGesture(
            GestureDescription.Builder().addStroke(
                GestureDescription.StrokeDescription(path, 0, 90)
            ).build(), null, null
        )
    }

    private fun getVisibleText(root: AccessibilityNodeInfo): String {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        val result = StringBuilder()
        var n = 0
        while (queue.isNotEmpty() && n++ < 1400) {
            val current = queue.removeFirst()
            val label = current.text?.toString()
                ?: current.contentDescription?.toString()
            if (!label.isNullOrBlank()) result.append(label).append(' ')
            for (i in 0 until current.childCount) {
                current.getChild(i)?.let { queue.add(it) }
            }
        }
        return result.toString()
    }

    private fun updateNumericalDiagnostics(view: ScanView) {
        lastNodeCount = view.nodesCount
        lastTextCount = view.textsCount
        lastScrollCount = view.scrollableCount
        getSharedPreferences("diagnostics", MODE_PRIVATE).edit()
            .putLong("lastObservedAt", System.currentTimeMillis())
            .putInt("nodes", view.nodesCount)
            .putInt("textNodes", view.textsCount)
            .putInt("scrollableNodes", view.scrollableCount)
            .putInt("clickableNodes", view.clickableCount)
            .putInt("visiblePriceTexts", view.cards.size)
            .putBoolean("everDetailDetected", view.detail)
            .putBoolean("everAcceptControlDetected", view.acceptTextVisible)
            .apply()
    }

    private fun publish(message: String, detailId: String? = null, slider: Boolean = false) {
        state = UiState(
            status = message, phase = phase.name, orders = known.values.toList(),
            pages = currentPages,
            elapsedMs = if (startedAt == 0L) 0 else SystemClock.elapsedRealtime() - startedAt,
            lastDetailId = detailId, sliderVisible = slider,
            lastNodeCount = lastNodeCount, lastTextCount = lastTextCount,
            lastScrollableCount = lastScrollCount
        )
    }

    override fun onInterrupt() {
        phase = Phase.IDLE
        publish("Служба прервана системой.")
    }

    override fun onDestroy() {
        phase = Phase.IDLE
        handler.removeCallbacksAndMessages(null)
        live = null
        publish("Служба отключена.")
        super.onDestroy()
    }
}
