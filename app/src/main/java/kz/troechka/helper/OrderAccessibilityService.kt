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
import java.util.ArrayDeque

/**
 * Local-only scanner and user-confirmed order acceptance through the official UI.
 * No network interception or server-side command injection. Sensitive order details stay in RAM.
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
        val lastScrollableCount: Int = 0,
        val incompleteObservations: Int = 0,
        val acceptanceResult: String = "Нет попыток"
    )

    companion object {
        private const val TARGET = "taxi.id1702"
        @Volatile var live: OrderAccessibilityService? = null
            private set
        @Volatile var state = UiState()
            private set
        val isRunning: Boolean get() = live != null
    }

    private enum class Phase { IDLE, RESET_SCAN, SCAN, RESET_FIND, FIND, WAIT_DETAIL, VERIFY_ACCEPT }
    private var phase = Phase.IDLE
    private var startedAt = 0L
    private var currentPages = 0
    private var steps = 0
    private var unchanged = 0
    private var lastFingerprint = ""
    private val known = linkedMapOf<String, OrderRecord>()
    private var selected: OrderRecord? = null
    private var acceptRequested = false
    private var gestureInFlight = false
    private var gestureCompleted = false
    private var gestureCompletedAt = 0L
    private var verificationPolls = 0
    private var acceptanceResult = "Нет попыток"
    private var waitingDetailChecks = 0
    private val handler = Handler(Looper.getMainLooper())
    private var pendingTick: Runnable? = null
    private var nextTickAt = Long.MAX_VALUE
    private val scanOrder = linkedSetOf<String>()
    private var waitingForScroll = false
    private var scrollSnapshot = ""
    private var scrollPolls = 0
    private var incompleteObservations = 0
    private var alternateDirection = false
    private var preciseScan = false
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

    fun startScan(accurate: Boolean = false) {
        if (!accurate) known.clear()
        scanOrder.clear()
        preciseScan = accurate
        selected = null
        acceptRequested = false
        phase = Phase.RESET_SCAN
        startedAt = SystemClock.elapsedRealtime()
        currentPages = 0
        incompleteObservations = 0
        resetScrollTracking()
        publish(if (accurate) "Точная допроверка: небольшие прокрутки с перекрытием." else "Быстрое сканирование списка.")
        schedule(160)
    }

    fun findAndOpen(key: String, accept: Boolean = false): Boolean {
        if (phase != Phase.IDLE) return false
        val order = known[key] ?: return false
        if (!order.complete) {
            publish("Адреса распознаны не полностью: открыть карточку автоматически нельзя.")
            return false
        }
        selected = order
        acceptRequested = accept
        acceptanceResult = if (accept) "Ожидаю поиска и проверки карточки" else "Принятие не запрашивалось"
        gestureCompleted = false
        gestureInFlight = false
        verificationPolls = 0
        // Searching from the currently displayed page is much faster than resetting.
        phase = Phase.FIND
        startedAt = SystemClock.elapsedRealtime()
        alternateDirection = false
        resetScrollTracking()
        publish("Поиск выбранного заказа. Открой вкладку «Свободные».")
        schedule(180)
        return true
    }

    fun cancel() {
        phase = Phase.IDLE
        pendingTick?.let { handler.removeCallbacks(it) }
        pendingTick = null
        nextTickAt = Long.MAX_VALUE
        waitingForScroll = false
        selected = null
        acceptRequested = false
        gestureInFlight = false
        publish("Остановлено. Результаты сканирования сохранены до закрытия процесса.")
    }

    private fun resetScrollTracking() {
        steps = 0
        unchanged = 0
        lastFingerprint = ""
        waitingDetailChecks = 0
        waitingForScroll = false
    }

    private fun schedule(delay: Long) {
        val due = SystemClock.uptimeMillis() + delay
        if (pendingTick != null && due >= nextTickAt) return
        pendingTick?.let { handler.removeCallbacks(it) }
        val callback = Runnable {
            pendingTick = null
            nextTickAt = Long.MAX_VALUE
            tick()
        }
        pendingTick = callback
        nextTickAt = due
        handler.postDelayed(callback, delay)
    }

    private fun finishScan(message: String) {
        // Preserve the actual top-to-bottom order observed during this scan.
        // Accurate rescans can insert missing rows between existing orders.
        val sorted = linkedMapOf<String, OrderRecord>()
        for (key in scanOrder) known[key]?.let { sorted[key] = it }
        for ((key, value) in known) if (key !in sorted) sorted[key] = value
        known.clear()
        known.putAll(sorted)
        phase = Phase.IDLE
        publish(message + " Полных карточек: " + known.size +
            ". Неполных чтений: " + incompleteObservations + ".")
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

        // The view tree immediately after ACTION_SCROLL may still be the old one.
        if (waitingForScroll) {
            if (screenFingerprint(view) == scrollSnapshot && scrollPolls++ < 4) {
                schedule(100)
                return
            }
            waitingForScroll = false
        }

        if (phase == Phase.VERIFY_ACCEPT) {
            if (gestureInFlight) {
                schedule(350)
                return
            }
            if (!gestureCompleted) {
                phase = Phase.IDLE
                acceptanceResult = "Жест отменён или не завершился"
                publish("Принятие не подтверждено: жест не завершён.")
                return
            }
            // A completed Android gesture is NOT proof that the taxi server assigned the trip.
            // Only a definite in-app confirmation should be labeled as success.
            verificationPolls++
            val screen = getVisibleText(root).lowercase()
            val strongSuccess = listOf("заказ принят", "заказ назначен", "вы назначены",
                "вы приняли заказ").any { screen.contains(it) }
            if (strongSuccess) {
                phase = Phase.IDLE
                acceptanceResult = "Троечка показала подтверждение принятия"
                publish("Заказ принят по сообщению приложения. Проверь «Мои заказы».")
                return
            }
            if (verificationPolls >= 12 ||
                SystemClock.elapsedRealtime() - gestureCompletedAt > 6500L) {
                phase = Phase.IDLE
                acceptanceResult = "Жест выполнен, подтверждение сервера НЕ обнаружено"
                publish("Свайп выполнен. Результат не подтверждён — проверь «Мои заказы» вручную.")
                return
            }
            schedule(450)
            return
        }

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
                if (priceMatches && addressesMatch) {
                    if (acceptRequested) {
                        if (!view.acceptTextVisible) {
                            phase = Phase.IDLE
                            acceptanceResult = "Не обнаружен текст ползунка принятия"
                            publish("Не нашёл ползунок «Принять». Свайп отменён.",
                                view.orderId, false)
                        } else {
                            // Acceptance occurs ONCE only after an explicit user confirmation
                            // on a selected order and a fresh price+address match.
                            acceptRequested = false
                            attemptAcceptance(root, view.orderId)
                        }
                    } else {
                        phase = Phase.IDLE
                        publish("Карточка найдена и сверена. Ползунок обнаружен: " +
                            if (view.acceptTextVisible) "да. Свайп НЕ выполнялся." else "нет. Свайп НЕ выполнялся.",
                            view.orderId, view.acceptTextVisible)
                    }
                } else {
                    phase = Phase.IDLE
                    acceptRequested = false
                    acceptanceResult = "Адреса или цена не совпали; свайп запрещён"
                    publish("Карточка открылась, но совпадение НЕ подтверждено. Ничего не принято.",
                        view.orderId, view.acceptTextVisible)
                }
                return
            }
            if (waitingDetailChecks >= 7) {
                phase = Phase.IDLE
                acceptRequested = false
                acceptanceResult = "Карточка не открылась"
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

        val frame = screenFingerprint(view)
        if (frame == lastFingerprint) unchanged++
        else {
            unchanged = 0
            lastFingerprint = frame
        }

        when (phase) {
            Phase.RESET_SCAN, Phase.RESET_FIND -> {
                if (unchanged >= 2 || steps >= 30) {
                    phase = if (phase == Phase.RESET_SCAN) Phase.SCAN else Phase.FIND
                    resetScrollTracking()
                    publish("Дошёл до начала списка. " +
                        if (phase == Phase.SCAN) "Читаю заказы…" else "Ищу выбранный заказ…")
                    schedule(120)
                } else if (scrollAndWait(view, backwards = true)) {
                    steps++
                    publish("Прокручиваю к началу списка…")
                    schedule(160)
                } else {
                    phase = if (phase == Phase.RESET_SCAN) Phase.SCAN else Phase.FIND
                    resetScrollTracking()
                    schedule(120)
                }
            }
            Phase.SCAN -> {
                currentPages++
                incompleteObservations += view.cards.count { !it.record.complete }
                for (item in view.cards) {
                    if (item.record.complete) {
                        known[item.record.key] = item.record
                        scanOrder.add(item.record.key)
                    }
                }
                if (unchanged >= 2 || steps >= 40) {
                    finishScan("Быстрое сканирование завершено.")
                } else if (scrollAndWait(view, backwards = false)) {
                    steps++
                    publish("Сканирую список: найдено " + known.size + " карточек…")
                    schedule(160)
                } else {
                    finishScan("Список дочитан.")
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
                        publish(if (acceptRequested)
                            "Открываю карточку и перепроверяю данные перед свайпом."
                            else "Открываю выбранную карточку. Принятие отключено.")
                        schedule(300)
                    } else {
                        phase = Phase.IDLE
                        publish("Карточка не открылась. Ничего не принято.")
                    }
                } else if (matches.size > 1) {
                    phase = Phase.IDLE
                    publish("Несколько одинаковых карточек. Ничего не открываю.")
                } else {
                    val orderIndexes = known.keys.toList()
                    val wanted = orderIndexes.indexOf(target.key)
                    val nearby = view.cards.map { orderIndexes.indexOf(it.record.key) }.filter { it >= 0 }
                    val reverse = when {
                        nearby.isEmpty() -> true
                        wanted < nearby.minOrNull()!! -> true
                        wanted > nearby.maxOrNull()!! -> false
                        else -> false
                    }
                    val backwards = if (alternateDirection) !reverse else reverse
                    if (unchanged >= 2 || steps >= 40) {
                        if (!alternateDirection && steps < 40) {
                            alternateDirection = true
                            unchanged = 0
                            lastFingerprint = ""
                            if (scrollAndWait(view, !reverse)) {
                                steps++
                                schedule(160)
                            } else {
                                phase = Phase.IDLE
                                publish("Заказ исчез или список изменился.")
                            }
                        } else {
                            phase = Phase.IDLE
                            publish("Заказ не найден в актуальном списке.")
                        }
                    } else if (scrollAndWait(view, backwards)) {
                        steps++
                        publish("Ищу подходящую карточку " + if (backwards) "выше…" else "ниже…")
                        schedule(160)
                    } else {
                        phase = Phase.IDLE
                        publish("Прокрутка закончилась. Заказ не найден.")
                    }
                }
            }
            else -> Unit
        }
    }

    private fun scrollAndWait(view: ScanView, backwards: Boolean): Boolean {
        val original = screenFingerprint(view)
        if (!scroll(view, backwards)) return false
        scrollSnapshot = original
        waitingForScroll = true
        scrollPolls = 0
        return true
    }

    private fun screenFingerprint(view: ScanView): String =
        view.cards.joinToString("#") { it.record.key + "@" + it.priceBounds.top }

    private fun scroll(view: ScanView, backwards: Boolean): Boolean {
        val node = view.scrollNode ?: return false
        val action = if (backwards) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                     else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        val precise = preciseScan && phase == Phase.SCAN && !backwards
        if (!precise && node.performAction(action)) return true

        // Precise scan uses overlapping short swipes so a partially visible
        // address can be read fully on a later frame. Never swipe a detail.

        if (view.detail || view.acceptTextVisible || view.cards.isEmpty()) return false
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.height() < 320 || rect.width() < 200) return false
        val x = rect.centerX().toFloat()
        val high = (rect.top + rect.height() * if (precise) 0.41f else 0.30f)
        val low = (rect.top + rect.height() * if (precise) 0.68f else 0.75f)
        val path = Path().apply {
            moveTo(x, if (backwards) high else low)
            lineTo(x, if (backwards) low else high)
        }
        return dispatchGesture(
            GestureDescription.Builder().addStroke(
                GestureDescription.StrokeDescription(path, 0, if (precise) 195L else 260L)
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

    /**
     * Finds the drag track from the current accessibility tree.
     * No fixed screen coordinates and no fallback blind swipe: if the thumb
     * track is not unambiguously exposed, abort instead of touching other UI.
     */
    private fun findSliderTrack(root: AccessibilityNodeInfo): Rect? {
        val screen = Rect().also { root.getBoundsInScreen(it) }
        val minHeight = (44 * resources.displayMetrics.density).toInt()
        val maxHeight = (140 * resources.displayMetrics.density).toInt()
        val found = mutableListOf<Rect>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var checked = 0
        while (queue.isNotEmpty() && checked++ < 1400) {
            val node = queue.removeFirst()
            val description = (node.text?.toString().orEmpty() + " " +
                node.contentDescription?.toString().orEmpty())
            if (description.contains("Принять заказ", ignoreCase = true)) {
                var current: AccessibilityNodeInfo? = node
                repeat(7) {
                    val ancestor = current ?: return@repeat
                    val r = Rect().also { ancestor.getBoundsInScreen(it) }
                    if (r.width() >= screen.width() * 0.70 &&
                        r.height() in minHeight..maxHeight &&
                        r.centerY() > screen.top + screen.height() * 0.70 &&
                        r.left >= screen.left &&
                        r.right <= screen.right &&
                        r.top >= screen.top &&
                        r.bottom <= screen.bottom) {
                        found.add(r)
                    }
                    current = ancestor.parent
                }
            }
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { queue.addLast(it) }
            }
        }
        // Reject ambiguous multiple tracks. Single left-to-right slider only.
        return found.distinctBy { it.flattenToString() }
            .minByOrNull { it.width().toLong() * it.height() }
    }

    private fun attemptAcceptance(root: AccessibilityNodeInfo, orderId: String?) {
        val track = findSliderTrack(root)
        if (track == null) {
            phase = Phase.IDLE
            acceptanceResult = "Границы ползунка неизвестны"
            publish("Ползунок виден, но его границы не определены. Свайп отменён.",
                orderId, false)
            return
        }
        if (gestureInFlight || gestureCompleted) {
            phase = Phase.IDLE
            acceptanceResult = "Повторное принятие заблокировано"
            publish("Повторный свайп заблокирован.")
            return
        }
        val y = track.centerY().toFloat()
        // White circular handle begins at the left end (seen in actual app UI).
        val left = track.left + track.height() / 2f
        val right = track.right - track.height() / 2f
        if (right - left < track.height() * 2f) {
            phase = Phase.IDLE
            acceptanceResult = "Недостаточная длина ползунка"
            publish("Свайп отменён: некорректная длина ползунка.")
            return
        }
        phase = Phase.VERIFY_ACCEPT
        gestureInFlight = true
        verificationPolls = 0
        val path = Path().apply {
            moveTo(left, y)
            lineTo(left + (right - left) * 0.35f, y)
            lineTo(left + (right - left) * 0.70f, y)
            lineTo(right, y)
        }
        val action = GestureDescription.Builder().addStroke(
            GestureDescription.StrokeDescription(path, 0, 480L)
        ).build()
        val dispatched = dispatchGesture(action,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    gestureInFlight = false
                    gestureCompleted = true
                    gestureCompletedAt = SystemClock.elapsedRealtime()
                    acceptanceResult = "Android выполнил свайп; проверяю ответ"
                    publish("Свайп выполнен. Жду подтверждения «Троечки».", orderId, true)
                    schedule(350)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    gestureInFlight = false
                    gestureCompleted = false
                    phase = Phase.IDLE
                    acceptanceResult = "Android отменил свайп"
                    publish("Android отменил жест. Заказ НЕ подтверждён.")
                }
            }, handler)
        if (!dispatched) {
            gestureInFlight = false
            phase = Phase.IDLE
            acceptanceResult = "Система отказала в отправке жеста"
            publish("Не удалось отправить свайп. Заказ НЕ подтверждён.")
        } else {
            publish("Выполняю однократный свайп по подтверждённой карточке.", orderId, true)
        }
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
            lastScrollableCount = lastScrollCount,
            incompleteObservations = incompleteObservations,
            acceptanceResult = acceptanceResult
        )
    }

    override fun onInterrupt() {
        acceptRequested = false
        gestureInFlight = false
        phase = Phase.IDLE
        publish("Служба прервана системой.")
    }

    override fun onDestroy() {
        acceptRequested = false
        gestureInFlight = false
        phase = Phase.IDLE
        pendingTick?.let { handler.removeCallbacks(it) }
        pendingTick = null
        live = null
        publish("Служба отключена.")
        super.onDestroy()
    }
}
