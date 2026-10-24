package kz.troechka.helper

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

class MainActivity : Activity() {
    private val bg = Color.rgb(13, 20, 18)
    private val accent = Color.rgb(79, 231, 160)
    private val muted = Color.rgb(185, 203, 195)
    private lateinit var statusText: TextView
    private lateinit var diagnosticsText: TextView
    private lateinit var resultsTitle: TextView
    private lateinit var results: LinearLayout
    private lateinit var minPrice: EditText
    private lateinit var maxPickup: EditText
    private lateinit var keyword: EditText
    private val uiHandler = Handler(Looper.getMainLooper())
    private val refreshTask = object : Runnable {
        override fun run() {
            if (::statusText.isInitialized) refresh()
            uiHandler.postDelayed(this, 1600)
        }
    }
    private var lastRenderToken = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val viewport = ScrollView(this).apply { setBackgroundColor(bg) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(24), dp(18), dp(32))
        }
        viewport.addView(root)
        root.addView(label("TROECHKA / DRIVER TOOLS", 12f, accent, true))
        root.addView(label("Помощник", 31f, Color.WHITE, true))
        root.addView(label("Сканер списка · v0.3  /  ускоренный поиск", 13f, muted, false))
        gap(root, 20)

        statusText = label("", 16f, Color.WHITE, false)
        card(root, statusText)
        gap(root, 12)

        button(root, "Сканировать все свободные заказы", true) {
            val service = OrderAccessibilityService.live
            if (service == null) {
                statusText.text = "Сначала включи специальный доступ"
            } else {
                service.startScan()
                launchDriver()
            }
        }
        button(root, "Точная допроверка пропусков", false) {
            val service = OrderAccessibilityService.live
            if (service == null) {
                statusText.text = "Сначала включи специальный доступ"
            } else {
                service.startScan(accurate = true)
                launchDriver()
            }
        }
        button(root, "Открыть Троечку Driver", false) { launchDriver() }
        button(root, "Остановить сканирование / поиск", false) {
            OrderAccessibilityService.live?.cancel()
            refresh()
        }
        button(root, "Настроить специальный доступ", false) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        gap(root, 14)
        root.addView(label("ФИЛЬТР ЗАКАЗОВ", 14f, accent, true))
        root.addView(label("Фильтрация выполняется на телефоне. " +
            "Неизвестное расстояние не проходит фильтр максимального расстояния.",
            13f, muted, false))
        gap(root, 9)

        val filters = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
        }
        minPrice = edit("Мин. цена, ₸", numeric = true)
        maxPickup = edit("Макс. подача, км", numeric = true)
        filters.addView(minPrice, LinearLayout.LayoutParams(0, dp(54), 1f).apply {
            rightMargin = dp(6)
        })
        filters.addView(maxPickup, LinearLayout.LayoutParams(0, dp(54), 1f).apply {
            leftMargin = dp(6)
        })
        root.addView(filters)
        gap(root, 9)
        keyword = edit("Слово в адресе подачи или назначения", numeric = false)
        root.addView(keyword, LinearLayout.LayoutParams(-1, dp(54)))

        val pref = getSharedPreferences("filters", MODE_PRIVATE)
        minPrice.setText(pref.getString("minPrice", ""))
        maxPickup.setText(pref.getString("maxPickup", ""))
        keyword.setText(pref.getString("keyword", ""))
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                getSharedPreferences("filters", MODE_PRIVATE).edit()
                    .putString("minPrice", minPrice.text.toString())
                    .putString("maxPickup", maxPickup.text.toString())
                    .putString("keyword", keyword.text.toString()).apply()
                lastRenderToken = ""
                renderResults()
            }
            override fun afterTextChanged(s: Editable?) {}
        }
        minPrice.addTextChangedListener(watcher)
        maxPickup.addTextChangedListener(watcher)
        keyword.addTextChangedListener(watcher)

        gap(root, 18)
        resultsTitle = label("Результаты", 18f, Color.WHITE, true)
        root.addView(resultsTitle)
        gap(root, 10)
        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(results)

        gap(root, 16)
        root.addView(label("ДИАГНОСТИКА", 14f, accent, true))
        gap(root, 9)
        diagnosticsText = label("", 14f, muted, false)
        card(root, diagnosticsText)
        gap(root, 14)
        root.addView(label(
            "Как пользоваться: включи спецдоступ и в «Троечке» открой «Свободные». " +
            "Сначала запусти быстрое сканирование. Если некоторые заказы пропущены, " +
            "выбери «Точная допроверка»: она листает меньшими шагами и дополняет найденное. " +
            "Поиск карточки начинается с текущей позиции, без возврата в начало.\n\n" +
            "Поиск открывает только карточку. Свайп «Принять заказ» НЕ выполняется. " +
            "Адреса остаются в оперативной памяти телефона, доступа к интернету у Помощника нет.",
            13f, muted, false
        ))
        setContentView(viewport)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        uiHandler.removeCallbacks(refreshTask)
        uiHandler.post(refreshTask)
    }

    override fun onPause() {
        uiHandler.removeCallbacks(refreshTask)
        super.onPause()
    }

    private fun launchDriver() {
        val launch = packageManager.getLaunchIntentForPackage("taxi.id1702")
        if (launch != null) startActivity(launch)
        else statusText.text = "Троечка Driver не найдена. Проверь установленное приложение."
    }

    private fun refresh() {
        val model = OrderAccessibilityService.state
        val active = OrderAccessibilityService.isRunning
        statusText.text = (if (active) "● Доступ включён" else "○ Доступ выключен") +
            "\n" + model.status
        diagnosticsText.text =
            "Режим: " + model.phase + "\n" +
            "Пройдено экранов: " + model.pages + "\n" +
            "В памяти заказов: " + model.orders.size + "\n" +
            "Неполных чтений: " + model.incompleteObservations + "\n" +
            "Время: " + String.format(Locale.US, "%.1f", model.elapsedMs / 1000.0) + " с\n" +
            "Узлов интерфейса: " + model.lastNodeCount + "\n" +
            "Текстовых узлов: " + model.lastTextCount + "\n" +
            "Прокручиваемых областей: " + model.lastScrollableCount + "\n" +
            "Карточка: " + (model.lastDetailId ?: "не проверена") + "\n" +
            "Ползунок: " + (if (model.sliderVisible) "обнаружен (НЕ задействован)" else "не подтверждён")
        renderResults()
    }

    private fun renderResults() {
        if (!::results.isInitialized || !::resultsTitle.isInitialized ||
            !::minPrice.isInitialized || !::maxPickup.isInitialized || !::keyword.isInitialized) return
        val state = OrderAccessibilityService.state
        val minValue = minPrice.text.toString().replace(',', '.').toDoubleOrNull() ?: 0.0
        val maxValue = maxPickup.text.toString().replace(',', '.').toDoubleOrNull()
        val word = keyword.text.toString().trim()
        val filtered = state.orders.filter { order ->
            order.price >= minValue &&
                (maxValue == null || (order.pickupKm != null && order.pickupKm <= maxValue)) &&
                (word.isBlank() || (order.pickup + " " + order.destination).contains(word, true))
        }
        val token = state.orders.joinToString(";") { it.key + ":" + it.pickupKm } +
            "|" + minValue + "|" + maxValue + "|" + word
        if (token == lastRenderToken) return
        lastRenderToken = token
        resultsTitle.text = "Найдено: " + state.orders.size + "  ·  Подходят: " + filtered.size
        results.removeAllViews()
        if (filtered.isEmpty()) {
            results.addView(label(
                if (state.orders.isEmpty()) "Пока пусто. Запусти сканирование списка."
                else "По заданным фильтрам заказов нет.",
                14f, muted, false
            ))
            return
        }
        filtered.forEachIndexed { index, order ->
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val price = label(order.price.toString() + " ₸", 24f, accent, true)
            box.addView(price)
            box.addView(label("Подача: " + order.pickup, 14f, Color.WHITE, false))
            box.addView(label("Куда: " + order.destination, 14f, Color.WHITE, false))
            val pickup = order.pickupKm?.let { formatKm(it) } ?: "?"
            val trip = order.routeKm?.let { formatKm(it) } ?: "?"
            box.addView(label("До подачи: " + pickup + " км   ·   Поездка: " + trip + " км",
                13f, muted, false))
            gap(box, 7)
            val open = Button(this).apply {
                text = "Найти в списке и открыть"
                isAllCaps = false
                setTextColor(Color.rgb(12, 30, 22))
                setBackgroundColor(accent)
                setOnClickListener {
                    val service = OrderAccessibilityService.live
                    if (service == null) statusText.text = "Специальный доступ выключен."
                    else if (service.findAndOpen(order.key)) launchDriver()
                    else refresh()
                }
            }
            box.addView(open)
            card(results, box)
            gap(results, 10)
        }
    }

    private fun formatKm(value: Double) =
        String.format(Locale("ru", "RU"), "%.2f", value)

    private fun label(s: String, size: Float, tint: Int, bold: Boolean) =
        TextView(this).apply {
            text = s
            textSize = size
            setTextColor(tint)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setLineSpacing(dp(3).toFloat(), 1f)
        }

    private fun edit(hint: String, numeric: Boolean): EditText =
        EditText(this).apply {
            setHint(hint)
            setHintTextColor(Color.rgb(170, 192, 181))
            setTextColor(Color.WHITE)
            textSize = 14f
            setSingleLine(true)
            inputType = if (numeric) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                        else InputType.TYPE_CLASS_TEXT
            setPadding(dp(12), 0, dp(10), 0)
            background = shape()
        }

    private fun card(root: LinearLayout, child: View) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(15), dp(15), dp(15))
            background = shape()
            addView(child)
        }
        root.addView(box, LinearLayout.LayoutParams(-1, -2))
    }

    private fun shape() = GradientDrawable().apply {
        setColor(Color.rgb(30, 43, 39))
        cornerRadius = dp(16).toFloat()
        setStroke(dp(1), Color.rgb(52, 78, 65))
    }

    private fun button(root: LinearLayout, caption: String, primary: Boolean, action: () -> Unit) {
        val b = Button(this).apply {
            text = caption
            isAllCaps = false
            setTextColor(if (primary) Color.rgb(12, 30, 22) else Color.WHITE)
            setBackgroundColor(if (primary) accent else Color.rgb(39, 67, 53))
            setOnClickListener { action() }
        }
        root.addView(b, LinearLayout.LayoutParams(-1, -2))
        gap(root, 6)
    }

    private fun gap(root: LinearLayout, height: Int) {
        root.addView(View(this), LinearLayout.LayoutParams(1, dp(height)))
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
}
