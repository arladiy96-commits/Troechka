package kz.troechka.helper

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque

data class OrderRecord(
    val key: String,
    val price: Int,
    val pickup: String,
    val destination: String,
    val pickupKm: Double?,
    val routeKm: Double?
) {
    val complete: Boolean get() = pickup.isNotBlank() && destination.isNotBlank()
}

data class ScanView(
    val cards: List<VisibleOrder>,
    val scrollNode: AccessibilityNodeInfo?,
    val detail: Boolean,
    val acceptTextVisible: Boolean,
    val orderId: String?,
    val textsCount: Int,
    val clickableCount: Int,
    val scrollableCount: Int,
    val nodesCount: Int
)

data class VisibleOrder(
    val record: OrderRecord,
    val priceNode: AccessibilityNodeInfo,
    val priceBounds: Rect
)

object OrderParser {
    private data class Piece(val text: String, val bounds: Rect, val node: AccessibilityNodeInfo)
    private val fareRegex = Regex("""(\d[\d \u00a0]{0,9})[,.](\d\d)\s*(?:тенге|₸)""", RegexOption.IGNORE_CASE)
    private val distanceRegex = Regex("""(\d+(?:[,.]\d+)?)\s*км""", RegexOption.IGNORE_CASE)
    private val addressRegex = Regex(
        """(?:\bул\b|\bулица\b|\bул\.|\bпр\.|\bпроспект\b|\bмкр\b|\bмикрорайон\b|\bпер\.|\bпереулок\b|\bшоссе\b|\bрайон\b|\bпосёлок\b|\bсело\b|\bплощадь\b|\bбульвар\b|\bпроезд\b|\bнабережная\b|\bтракт\b)""",
        RegexOption.IGNORE_CASE
    )
    private val orderIdRegex = Regex("""\b\d{5,10}\b""")

    fun inspect(root: AccessibilityNodeInfo): ScanView {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        val pieces = mutableListOf<Piece>()
        val scrollables = mutableListOf<AccessibilityNodeInfo>()
        val rootBounds = Rect().also { root.getBoundsInScreen(it) }
        val seen = HashSet<String>()
        var count = 0
        var clickables = 0
        while (queue.isNotEmpty() && count < 1400) {
            val node = queue.removeFirst()
            count++
            if (node.isClickable) clickables++
            if (node.isScrollable) scrollables.add(node)
            val rect = Rect()
            node.getBoundsInScreen(rect)
            // Ignore off-screen/virtual rows. A partially visible card will be retried
            // on the next overlapping scroll step.
            if (rect.width() > 0 && rect.height() > 0 &&
                Rect.intersects(rect, rootBounds)) {
                val value = (node.text?.toString()?.takeIf { it.isNotBlank() }
                    ?: node.contentDescription?.toString().orEmpty()).trim()
                if (value.isNotBlank()) {
                    val fingerprint = value + "|" + rect.left + "|" + rect.top +
                        "|" + rect.right + "|" + rect.bottom
                    if (seen.add(fingerprint)) pieces.add(Piece(value, rect, node))
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.addLast(it) }
            }
        }
        val texts = pieces.map { it.text }
        val detail = texts.any { it.contains("Вернуться к заказам", true) }
        val accept = texts.any { it.contains("Принять заказ", true) }
        val id = if (detail) texts.firstNotNullOfOrNull {
            orderIdRegex.find(it)?.value
        } else null
        val scroll = scrollables.maxByOrNull { node ->
            val b = Rect()
            node.getBoundsInScreen(b)
            b.width() * b.height()
        }
        if (detail || accept) {
            return ScanView(emptyList(), scroll, detail, accept, id,
                pieces.size, clickables, scrollables.size, count)
        }

        val prices = pieces.mapNotNull { piece ->
            val match = fareRegex.find(piece.text) ?: return@mapNotNull null
            val whole = match.groupValues[1].replace(Regex("""[^\d]"""), "")
            val fare = whole.toIntOrNull() ?: return@mapNotNull null
            if (fare < 100 || fare > 100000) return@mapNotNull null
            Pair(piece, fare)
        }.distinctBy { (piece, _) -> piece.bounds.centerY().toString() + ":" +
            piece.bounds.centerX().toString() }
            .sortedBy { it.first.bounds.centerY() }

        val visible = mutableListOf<VisibleOrder>()
        for ((index, pair) in prices.withIndex()) {
            val (pricePiece, fare) = pair
            val y = pricePiece.bounds.centerY()
            val previousY = prices.getOrNull(index - 1)?.first?.bounds?.centerY()
            val nextY = prices.getOrNull(index + 1)?.first?.bounds?.centerY()
            val from = previousY?.let { (it + y) / 2 } ?: (y - 230)
            val until = nextY?.let { (it + y) / 2 } ?: (y + 450)
            // Prefer the actual card container if accessibility exposes one. Pixel
            // midpoints fail on cards with long wrapped addresses.
            val parentBounds = findCardBounds(pricePiece.node, pricePiece.bounds, rootBounds, prices, pieces)
            val section = if (parentBounds != null) {
                pieces.filter { parentBounds.contains(it.bounds.centerX(), it.bounds.centerY()) }
            } else {
                pieces.filter { p ->
                    p.bounds.centerY() >= from && p.bounds.centerY() < until
                }
            }.sortedWith(compareBy<Piece> { it.bounds.top }.thenBy { it.bounds.left })

            val addresses = section.map { it.text.replace(Regex("""\s+"""), " ").trim() }
                .filter { addressRegex.containsMatchIn(it) }
            val pickup = addresses.getOrNull(0).orEmpty()
            val destination = addresses.getOrNull(1).orEmpty()
            val distances = section.mapNotNull { p ->
                val m = distanceRegex.find(p.text) ?: return@mapNotNull null
                Pair(p.text, m.groupValues[1].replace(',', '.').toDoubleOrNull())
            }
            val pickupKm = distances.firstOrNull { !it.first.contains("в пути", true) }?.second
            val routeKm = distances.firstOrNull { it.first.contains("в пути", true) }?.second
            val key = fare.toString() + "|" + normalize(pickup) + "|" + normalize(destination)
            visible.add(VisibleOrder(
                OrderRecord(key, fare, pickup, destination, pickupKm, routeKm),
                pricePiece.node, Rect(pricePiece.bounds)
            ))
        }
        return ScanView(visible, scroll, false, false, null,
            pieces.size, clickables, scrollables.size, count)
    }

    private fun findCardBounds(
        priceNode: AccessibilityNodeInfo,
        priceRect: Rect,
        window: Rect,
        prices: List<Pair<Piece, Int>>,
        pieces: List<Piece>
    ): Rect? {
        var ancestor: AccessibilityNodeInfo? = priceNode.parent
        var depth = 0
        var best: Rect? = null
        while (ancestor != null && depth++ < 9) {
            val current = ancestor ?: break
            val box = Rect().also { current.getBoundsInScreen(it) }
            val height = box.height()
            if (height > priceRect.height() * 2 &&
                height < window.height() * 0.72 &&
                box.width() > priceRect.width() * 1.5 &&
                box.contains(priceRect.centerX(), priceRect.centerY())) {
                val priceCount = prices.count {
                    box.contains(it.first.bounds.centerX(), it.first.bounds.centerY())
                }
                val addressCount = pieces.count {
                    box.contains(it.bounds.centerX(), it.bounds.centerY()) &&
                    addressRegex.containsMatchIn(it.text)
                }
                if (priceCount == 1 && addressCount >= 2) {
                    best = box
                    break
                }
            }
            ancestor = current.parent
        }
        return best
    }

    private fun normalize(value: String) =
        value.lowercase().replace(Regex("""\s+"""), " ").trim()
}
