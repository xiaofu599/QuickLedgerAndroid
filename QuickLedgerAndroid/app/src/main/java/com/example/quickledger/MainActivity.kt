package com.example.quickledger

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore

data class Entry(val id: Long, val type: String, val amount: Long, val category: String, val date: String, val note: String)

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("quick_ledger", MODE_PRIVATE) }
    private val entries = mutableListOf<Entry>()
    private var budgetCents = 0L
    private var filterType = "全部"
    private var filterMonth = monthNow()
    private var query = ""
    private lateinit var root: LinearLayout
    private lateinit var summaryBox: LinearLayout
    private lateinit var chart: SpendChart
    private lateinit var listBox: LinearLayout
    private lateinit var searchInput: EditText
    private lateinit var monthSpinner: Spinner
    private lateinit var typeSpinner: Spinner
    private val purple = 0xFF507D65.toInt()
    private val green = 0xFF427C61.toInt()
    private val red = 0xFFC77A65.toInt()
    private val ink = 0xFF303B33.toInt()
    private val muted = 0xFF8B9188.toInt()
    private val bg = 0xFFF8F6F0.toInt()
    private val white = 0xFFFFFEFA.toInt()
    private val cats = mapOf(
        "支出" to listOf("餐饮","交通","购物","日用","住房","娱乐","医疗","学习","其他"),
        "收入" to listOf("工资","奖金","兼职","理财","红包","其他")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        loadEntries()
        budgetCents = prefs.getLong("budget_cents", 0L)
        // No PIN/password lock: open the ledger directly.
        buildApp()
    }

    private fun money(cents: Long): String = "¥" + String.format(Locale.CHINA, "%,.2f", cents / 100.0)
    private fun monthNow(): String = SimpleDateFormat("yyyy-MM", Locale.US).format(Date())
    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    private fun month(date: String): String = if (date.length >= 7) date.substring(0, 7) else ""
    private fun dp(n: Int): Int = (n * resources.displayMetrics.density + .5f).toInt()

    private fun loadEntries() {
        entries.clear()
        val blob = prefs.getString("entries_enc", null) ?: return
        try {
            val plain = decrypt(blob)
            val arr = JSONArray(plain)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val type = o.optString("type")
                val amount = o.optLong("amount")
                val date = o.optString("date")
                if ((type == "收入" || type == "支出") && amount > 0 && date.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) {
                    entries.add(Entry(o.optLong("id", System.currentTimeMillis()+i), type, amount,
                        o.optString("category", "其他"), date, o.optString("note")))
                }
            }
        } catch (_: Exception) {
            Toast.makeText(this, "本地数据读取失败；请检查是否为本应用数据。", Toast.LENGTH_LONG).show()
        }
    }

    private fun saveEntries() {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(JSONObject().put("id", e.id).put("type", e.type).put("amount", e.amount)
                .put("category", e.category).put("date", e.date).put("note", e.note))
        }
        try {
            prefs.edit().putString("entries_enc", encrypt(arr.toString())).apply()
        } catch (_: Exception) {
            Toast.makeText(this, "保存失败，请先导出备份。", Toast.LENGTH_LONG).show()
        }
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = ks.getKey("quick_ledger_aes", null) as? SecretKey
        if (existing != null) return existing
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder("quick_ledger_aes",
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
        return gen.generateKey()
    }
    private fun encrypt(text: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val bytes = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        return android.util.Base64.encodeToString(iv + bytes, android.util.Base64.NO_WRAP)
    }
    private fun decrypt(blob: String): String {
        val all = android.util.Base64.decode(blob, android.util.Base64.NO_WRAP)
        val iv = all.copyOfRange(0, 12)
        val body = all.copyOfRange(12, all.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(body), Charsets.UTF_8)
    }

    private fun buildApp() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(bg)
        }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(14))
            setBackgroundColor(bg)
        }
        bottom.addView(button("＋ 记一笔", purple) { showEntryDialog() },
            LinearLayout.LayoutParams(-1, dp(54)))
        root.addView(bottom)
        setContentView(root)

        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; orientation = LinearLayout.HORIZONTAL }
        val titleCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleCol.addView(text("随手记账", 30, ink, true))
        titleCol.addView(text("每一笔日常，都值得好好记录", 13, muted))
        header.addView(titleCol, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(button("年度账单  ›", 0xFFE9F0E7.toInt(), purple) { showAnnualBill() })
        content.addView(header)
        content.addView(space(20))

        summaryBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(summaryBox)
        content.addView(space(12))
        content.addView(sectionTitle("本月收支趋势"))
        chart = SpendChart(this)
        val chartCard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(10), dp(8), dp(4)); background = rounded(white, 22) }
        chartCard.addView(text("收入（绿） · 支出（橙）  单位：元", 12, muted))
        chartCard.addView(text("左右滑动查看每天金额", 12, muted))
        val chartScroll = HorizontalScrollView(this).apply { isFillViewport = true }
        chartScroll.addView(chart, ViewGroup.LayoutParams(dp(70 * Calendar.getInstance().get(Calendar.DAY_OF_MONTH) + 72), dp(230)))
        chartCard.addView(chartScroll, LinearLayout.LayoutParams(-1, dp(242)))
        content.addView(chartCard)
        content.addView(space(12))

        content.addView(button("收支饼图 · 按月 / 按日  ›", 0xFFE9F0E7.toInt(), purple) { showStatistics() })
        content.addView(space(12))
        val budgetRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        budgetRow.addView(text("月度预算", 17, ink, true), LinearLayout.LayoutParams(0, -2, 1f))
        budgetRow.addView(button("设置预算", 0xFFE9F0E7.toInt(), purple) { showBudgetDialog() })
        content.addView(budgetRow)
        content.addView(space(6))
        val budgetInfo = text("", 14, muted)
        budgetInfo.tag = "budgetInfo"
        content.addView(budgetInfo)
        content.addView(space(14))

        content.addView(sectionTitle("账单明细"))
        searchInput = EditText(this).apply {
            hint = "搜索备注或分类"; setSingleLine(true)
            textSize = 15f
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(white, 14)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { query = s?.toString()?.trim() ?: ""; renderList() }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
        }
        content.addView(searchInput)
        content.addView(space(8))
        val filters = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        monthSpinner = Spinner(this)
        typeSpinner = Spinner(this)
        filters.addView(monthSpinner, LinearLayout.LayoutParams(0, dp(46), 1f))
        filters.addView(typeSpinner, LinearLayout.LayoutParams(0, dp(46), 1f))
        content.addView(filters)
        content.addView(space(4))
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(listBox)
        content.addView(space(14))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(button("导出备份", 0xFFE9F0E7.toInt(), purple) { exportBackup() }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(spaceH(8))
        actions.addView(button("恢复备份", 0xFFE9F0E7.toInt(), purple) { importBackup() }, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(actions)
        content.addView(space(8))
        val privacy = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        privacy.addView(button("清空账单", 0xFFF8EBE5.toInt(), red) { clearEntries() }, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(privacy)
        content.addView(space(10))
        content.addView(text("数据默认加密保存在本机。导出的备份文件包含可读账单，请妥善保管。", 12, muted))
        fillFilters()
        renderAll()
    }

    private fun rounded(color: Int, radiusDp: Int): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }

    private fun text(s: String, size: Int, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply { text = s; textSize = size.toFloat(); setTextColor(color); if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD) }
    private fun space(height: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(height)) }
    private fun spaceH(width: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(width), 1) }
    private fun sectionTitle(s: String) = text(s, 18, ink, true).apply { setPadding(dp(2), dp(6), 0, dp(12)) }
    private fun button(label: String, color: Int, textColor: Int = white, action: () -> Unit): Button =
        Button(this).apply {
            text = label; setTextColor(textColor); textSize = 13f; isAllCaps = false
            background = rounded(color, 18)
            minimumHeight = dp(44)
            setPadding(dp(12), dp(6), dp(12), dp(6)); setOnClickListener { action() }
        }

    private fun fillFilters() {
        val months = (entries.map { month(it.date) } + monthNow()).distinct().sortedDescending()
        val monthOptions = listOf("全部月份") + months
        monthSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, monthOptions)
        monthSpinner.setSelection(monthOptions.indexOf(filterMonth).takeIf { it >= 0 } ?: 0)
        typeSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("全部", "支出", "收入"))
        monthSpinner.onItemSelectedListener = simpleListener { pos ->
            filterMonth = monthOptions[pos]
            renderList()
        }
        typeSpinner.onItemSelectedListener = simpleListener { pos ->
            filterType = listOf("全部", "支出", "收入")[pos]
            renderList()
        }
    }
    private fun simpleListener(fn: (Int) -> Unit) = object : android.widget.AdapterView.OnItemSelectedListener {
        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) = fn(position)
    }

    private fun renderAll() {
        summaryBox.removeAllViews()
        val thisMonth = entries.filter { month(it.date) == monthNow() }
        val exp = thisMonth.filter { it.type == "支出" }.sumOf { it.amount }
        val inc = thisMonth.filter { it.type == "收入" }.sumOf { it.amount }
        val balance = inc - exp
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(22), dp(22), dp(22))
            background = rounded(purple, 26)
        }
        hero.addView(text("本月结余  ·  ${monthNow()}", 13, 0xFFE2EEE4.toInt()))
        hero.addView(space(12))
        hero.addView(text(money(balance), 34, white, true))
        hero.addView(space(10))
        hero.addView(text("认真生活，轻松掌握每一笔收支", 12, 0xFFE2EEE4.toInt()))
        summaryBox.addView(hero, LinearLayout.LayoutParams(-1, -2))
        summaryBox.addView(space(12))
        val detailRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(Triple("↗  本月收入", inc, green), Triple("↘  本月支出", exp, red)).forEachIndexed { i, item ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(17), dp(12), dp(17))
                background = rounded(white, 22)
            }
            card.addView(text(item.first, 13, muted))
            card.addView(space(10))
            card.addView(text(money(item.second), 20, item.third, true))
            val lp = LinearLayout.LayoutParams(0, -2, 1f)
            if (i > 0) lp.leftMargin = dp(10)
            card.setOnClickListener { showRanking(if (i == 0) "收入" else "支出", monthNow()) }
            detailRow.addView(card, lp)
        }
        summaryBox.addView(detailRow)
        val days = 1..Calendar.getInstance().get(Calendar.DAY_OF_MONTH)
        val daily = days.map { day ->
            val date = monthNow() + "-" + String.format(Locale.US, "%02d", day)
            val records = thisMonth.filter { it.date == date }
            Pair(records.filter { it.type == "收入" }.sumOf { it.amount } / 100.0,
                records.filter { it.type == "支出" }.sumOf { it.amount } / 100.0)
        }
        chart.setValues(daily)
        val spent = exp
        val info = root.findViewWithTag<TextView>("budgetInfo")
        if (info != null) {
            if (budgetCents <= 0) info.text = "尚未设置预算"
            else {
                val percent = (spent.toDouble() / budgetCents * 100).toInt()
                info.text = "已支出 ${money(spent)} / ${money(budgetCents)}（${percent}%）"
                if (spent > budgetCents) {
                    info.setTextColor(red)
                    if (prefs.getString("last_budget_alert", "") != monthNow()) {
                        Toast.makeText(this, "本月支出已超过预算！", Toast.LENGTH_LONG).show()
                        prefs.edit().putString("last_budget_alert", monthNow()).apply()
                    }
                } else info.setTextColor(muted)
            }
        }
        renderList()
    }

    private fun renderList() {
        if (!::listBox.isInitialized) return
        listBox.removeAllViews()
        val shown = entries.filter { e ->
            (filterMonth == "全部月份" || month(e.date) == filterMonth) &&
            (filterType == "全部" || e.type == filterType) &&
            (query.isBlank() || e.note.contains(query, true) || e.category.contains(query, true))
        }.sortedWith(compareByDescending<Entry> { it.date }.thenByDescending { it.id })
        if (shown.isEmpty()) {
            val empty = text("🧾  暂无账单记录\n记下第一笔，让收支一目了然", 14, muted).apply { gravity = Gravity.CENTER; setPadding(0, dp(36), 0, dp(36)); background = rounded(white, 22) }
            listBox.addView(empty); return
        }
        shown.forEach { e ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(13), dp(14), dp(13)); background = rounded(white, 20) }
            val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            left.addView(text("${if (e.type == "支出") "◉" else "✦"}  ${if (e.note.isNotBlank()) e.note else e.category}", 15, ink, true))
            left.addView(space(4))
            left.addView(text(e.category + "  ·  " + e.date, 12, muted))
            row.addView(left, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(text((if (e.type == "支出") "−" else "+") + money(e.amount), 15, if (e.type == "支出") red else green, true))
            row.isClickable = true
            row.setOnClickListener { showEntryDialog(e) }
            row.setOnLongClickListener {
                AlertDialog.Builder(this).setMessage("删除这条账单？")
                    .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ ->
                        entries.remove(e); saveEntries(); renderAll(); fillFilters()
                    }.show(); true
            }
            listBox.addView(row)
            listBox.addView(space(7))
        }
    }

    // 年度账单：不改变原始账单或加密格式
    private fun showAnnualBill(initialYear: Int = Calendar.getInstance().get(Calendar.YEAR)) {
        var selectedYear = initialYear
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(12))
            setBackgroundColor(bg)
        }
        val yearRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val prev = button("‹", 0xFFE9F0E7.toInt(), purple) { }
        val next = button("›", 0xFFE9F0E7.toInt(), purple) { }
        val yearText = text("${selectedYear} 年", 21, ink, true).apply { gravity = Gravity.CENTER }
        yearRow.addView(prev, LinearLayout.LayoutParams(dp(52), dp(48)))
        yearRow.addView(yearText, LinearLayout.LayoutParams(0, dp(48), 1f))
        yearRow.addView(next, LinearLayout.LayoutParams(dp(52), dp(48)))
        wrapper.addView(yearRow)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wrapper.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        val dialog = AlertDialog.Builder(this).setView(wrapper).setNegativeButton("返回首页", null).create()
        fun drawYear() {
            yearText.text = "${selectedYear} 年"
            body.removeAllViews()
            val yearEntries = entries.filter { it.date.startsWith("$selectedYear-") }
            val inc = yearEntries.filter { it.type == "收入" }.sumOf { it.amount }
            val exp = yearEntries.filter { it.type == "支出" }.sumOf { it.amount }
            val hero = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(20), dp(20), dp(20))
                background = rounded(0xFFFFDB5C.toInt(), 22)
            }
            hero.addView(text("年结余", 14, 0xFF65531D.toInt()))
            hero.addView(space(8))
            hero.addView(text(money(inc - exp), 29, 0xFF2D2A20.toInt(), true))
            hero.addView(space(12))
            hero.addView(text("年收入 ${money(inc)}    年支出 ${money(exp)}", 14, 0xFF51451F.toInt()))
            body.addView(hero)
            body.addView(space(14))
            val headings = LinearLayout(this).apply { setPadding(dp(8), dp(12), dp(8), dp(12)) }
            listOf("月份", "月收入", "月支出", "月结余").forEachIndexed { i, name ->
                headings.addView(text(name, 12, muted), LinearLayout.LayoutParams(0, -2, if (i == 0) .65f else 1f))
            }
            body.addView(headings)
            for (m in 12 downTo 1) {
                val ym = String.format(Locale.US, "%04d-%02d", selectedYear, m)
                val records = yearEntries.filter { month(it.date) == ym }
                val income = records.filter { it.type == "收入" }.sumOf { it.amount }
                val expense = records.filter { it.type == "支出" }.sumOf { it.amount }
                val row = LinearLayout(this).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(8), dp(16), dp(8), dp(16))
                    background = rounded(white, 12)
                }
                listOf("${m}月", String.format(Locale.CHINA, "%.2f", income / 100.0),
                    String.format(Locale.CHINA, "%.2f", expense / 100.0),
                    String.format(Locale.CHINA, "%.2f", (income - expense) / 100.0)).forEachIndexed { i, v ->
                    row.addView(text(v, 12, if (i == 3 && income < expense) red else ink, i == 0),
                        LinearLayout.LayoutParams(0, -2, if (i == 0) .65f else 1f))
                }
                row.setOnClickListener {
                    dialog.dismiss()
                    filterMonth = ym
                    fillFilters()
                    renderList()
                    Toast.makeText(this, "已筛选 $ym 账单", Toast.LENGTH_SHORT).show()
                }
                body.addView(row)
                body.addView(space(3))
            }
        }
        // 直接在当前弹窗更新年份，避免叠加多个弹窗
        prev.setOnClickListener { selectedYear--; drawYear() }
        next.setOnClickListener { selectedYear++; drawYear() }
        drawYear()
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * .85f).toInt())
    }

    private fun showRanking(type: String, targetMonth: String) {
        var sortByAmount = true
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
            setBackgroundColor(bg)
        }
        val title = text("${targetMonth.replace('-', '年')}月 · ${type}排行", 21, ink, true)
        wrapper.addView(title)
        wrapper.addView(space(10))
        val total = entries.filter { it.type == type && month(it.date) == targetMonth }.sumOf { it.amount }
        wrapper.addView(text("本月总$type", 13, muted))
        wrapper.addView(text(money(total), 30, ink, true))
        wrapper.addView(space(14))
        val orderButton = button("按金额排序  ⇅", 0xFFE9F0E7.toInt(), purple) { }
        wrapper.addView(orderButton)
        wrapper.addView(space(10))
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wrapper.addView(ScrollView(this).apply { addView(rows) }, LinearLayout.LayoutParams(-1, 0, 1f))
        val dialog = AlertDialog.Builder(this).setView(wrapper).setNegativeButton("返回", null).create()
        fun drawRows() {
            rows.removeAllViews()
            val filtered = entries.filter { it.type == type && month(it.date) == targetMonth }
            val ranked = if (sortByAmount) filtered.sortedWith(compareByDescending<Entry> { it.amount }.thenByDescending { it.date })
                else filtered.sortedWith(compareByDescending<Entry> { it.date }.thenByDescending { it.id })
            if (ranked.isEmpty()) {
                rows.addView(text("这个月还没有${type}记录", 14, muted).apply { setPadding(0, dp(24), 0, dp(24)) })
            }
            ranked.forEach { e ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), dp(13), dp(12), dp(13))
                    background = rounded(white, 14)
                }
                val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
                top.addView(text(if (e.note.isBlank()) e.category else e.note, 15, ink, true), LinearLayout.LayoutParams(0, -2, 1f))
                top.addView(text(money(e.amount), 15, if (type == "收入") green else red, true))
                row.addView(top)
                row.addView(space(7))
                val percentage = if (total == 0L) 0.0 else e.amount.toDouble() / total * 100
                row.addView(text("${e.date}  ·  ${e.category}  ·  ${String.format(Locale.US, "%.1f", percentage)}%", 12, muted))
                row.addView(space(6))
                val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 1000
                    progress = (percentage * 10).toInt().coerceIn(0, 1000)
                    progressTintList = android.content.res.ColorStateList.valueOf(if (type == "收入") 0xFFF1CE47.toInt() else green)
                }
                row.addView(progress, LinearLayout.LayoutParams(-1, dp(5)))
                row.setOnClickListener { dialog.dismiss(); showEntryDialog(e) }
                rows.addView(row)
                rows.addView(space(7))
            }
        }
        orderButton.setOnClickListener {
            sortByAmount = !sortByAmount
            orderButton.text = if (sortByAmount) "按金额排序  ⇅" else "按时间排序  ⇅"
            drawRows()
        }
        drawRows()
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * .83f).toInt())
    }

    private fun showStatistics() {
        var daily = false
        var selectedDate = today()
        var selectedType = "支出"
        var smart = true
        var parentCategory: String? = null
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(8))
            setBackgroundColor(bg)
        }
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val period = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("按月", "按日")) }
        val type = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("支出", "收入")) }
        controls.addView(period, LinearLayout.LayoutParams(0, dp(48), 1f))
        controls.addView(type, LinearLayout.LayoutParams(0, dp(48), 1f))
        wrapper.addView(controls)
        val dateButton = button("", 0xFFE9F0E7.toInt(), purple) { }
        wrapper.addView(dateButton)
        val mode = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("备注智能分类", "账单原类别")) }
        wrapper.addView(mode)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wrapper.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        val dialog = AlertDialog.Builder(this).setTitle("收支分类统计").setView(wrapper).setNegativeButton("关闭", null).create()
        fun draw() {
            body.removeAllViews()
            dateButton.text = (if (daily) selectedDate else month(selectedDate)) + "  · 点击选择日期"
            val records = entries.filter { it.type == selectedType && (if (daily) it.date == selectedDate else month(it.date) == month(selectedDate)) }
            fun classification(e: Entry): Pair<String, String> = if (smart) NoteClassifier.classify(e.type, e.category, e.note) else Pair(e.category, if (e.note.isBlank()) "无备注" else e.note)
            val selected = records.filter { parentCategory == null || classification(it).first == parentCategory }
            val total = selected.sumOf { it.amount }
            body.addView(text("${parentCategory ?: "全部分类"} · $selectedType ${money(total)}", 18, ink, true))
            body.addView(text(if (parentCategory == null) "点击分类查看细分" else "占比以当前大类总金额为基准", 12, muted))
            if (parentCategory != null) body.addView(button("‹ 返回全部分类", 0xFFE9F0E7.toInt(), purple) { parentCategory = null; draw() })
            val groups = selected.groupBy { if (parentCategory == null) classification(it).first else classification(it).second }
                .entries.sortedByDescending { it.value.sumOf { e -> e.amount } }
            if (total == 0L) {
                body.addView(text("这个时间段暂无${selectedType}账单", 16, muted).apply { setPadding(0, dp(32), 0, dp(32)) })
                return
            }
            val slices = groups.map { it.value.sumOf { e -> e.amount } }
            val pie = LedgerPie(this)
            pie.amounts = slices
            pie.contentDescription = groups.joinToString("；") { "${it.key} ${money(it.value.sumOf { e -> e.amount })}" }
            body.addView(pie, LinearLayout.LayoutParams(-1, dp(230)))
            groups.forEachIndexed { index, group ->
                val amount = slices[index]
                val percentage = amount.toDouble() / total * 100
                val percentText = if (percentage < 0.01) "<0.01" else String.format(Locale.CHINA, "%.2f", percentage)
                val row = text("● ${group.key}\n${money(amount)}  ·  $percentText%  ·  ${group.value.size}笔  ›", 15, LedgerPie.colors[index % LedgerPie.colors.size]).apply {
                    setPadding(dp(10), dp(12), dp(10), dp(12)); background = rounded(white, 12)
                }
                row.setOnClickListener {
                    if (parentCategory == null) { parentCategory = group.key; draw() }
                    else {
                        val detail = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)) }
                        group.value.sortedByDescending { it.date }.forEach { e ->
                            detail.addView(text("${e.date}  ${money(e.amount)}\n${e.category} · ${e.note.ifBlank { "无备注" }}", 15, ink).apply { setPadding(0, dp(12), 0, dp(12)) })
                        }
                        AlertDialog.Builder(this).setTitle(group.key).setView(ScrollView(this).apply { addView(detail) }).setPositiveButton("返回", null).show()
                    }
                }
                body.addView(row); body.addView(space(6))
            }
            body.addView(text("金额按分汇总；百分比四舍五入。备注分类仅用于统计，不修改原账单。未匹配备注保留原类别。外卖关键词优先于早/午/晚餐，同一笔只计入一个子类。", 12, muted))
        }
        dateButton.setOnClickListener {
            val parts = selectedDate.split('-').map { it.toInt() }
            android.app.DatePickerDialog(this, { _, y, m, d ->
                selectedDate = String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, d)
                parentCategory = null; draw()
            }, parts[0], parts[1] - 1, parts[2]).show()
        }
        period.onItemSelectedListener = simpleListener { daily = it == 1; parentCategory = null; draw() }
        type.onItemSelectedListener = simpleListener { selectedType = if (it == 0) "支出" else "收入"; parentCategory = null; draw() }
        mode.onItemSelectedListener = simpleListener { smart = it == 0; parentCategory = null; draw() }
        draw(); dialog.show()
        dialog.window?.setLayout(-1, (resources.displayMetrics.heightPixels * .9f).toInt())
    }

    private fun showEntryDialog(existing: Entry? = null) {
        var type = existing?.type ?: "支出"
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(4)) }
        val typeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("支出", "收入"))
            setSelection(if (type == "收入") 1 else 0)
        }
        val amount = EditText(this).apply {
            hint = "金额（元）"; textSize = 18f; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            existing?.let { setText(String.format(Locale.US, "%.2f", it.amount / 100.0)) }
        }
        val category = Spinner(this)
        // Spinner delivers an initial selection callback after layout. Do not rebuild
        // the category adapter unless the type really changed.
        val rememberedCategories = mutableMapOf<String, String>()
        existing?.let { rememberedCategories[it.type] = it.category }
        fun updateCats(preferred: String? = rememberedCategories[type]) {
            val options = cats[type].orEmpty().toMutableList()
            // Preserve categories restored from backups, including custom/legacy names.
            if (!preferred.isNullOrBlank() && preferred !in options) options.add(preferred)
            category.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options)
            category.setSelection(options.indexOf(preferred).takeIf { it >= 0 } ?: 0)
        }
        updateCats()
        typeSpinner.onItemSelectedListener = simpleListener { pos ->
            val selectedType = if (pos == 0) "支出" else "收入"
            if (selectedType != type) {
                category.selectedItem?.toString()?.let { rememberedCategories[type] = it }
                type = selectedType
                updateCats()
            }
        }
        val date = EditText(this).apply { setText(existing?.date ?: today()); hint = "日期 YYYY-MM-DD"; isSingleLine = true }
        // Reuse notes from saved bills, including bills created before this update.
        // Existing encrypted bill storage also preserves these choices after restart.
        val savedNotes = entries.sortedByDescending { it.id }.map { it.note.trim() }
            .filter { it.isNotEmpty() }.distinct()
        val note = AutoCompleteTextView(this).apply {
            hint = "备注（选填，可选择历史备注）"; isSingleLine = true
            threshold = 1
            filters = arrayOf(android.text.InputFilter.LengthFilter(100))
            setAdapter(ArrayAdapter(this@MainActivity, android.R.layout.simple_dropdown_item_1line, savedNotes))
            setText(existing?.note.orEmpty(), false)
        }
        layout.addView(labelled("类型", typeSpinner)); layout.addView(labelled("金额", amount))
        layout.addView(labelled("分类", category)); layout.addView(labelled("日期", date)); layout.addView(labelled("备注", note))
        if (savedNotes.isNotEmpty()) {
            layout.addView(text("最近备注 · 点一下即可填入，左右滑动查看更多", 12, muted))
            val quickNotes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            savedNotes.take(8).forEach { saved ->
                quickNotes.addView(button(saved, 0xFFE9F0E7.toInt(), purple) {
                    note.setText(saved, false)
                    note.setSelection(note.text.length)
                    note.dismissDropDown()
                })
                quickNotes.addView(spaceH(6))
            }
            layout.addView(HorizontalScrollView(this).apply { addView(quickNotes) })
            layout.addView(button("全部历史备注（${savedNotes.size}）", 0xFFE9F0E7.toInt(), purple) {
                val chooserBody = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), dp(8), dp(16), dp(8))
                }
                val search = EditText(this).apply { hint = "搜索历史备注"; isSingleLine = true }
                val choices = ListView(this)
                val noteAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, savedNotes)
                choices.adapter = noteAdapter
                chooserBody.addView(search)
                chooserBody.addView(choices, LinearLayout.LayoutParams(-1, 0, 1f))
                val chooser = AlertDialog.Builder(this).setTitle("选择历史备注")
                    .setView(chooserBody).setNegativeButton("取消", null).create()
                search.addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { noteAdapter.filter.filter(s) }
                    override fun afterTextChanged(s: android.text.Editable?) {}
                })
                choices.setOnItemClickListener { _, _, position, _ ->
                    note.setText(noteAdapter.getItem(position).orEmpty(), false)
                    note.setSelection(note.text.length)
                    note.dismissDropDown()
                    chooser.dismiss()
                }
                chooser.show()
                chooser.window?.setLayout(-1, (resources.displayMetrics.heightPixels * .65f).toInt())
            })
        } else {
            layout.addView(text("保存账单后，备注会自动出现在这里，供下次快捷选用。", 12, muted))
        }
        AlertDialog.Builder(this).setTitle(if (existing == null) "记一笔" else "编辑账单").setView(ScrollView(this).apply { addView(layout) }).setNegativeButton("取消", null)
            .setPositiveButton("保存", null).create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val value = amount.text.toString().toBigDecimalOrNull()
                        val dateText = date.text.toString().trim()
                        val cents = try { value?.movePointRight(2)?.longValueExact() } catch (_: Exception) { null }
                        if (cents == null || cents <= 0) { amount.error = "请输入有效金额（最多两位小数）"; return@setOnClickListener }
                        if (!validDate(dateText)) { date.error = "日期格式应为 YYYY-MM-DD"; return@setOnClickListener }
                        val updated = Entry(
                            existing?.id ?: System.currentTimeMillis(), type, cents,
                            category.selectedItem?.toString() ?: "其他", dateText, note.text.toString().trim()
                        )
                        if (existing == null) entries.add(updated) else {
                            val index = entries.indexOfFirst { it.id == existing.id }
                            if (index >= 0) entries[index] = updated else entries.add(updated)
                        }
                        saveEntries(); fillFilters(); renderAll(); dialog.dismiss()
                    }
                }
                dialog.show()
            }
    }
    private fun labelled(label: String, view: View): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, dp(4)) }
        box.addView(text(label, 12, muted)); box.addView(view); return box
    }
    private fun validDate(s: String): Boolean {
        if (!s.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) return false
        return try {
            val f = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }
            f.parse(s) != null
        } catch (_: Exception) { false }
    }

    private fun showBudgetDialog() {
        val input = EditText(this).apply {
            hint = "每月预算（元）"; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            if (budgetCents > 0) setText(String.format(Locale.US, "%.2f", budgetCents / 100.0))
        }
        AlertDialog.Builder(this).setTitle("设置月度预算").setView(input).setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val value = input.text.toString().toBigDecimalOrNull()
                val cents = try { value?.movePointRight(2)?.longValueExact() } catch (_: Exception) { null }
                if (cents == null || cents < 0) Toast.makeText(this, "请输入有效预算金额", Toast.LENGTH_SHORT).show()
                else { budgetCents = cents; prefs.edit().putLong("budget_cents", budgetCents).apply(); renderAll() }
            }.show()
    }

    private fun exportBackup() {
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "application/json"; putExtra(Intent.EXTRA_TITLE, "随手记账备份_${today()}.json")
        }
        startActivityForResult(i, 1001)
    }
    private fun importBackup() {
        AlertDialog.Builder(this).setTitle("恢复备份").setMessage("导入会替换当前所有账单和预算。建议先导出当前数据备份。")
            .setNegativeButton("取消", null).setPositiveButton("选择备份") { _, _ ->
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "application/json" }
                startActivityForResult(i, 1002)
            }.show()
    }
    @Deprecated("Use document result APIs")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        val uri = data.data!!
        try {
            if (requestCode == 1001) {
                val obj = JSONObject().put("app", "QuickLedger").put("version", 1).put("budgetCents", budgetCents)
                val arr = JSONArray()
                entries.forEach { e -> arr.put(JSONObject().put("id", e.id).put("type", e.type).put("amount", e.amount).put("category", e.category).put("date", e.date).put("note", e.note)) }
                obj.put("entries", arr)
                contentResolver.openOutputStream(uri)?.use { it.write(obj.toString(2).toByteArray(Charsets.UTF_8)) }
                Toast.makeText(this, "备份已导出。请妥善保管该文件。", Toast.LENGTH_LONG).show()
            } else if (requestCode == 1002) {
                val raw = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: error("文件无法读取")
                val obj = JSONObject(raw)
                require(obj.optString("app") == "QuickLedger") { "不是随手记账备份文件" }
                val arr = obj.getJSONArray("entries")
                val restored = mutableListOf<Entry>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val typ = o.getString("type"); val amt = o.getLong("amount"); val dt = o.getString("date")
                    require((typ == "收入" || typ == "支出") && amt > 0 && validDate(dt)) { "备份中包含无效记录" }
                    restored.add(Entry(o.optLong("id", System.currentTimeMillis()+i), typ, amt, o.optString("category", "其他"), dt, o.optString("note", "")))
                }
                entries.clear(); entries.addAll(restored)
                budgetCents = obj.optLong("budgetCents", 0L).coerceAtLeast(0)
                prefs.edit().putLong("budget_cents", budgetCents).apply()
                saveEntries(); fillFilters(); renderAll()
                Toast.makeText(this, "已恢复 ${entries.size} 条记录", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "操作失败：${e.message ?: "文件格式不正确"}", Toast.LENGTH_LONG).show()
        }
    }

    private fun clearEntries() {
        AlertDialog.Builder(this).setTitle("清空全部账单？").setMessage("此操作不可撤销。建议先导出备份。")
            .setNegativeButton("取消", null).setPositiveButton("清空") { _, _ ->
                entries.clear(); saveEntries(); fillFilters(); renderAll()
            }.show()
    }


}

class SpendChart(context: android.content.Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var values: List<Pair<Double, Double>> = emptyList()
    private val density = resources.displayMetrics.density
    private fun d(value: Float) = value * density

    fun setValues(v: List<Pair<Double, Double>>) {
        values = v
        layoutParams?.let { params ->
            paint.textSize = d(10f)
            val longest = v.flatMap { listOf(it.first, it.second) }.maxOfOrNull {
                paint.measureText(String.format(Locale.CHINA, "%.2f", it))
            } ?: 0f
            val slotWidth = maxOf(d(70f), longest * 2 + d(12f))
            params.width = (slotWidth * v.size.coerceAtLeast(1) + d(100f)).toInt()
            layoutParams = params
        }
        contentDescription = "每日收支，单位元。" + v.mapIndexed { i, pair ->
            "${i + 1}日收入${pair.first}，支出${pair.second}"
        }.joinToString("；")
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // View's default measurement may collapse inside a scrolling parent.
        val desired = layoutParams?.width?.takeIf { it > 0 } ?: (d(80f) * values.size + d(100f)).toInt()
        setMeasuredDimension(desired, resolveSize(d(230f).toInt(), heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val maximum = values.maxOfOrNull { maxOf(it.first, it.second) } ?: 0.0
        // Rounded linear ticks adapt to small amounts, instead of an unlabeled scale.
        val rawStep = maximum.coerceAtLeast(1.0) / 4.0
        val magnitude = 10.0.pow(floor(log10(rawStep)))
        val fraction = rawStep / magnitude
        val step = (listOf(1.0, 2.0, 2.5, 5.0, 10.0).first { it >= fraction }) * magnitude
        val tickCount = ceil(maximum.coerceAtLeast(1.0) / step).toInt().coerceAtLeast(1)
        val ceiling = step * tickCount
        paint.textSize = d(11f)
        fun tickText(value: Double): String = when {
            value >= 1000000 -> String.format(Locale.CHINA, "%.1f百万", value / 1000000)
            value >= 10000 -> String.format(Locale.CHINA, "%.1f万", value / 10000)
            step < 1.0 -> String.format(Locale.CHINA, "%.2f", value)
            step % 1.0 != 0.0 -> String.format(Locale.CHINA, "%.1f", value)
            else -> String.format(Locale.CHINA, "%.0f", value)
        }
        val left = maxOf(d(48f), paint.measureText(tickText(ceiling)) + d(10f))
        val right = width - d(12f)
        val top = d(30f)
        val bottom = height - d(28f)
        paint.strokeWidth = d(1f)
        for (i in 0..tickCount) {
            val y = bottom - (bottom - top) * i / tickCount
            paint.color = 0xFFE8EAE2.toInt()
            canvas.drawLine(left, y, right, y, paint)
            paint.color = 0xFF8B9188.toInt()
            paint.textAlign = Paint.Align.RIGHT
            canvas.drawText(tickText(step * i), left - d(6f), y + d(4f), paint)
        }
        if (values.isEmpty()) return
        val slot = (right - left) / values.size
        val barWidth = minOf(d(16f), slot * .22f)
        values.forEachIndexed { index, pair ->
            val center = left + slot * (index + .5f)
            listOf(pair.first, pair.second).forEachIndexed { series, value ->
                val x = center + if (series == 0) -d(12f) else d(12f)
                val h = ((bottom - top) * value / ceiling).toFloat()
                paint.color = if (series == 0) 0xFF427C61.toInt() else 0xFFC77A65.toInt()
                canvas.drawRoundRect(RectF(x - barWidth / 2, bottom - h, x + barWidth / 2, bottom), d(3f), d(3f), paint)
                paint.textSize = d(10f)
                paint.textAlign = if (series == 0) Paint.Align.RIGHT else Paint.Align.LEFT
                val label = String.format(Locale.CHINA, "%.2f", value)
                // Put labels on separate rows so adjacent income/expense figures stay legible.
                val labelY = bottom - h - d(if (series == 0) 18f else 5f)
                if (value > 0) canvas.drawText(label, center + if (series == 0) -d(2f) else d(2f), labelY, paint)
            }
            paint.color = 0xFF8B9188.toInt()
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = d(11f)
            canvas.drawText("${index + 1}日", center, height - d(8f), paint)
        }
        paint.textAlign = Paint.Align.LEFT
    }
}

/** First matching rule wins, so amounts are never double counted. */
object NoteClassifier {
    private data class Rule(val type: String, val category: String, val sub: String, val words: List<String>)
    private fun rule(type: String, category: String, sub: String, words: String) = Rule(type, category, sub, words.split('|'))
    private val rules = listOf(
        rule("支出", "餐饮", "外卖", "外卖|美团外卖|饿了么"),
        rule("支出", "餐饮", "早餐", "早餐|早饭|早点"),
        rule("支出", "餐饮", "午饭", "午饭|午餐|中饭"),
        rule("支出", "餐饮", "晚饭", "晚饭|晚餐"),
        rule("支出", "餐饮", "夜宵", "夜宵|宵夜"),
        rule("支出", "餐饮", "饮品", "奶茶|咖啡|饮料|果茶"),
        rule("支出", "餐饮", "水果零食", "水果|零食|饼干|蛋糕"),
        rule("支出", "餐饮", "食材买菜", "买菜|食材|蔬菜"),
        rule("支出", "餐饮", "聚餐", "聚餐|火锅|烧烤|请客吃饭"),
        rule("支出", "交通", "打车", "打车|出租车|滴滴|网约车"),
        rule("支出", "交通", "公交地铁", "公交|地铁"),
        rule("支出", "交通", "长途出行", "机票|火车票|高铁|动车"),
        rule("支出", "交通", "用车", "加油|停车|过路费|充电费"),
        rule("支出", "住房", "房租", "房租|租金"),
        rule("支出", "住房", "水电物业", "水费|电费|燃气|物业"),
        rule("支出", "购物", "服饰", "衣服|裤子|鞋子|服装"),
        rule("支出", "购物", "数码", "手机|电脑|耳机|键盘"),
        rule("支出", "日用", "生活用品", "纸巾|洗衣液|牙膏|洗发水"),
        rule("支出", "娱乐", "电影游戏", "电影|游戏|电玩"),
        rule("支出", "医疗", "就医买药", "挂号|买药|药费|诊疗|体检"),
        rule("支出", "学习", "书籍课程", "买书|书费|课程|学费|培训"),
        rule("收入", "工资", "基本工资", "工资|薪水|薪资|底薪"),
        rule("收入", "奖金", "年终奖", "年终奖"),
        rule("收入", "奖金", "绩效奖金", "奖金|绩效|提成"),
        rule("收入", "兼职", "兼职劳务", "兼职|劳务|稿费|家教|接单"),
        rule("收入", "理财", "利息分红", "利息|分红|股息|理财"),
        rule("收入", "红包", "红包礼金", "红包|礼金|压岁钱")
    )
    fun classify(type: String, category: String, note: String): Pair<String, String> {
        val match = rules.firstOrNull { it.type == type && it.words.any { word -> note.contains(word, ignoreCase = true) } }
        return if (match != null) match.category to match.sub
        else category.ifBlank { "其他" } to if (note.isBlank()) "无备注" else "其他备注"
    }
}

class LedgerPie(context: android.content.Context) : View(context) {
    companion object {
        val colors = intArrayOf(0xFF507D65.toInt(), 0xFFC77A65.toInt(), 0xFF668CAB.toInt(), 0xFFAD8A44.toInt(), 0xFF9275A8.toInt(), 0xFF3E9692.toInt(), 0xFFAF6687.toInt(), 0xFF7D8548.toInt(), 0xFF956C52.toInt())
    }
    var amounts: List<Long> = emptyList()
        set(value) { field = value; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val total = amounts.sum()
        if (total <= 0L) return
        val diameter = minOf(width, height) * .88f
        val bounds = RectF((width - diameter) / 2, (height - diameter) / 2, (width + diameter) / 2, (height + diameter) / 2)
        var start = -90f
        amounts.forEachIndexed { i, amount ->
            val sweep = if (i == amounts.lastIndex) 270f - start else (amount.toDouble() / total * 360).toFloat()
            paint.color = colors[i % colors.size]
            canvas.drawArc(bounds, start, sweep, true, paint)
            start += sweep
        }
    }
}
