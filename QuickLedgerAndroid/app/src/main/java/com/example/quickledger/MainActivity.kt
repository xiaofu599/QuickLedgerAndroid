package com.example.quickledger

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
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
        header.addView(text("✦", 28, purple, true))
        content.addView(header)
        content.addView(space(20))

        summaryBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(summaryBox)
        content.addView(space(12))
        content.addView(sectionTitle("本月支出趋势"))
        chart = SpendChart(this)
        val chartCard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(10), dp(8), dp(4)); background = rounded(white, 22) }
        chartCard.addView(chart, LinearLayout.LayoutParams(-1, dp(150)))
        content.addView(chartCard)
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
            detailRow.addView(card, lp)
        }
        summaryBox.addView(detailRow)
        chart.setValues((1..Calendar.getInstance().get(Calendar.DAY_OF_MONTH)).map { day ->
            val date = monthNow() + "-" + String.format(Locale.US, "%02d", day)
            entries.filter { it.type == "支出" && it.date == date }.sumOf { it.amount }.toFloat() / 100f
        })
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
        fun updateCats() { category.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, cats[type]!!) }
        typeSpinner.onItemSelectedListener = simpleListener { pos -> type = if (pos == 0) "支出" else "收入"; updateCats() }
        val date = EditText(this).apply { setText(existing?.date ?: today()); hint = "日期 YYYY-MM-DD"; isSingleLine = true }
        val note = EditText(this).apply {
            hint = "备注（选填）"; isSingleLine = true
            filters = arrayOf(android.text.InputFilter.LengthFilter(100))
            setText(existing?.note.orEmpty())
        }
        layout.addView(labelled("类型", typeSpinner)); layout.addView(labelled("金额", amount))
        layout.addView(labelled("分类", category)); layout.addView(labelled("日期", date)); layout.addView(labelled("备注", note))
        updateCats()
        existing?.let { old ->
            val options = cats[type].orEmpty()
            val selected = options.indexOf(old.category)
            if (selected >= 0) category.setSelection(selected)
        }
        AlertDialog.Builder(this).setTitle(if (existing == null) "记一笔" else "编辑账单").setView(layout).setNegativeButton("取消", null)
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
    private var values: List<Float> = emptyList()
    private val density = resources.displayMetrics.density
    fun setValues(v: List<Float>) { values = v; invalidate() }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = 12f*density; val right = width - 8f*density
        val top = 12f*density; val bottom = height - 24f*density
        paint.color = 0xFFE8EAE2.toInt(); paint.strokeWidth = density
        for (i in 0..3) {
            val y = top + (bottom-top)*i/3f
            canvas.drawLine(left, y, right, y, paint)
        }
        if (values.isEmpty()) return
        val max = (values.maxOrNull() ?: 0f).coerceAtLeast(1f)
        val slot = (right-left)/values.size
        val barWidth = (slot*.58f).coerceAtLeast(1f)
        values.forEachIndexed { index, value ->
            val h = (bottom-top) * value / max
            paint.color = 0xFF91B49C.toInt()
            val x = left + index*slot + (slot-barWidth)/2
            canvas.drawRoundRect(RectF(x, bottom-h, x+barWidth, bottom), 3f*density, 3f*density, paint)
        }
        paint.color = 0xFF8B9188.toInt(); paint.textSize = 10f*density
        canvas.drawText("1", left, height-5f*density, paint)
        canvas.drawText(values.size.toString(), right-8f*density, height-5f*density, paint)
    }
}
