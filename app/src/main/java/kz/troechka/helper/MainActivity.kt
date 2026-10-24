package kz.troechka.helper

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.DateFormat
import java.util.Date

class MainActivity : Activity() {
    private lateinit var details: TextView
    private lateinit var state: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.rgb(13, 20, 18)) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(30), dp(20), dp(32))
        }
        scroll.addView(root)
        root.addView(label("ТРОЕЧКА", 14f, Color.rgb(73, 226, 154), true))
        root.addView(label("Помощник", 30f, Color.WHITE, true))
        root.addView(label("Диагностика интерфейса · v0.1", 14f, Color.LTGRAY, false))
        gap(root, 22)
        state = label("", 16f, Color.WHITE, true)
        card(root, state)
        gap(root, 12)
        details = label("", 16f, Color.WHITE, false)
        card(root, details)
        gap(root, 16)
        button(root, "1. Включить специальный доступ") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        button(root, "2. Открыть Троечку Driver") {
            val launch = packageManager.getLaunchIntentForPackage("taxi.id1702")
            if (launch != null) startActivity(launch)
            else state.text = "Троечка Driver не найдена. Проверь пакет приложения."
        }
        button(root, "3. Обновить результаты") { refresh() }
        button(root, "Очистить статистику") {
            getSharedPreferences("diagnostics", MODE_PRIVATE).edit().clear().apply()
            refresh()
        }
        gap(root, 16)
        root.addView(label(
            "Проверка: включи доступ, открой вкладку «Свободные» в Троечке, " +
            "пролистай несколько заказов вручную, открой карточку (не принимай) " +
            "и вернись сюда.

" +
            "Адреса, телефоны и другой текст карточек не сохраняются. " +
            "Приложение не нажимает кнопки и не подключается к сети.",
            14f, Color.LTGRAY, false
        ))
        setContentView(scroll)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (::details.isInitialized) refresh()
    }

    private fun refresh() {
        val p = getSharedPreferences("diagnostics", MODE_PRIVATE)
        state.text = if (OrderAccessibilityService.isRunning) {
            "● Служба доступности включена"
        } else {
            "○ Служба не запущена\nВключи «Троечка — диагностика списка» в настройках."
        }
        val whenSeen = p.getLong("lastObservedAt", 0L)
        val seen = if (whenSeen == 0L) "Нет данных"
                   else DateFormat.getDateTimeInstance().format(Date(whenSeen))
        fun yn(ok: Boolean) = if (ok) "Да" else "Нет"
        details.text =
            "Последний сигнал: " + seen + "\n" +
            "Замеров: " + p.getLong("observations", 0L) + "\n\n" +
            "Элементов: " + p.getInt("nodes", 0) + "\n" +
            "Текстовых элементов: " + p.getInt("textNodes", 0) + "\n" +
            "Прокручиваемых областей: " + p.getInt("scrollableNodes", 0) + "\n" +
            "Нажимаемых элементов: " + p.getInt("clickableNodes", 0) + "\n" +
            "Цен на последнем экране: " + p.getInt("visiblePriceTexts", 0) + "\n" +
            "Максимум цен на экране: " + p.getInt("maxVisiblePriceTexts", 0) + "\n\n" +
            "Карточка заказа встречалась: " + yn(p.getBoolean("everDetailDetected", false)) + "\n" +
            "Ползунок «Принять» распознан: " + yn(p.getBoolean("everAcceptControlDetected", false))
    }

    private fun label(s: String, size: Float, tint: Int, bold: Boolean) =
        TextView(this).apply {
            text = s
            textSize = size
            setTextColor(tint)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setLineSpacing(dp(3).toFloat(), 1f)
        }

    private fun card(root: LinearLayout, child: View) {
        val shape = GradientDrawable().apply {
            setColor(Color.rgb(30, 43, 39))
            cornerRadius = dp(18).toFloat()
            setStroke(dp(1), Color.rgb(51, 79, 64))
        }
        val box = LinearLayout(this).apply {
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = shape
            addView(child)
        }
        root.addView(box, LinearLayout.LayoutParams(-1, -2))
    }

    private fun button(root: LinearLayout, title: String, action: () -> Unit) {
        val b = Button(this).apply {
            text = title
            isAllCaps = false
            setTextColor(Color.rgb(14, 28, 22))
            setBackgroundColor(Color.rgb(88, 223, 157))
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
