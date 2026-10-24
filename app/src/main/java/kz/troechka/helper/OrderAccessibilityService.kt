package kz.troechka.helper

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque

/** Passive on-device diagnostics. No clicks, order acceptance, or network access. */
class OrderAccessibilityService : AccessibilityService() {
    companion object {
        private const val TARGET_PACKAGE = "taxi.id1702"
        @Volatile var isRunning: Boolean = false
            private set
    }
    private val pricePattern = Regex(
        """\b\d[\d ]{0,9}[,.]\d{2}\s*(?:тенге|₸)""",
        RegexOption.IGNORE_CASE
    )
    private var lastSampleAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.toString() != TARGET_PACKAGE) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastSampleAt < 250L) return
        lastSampleAt = now
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != TARGET_PACKAGE) return

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var nodeCount = 0
        var textNodes = 0
        var scrollables = 0
        var clickables = 0
        var prices = 0
        var details = false
        var accept = false
        while (queue.isNotEmpty() && nodeCount < 1000) {
            val node = queue.removeFirst()
            nodeCount++
            if (node.isScrollable) scrollables++
            if (node.isClickable) clickables++
            val label = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            if (label.isNotBlank()) textNodes++
            if (pricePattern.containsMatchIn(label)) prices++
            if (pricePattern.containsMatchIn(desc)) prices++
            if (label.contains("Вернуться к заказам", true) ||
                desc.contains("Вернуться к заказам", true)) details = true
            if (label.contains("Принять заказ", true) ||
                desc.contains("Принять заказ", true)) accept = true
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.addLast(it) }
            }
        }
        val prefs = getSharedPreferences("diagnostics", MODE_PRIVATE)
        prefs.edit()
            .putLong("observations", prefs.getLong("observations", 0) + 1)
            .putLong("lastObservedAt", System.currentTimeMillis())
            .putInt("nodes", nodeCount)
            .putInt("textNodes", textNodes)
            .putInt("scrollableNodes", scrollables)
            .putInt("clickableNodes", clickables)
            .putInt("visiblePriceTexts", prices)
            .putInt("maxVisiblePriceTexts", maxOf(prices, prefs.getInt("maxVisiblePriceTexts", 0)))
            .putBoolean("everDetailDetected", details || prefs.getBoolean("everDetailDetected", false))
            .putBoolean("everAcceptControlDetected", accept || prefs.getBoolean("everAcceptControlDetected", false))
            .apply()
    }
    override fun onInterrupt() = Unit
    override fun onDestroy() {
        isRunning = false
        super.onDestroy()
    }
}
