package com.ssebanom.pythonide

import android.annotation.SuppressLint
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.google.android.material.appbar.MaterialToolbar
import org.json.JSONArray
import java.io.File
import java.util.concurrent.LinkedBlockingQueue

class MainActivity : AppCompatActivity() {

    companion object {
        private const val EOF_SENTINEL = "EOF"
        private const val PREFS = "pyide"
        private const val MAX_CONSOLE_CHARS = 200_000
    }

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var toolbar: MaterialToolbar
    private lateinit var editor: EditText
    private lateinit var editorScroll: ScrollView
    private lateinit var gutter: TextView
    private lateinit var consoleText: TextView
    private lateinit var consoleScroll: ScrollView
    private lateinit var consolePanel: LinearLayout
    private lateinit var inputRow: LinearLayout
    private lateinit var inputField: EditText
    private lateinit var fileListView: ListView
    private lateinit var problemsBar: LinearLayout
    private lateinit var problemsText: TextView

    private lateinit var highlighter: PythonHighlighter
    private val uiHandler = Handler(Looper.getMainLooper())

    private lateinit var scriptsDir: File
    private var currentFile: File? = null

    private val py: Python get() = Python.getInstance()
    private val runner: PyObject get() = py.getModule("ide_runner")

    private val pythonReady: Boolean get() = App.startupError == null

    @Volatile private var running = false
    private var runMenuItem: MenuItem? = null
    private var stopMenuItem: MenuItem? = null
    private val inputQueue = LinkedBlockingQueue<String>()

    private var lastLineCount = -1
    private var internalEdit = false
    private var pendingIndentPos = -1

    // Live error checking.
    private data class Problem(
        val line: Int, val col: Int, val endLine: Int, val endCol: Int,
        val message: String, val severity: String
    )

    private val problems = ArrayList<Problem>()
    private val problemSpans = ArrayList<WavyUnderlineSpan>()
    private var gutterLineCount = 1
    private var checkSeq = 0
    private var nextProblemIdx = 0
    private var errorColor = 0
    private var warnColor = 0
    private var okColor = 0
    private var wavyAmp = 0f
    private var wavyStroke = 0f
    private var wavyLen = 0f
    private var consoleCollapsed = false

    private val checkRunnable = Runnable { runCheck() }

    // Console output is buffered and flushed to the UI at ~30fps to keep
    // fast print loops from overwhelming the main thread.
    private val consoleBuffer = SpannableStringBuilder()
    private val consoleLock = Any()
    private var flushScheduled = false

    private var stdoutColor = 0
    private var stderrColor = 0
    private var inputColor = 0

    private val highlightRunnable = Runnable {
        highlighter.highlight(editor.text)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            buildUi()
        } catch (t: Throwable) {
            // Anything that goes wrong while building the screen is shown
            // rather than silently killing the app.
            startActivity(android.content.Intent(this, CrashActivity::class.java)
                .putExtra(CrashActivity.EXTRA_TRACE,
                    "MainActivity failed to start:\n" + App.stackToString(t)))
            finish()
        }
    }

    private fun appVersion(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

    private fun buildUi() {
        setContentView(R.layout.activity_main)

        drawerLayout = findViewById(R.id.drawerLayout)
        toolbar = findViewById(R.id.toolbar)
        editor = findViewById(R.id.editor)
        editorScroll = findViewById(R.id.editorScroll)
        gutter = findViewById(R.id.gutter)
        consoleText = findViewById(R.id.consoleText)
        consoleScroll = findViewById(R.id.consoleScroll)
        consolePanel = findViewById(R.id.consolePanel)
        inputRow = findViewById(R.id.inputRow)
        inputField = findViewById(R.id.inputField)
        fileListView = findViewById(R.id.fileList)
        problemsBar = findViewById(R.id.problemsBar)
        problemsText = findViewById(R.id.problemsText)

        setSupportActionBar(toolbar)

        // Keep the keyboard from resizing/scrolling the editor above it; the
        // input bar temporarily switches to adjustResize while a script waits
        // for input() so it stays reachable above the keyboard.
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)

        stdoutColor = ContextCompat.getColor(this, R.color.console_stdout)
        stderrColor = ContextCompat.getColor(this, R.color.console_stderr)
        inputColor = ContextCompat.getColor(this, R.color.console_input)
        errorColor = ContextCompat.getColor(this, R.color.problem_error)
        warnColor = ContextCompat.getColor(this, R.color.problem_warning)
        okColor = ContextCompat.getColor(this, R.color.problem_ok)

        val dm = resources.displayMetrics
        wavyAmp = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 2f, dm)
        wavyStroke = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1.3f, dm)
        wavyLen = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 5f, dm)

        problemsBar.setOnClickListener { jumpToProblem() }

        highlighter = PythonHighlighter(
            ContextCompat.getColor(this, R.color.syn_keyword),
            ContextCompat.getColor(this, R.color.syn_string),
            ContextCompat.getColor(this, R.color.syn_comment),
            ContextCompat.getColor(this, R.color.syn_number),
            ContextCompat.getColor(this, R.color.syn_builtin),
            ContextCompat.getColor(this, R.color.syn_decorator),
            ContextCompat.getColor(this, R.color.syn_self)
        )

        // scriptsDir must be initialised before setupDrawer(), which lists it.
        scriptsDir = File(filesDir, "scripts").apply { mkdirs() }
        Examples.installIfNeeded(this, scriptsDir)

        setupEditor()
        setupConsole()
        setupDrawer()

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        applyTextSize(prefs.getFloat("textSize", 14f))
        val lastName = prefs.getString("lastFile", "main.py")!!
        val toOpen = File(scriptsDir, lastName)
        openFile(if (toOpen.exists()) toOpen else pickAnyFile())

        supportActionBar?.subtitle = "${currentFile?.name}  ·  v${appVersion()}"

        if (pythonReady) {
            appendConsole(
                "Python IDE v${appVersion()}\n" +
                    runner.callAttr("python_version").toString() +
                    " on Android — ready.\n", stdoutColor
            )
        } else {
            reportStartupError()
        }
    }

    /** Shown when the embedded Python interpreter failed to load at startup. */
    private fun reportStartupError() {
        val err = App.startupError ?: "Unknown error"
        setConsoleCollapsed(false)
        appendConsole(
            "Python failed to start on this device.\n\n$err\n", stderrColor
        )
        AlertDialog.Builder(this)
            .setTitle("Python engine could not start")
            .setMessage(
                "The Python runtime failed to load, so scripts can't run.\n\n" +
                    "The details are shown in the console below. Please send " +
                    "them to the developer.\n\nFirst lines:\n" +
                    err.lineSequence().take(6).joinToString("\n")
            )
            .setPositiveButton("Copy details") { _, _ ->
                val cm = getSystemService(CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                cm.setPrimaryClip(
                    android.content.ClipData.newPlainText("crash", err)
                )
                toast("Copied to clipboard")
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun pickAnyFile(): File {
        val files = listScripts()
        if (files.isNotEmpty()) return files[0]
        val f = File(scriptsDir, "main.py")
        f.writeText("print(\"Hello from Python!\")\n")
        return f
    }

    // ------------------------------------------------------------ editor

    private fun setupEditor() {
        editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}

            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                if (!internalEdit && count == 1 && before == 0 && s[start] == '\n') {
                    pendingIndentPos = start
                }
            }

            override fun afterTextChanged(s: Editable) {
                if (internalEdit) return
                if (pendingIndentPos >= 0) {
                    val pos = pendingIndentPos
                    pendingIndentPos = -1
                    applyAutoIndent(s, pos)
                }
                updateGutter(s)
                uiHandler.removeCallbacks(highlightRunnable)
                uiHandler.postDelayed(highlightRunnable, 160)
                uiHandler.removeCallbacks(checkRunnable)
                uiHandler.postDelayed(checkRunnable, 500)
            }
        })
    }

    /** After the user types Enter at [newlinePos], copy the previous line's
     *  indentation, adding four spaces if that line opened a block. */
    private fun applyAutoIndent(s: Editable, newlinePos: Int) {
        val lineStart = s.substring(0, newlinePos).lastIndexOf('\n') + 1
        val prevLine = s.substring(lineStart, newlinePos)
        val indent = StringBuilder(prevLine.takeWhile { it == ' ' || it == '\t' })
        if (prevLine.trimEnd().endsWith(":")) indent.append("    ")
        if (indent.isNotEmpty()) {
            internalEdit = true
            s.insert(newlinePos + 1, indent)
            internalEdit = false
        }
    }

    private fun updateGutter(s: CharSequence) {
        var lines = 1
        for (ch in s) if (ch == '\n') lines++
        gutterLineCount = lines
        lastLineCount = lines
        renderGutter()
    }

    /** Rebuild the gutter, colouring line numbers that have problems. */
    private fun renderGutter() {
        val lines = gutterLineCount
        val severityByLine = HashMap<Int, String>()
        for (p in problems) {
            val existing = severityByLine[p.line]
            if (existing != "error") severityByLine[p.line] = p.severity
        }
        val sb = SpannableStringBuilder()
        for (i in 1..lines) {
            val start = sb.length
            sb.append(i.toString())
            severityByLine[i]?.let { sev ->
                val color = if (sev == "error") errorColor else warnColor
                sb.setSpan(ForegroundColorSpan(color), start, sb.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (i < lines) sb.append("\n")
        }
        gutter.text = sb
    }

    private fun applyTextSize(sp: Float) {
        editor.textSize = sp
        gutter.textSize = sp
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putFloat("textSize", sp).apply()
    }

    // -------------------------------------------------- live error checking

    private fun runCheck() {
        if (!pythonReady) return
        val file = currentFile
        if (file == null || !file.name.endsWith(".py")) {
            applyProblems(emptyList())
            return
        }
        val source = editor.text.toString()
        val seq = ++checkSeq
        Thread({
            val json = try {
                runner.callAttr("check_code", source).toString()
            } catch (e: Throwable) {
                "[]"
            }
            val parsed = parseProblems(json)
            runOnUiThread { if (seq == checkSeq) applyProblems(parsed) }
        }, "PyCheck").start()
    }

    private fun parseProblems(json: String): List<Problem> {
        val out = ArrayList<Problem>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    Problem(
                        o.optInt("line", 1), o.optInt("col", 1),
                        o.optInt("endLine", 1), o.optInt("endCol", 1),
                        o.optString("message", ""), o.optString("severity", "error")
                    )
                )
            }
        } catch (_: Throwable) {
        }
        return out
    }

    private fun applyProblems(list: List<Problem>) {
        val text = editor.text
        for (span in problemSpans) text.removeSpan(span)
        problemSpans.clear()
        problems.clear()
        problems.addAll(list)
        nextProblemIdx = 0

        for (p in list) {
            val (start, end) = lineCharRange(text, p.line)
            if (start in 0..text.length && end in start..text.length && end > start) {
                val color = if (p.severity == "error") errorColor else warnColor
                val span = WavyUnderlineSpan(color, wavyAmp, wavyStroke, wavyLen)
                text.setSpan(span, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                problemSpans.add(span)
            }
        }
        renderGutter()
        updateProblemsBar()
    }

    /** Character range covering 1-based [line] (excluding its newline). */
    private fun lineCharRange(text: CharSequence, line: Int): Pair<Int, Int> {
        var idx = 0
        var current = 1
        while (current < line && idx < text.length) {
            if (text[idx] == '\n') current++
            idx++
        }
        val start = idx
        var end = idx
        while (end < text.length && text[end] != '\n') end++
        return Pair(start, end)
    }

    private fun updateProblemsBar() {
        if (problems.isEmpty()) {
            problemsText.setTextColor(okColor)
            problemsText.text = "✓ No problems"
            return
        }
        val errors = problems.count { it.severity == "error" }
        val warnings = problems.size - errors
        val first = problems.minByOrNull { it.line }!!
        val counts = buildString {
            if (errors > 0) append("⛔ $errors")
            if (warnings > 0) {
                if (isNotEmpty()) append("   ")
                append("⚠ $warnings")
            }
        }
        problemsText.setTextColor(if (errors > 0) errorColor else warnColor)
        problemsText.text = "$counts    Line ${first.line}: ${first.message}"
    }

    private fun jumpToProblem() {
        if (problems.isEmpty()) return
        val ordered = problems.sortedBy { it.line }
        val p = ordered[nextProblemIdx % ordered.size]
        nextProblemIdx++
        val (start, _) = lineCharRange(editor.text, p.line)
        val pos = start.coerceIn(0, editor.text.length)
        editor.requestFocus()
        editor.setSelection(pos)
        editor.post {
            val layout = editor.layout ?: return@post
            val y = layout.getLineTop(layout.getLineForOffset(pos))
            editorScroll.smoothScrollTo(0, (y - 120).coerceAtLeast(0))
        }
    }

    // ------------------------------------------------------------ files

    private fun listScripts(): List<File> =
        scriptsDir.listFiles { f -> f.isFile }?.sortedBy { it.name.lowercase() }
            ?: emptyList()

    private fun refreshFileList() {
        val names = listScripts().map { it.name }
        fileListView.adapter = ArrayAdapter(
            this, android.R.layout.simple_list_item_1, names
        )
    }

    private fun setupDrawer() {
        refreshFileList()
        fileListView.setOnItemClickListener { _, _, pos, _ ->
            val name = fileListView.adapter.getItem(pos) as String
            saveCurrentFile()
            openFile(File(scriptsDir, name))
            drawerLayout.closeDrawer(GravityCompat.START)
        }
        fileListView.setOnItemLongClickListener { _, _, pos, _ ->
            val name = fileListView.adapter.getItem(pos) as String
            showFileOptions(File(scriptsDir, name))
            true
        }
        findViewById<View>(R.id.btnNewFile).setOnClickListener { promptNewFile() }
    }

    private fun openFile(file: File) {
        currentFile = file
        internalEdit = true
        editor.setText(if (file.exists()) file.readText() else "")
        internalEdit = false
        lastLineCount = -1
        // Clear stale markers from the previous file before re-checking.
        for (span in problemSpans) editor.text.removeSpan(span)
        problemSpans.clear()
        problems.clear()
        updateGutter(editor.text)
        highlighter.highlight(editor.text)
        updateProblemsBar()
        supportActionBar?.subtitle = "${file.name}  ·  v${appVersion()}"
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString("lastFile", file.name).apply()
        uiHandler.removeCallbacks(checkRunnable)
        uiHandler.postDelayed(checkRunnable, 300)
    }

    private fun saveCurrentFile() {
        currentFile?.writeText(editor.text.toString())
    }

    private fun promptNewFile() {
        val input = EditText(this).apply {
            hint = "script_name.py"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(this)
            .setTitle("New file")
            .setView(wrapWithMargin(input))
            .setPositiveButton("Create") { _, _ ->
                var name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                if (!name.endsWith(".py")) name += ".py"
                if (name.contains('/') || name.contains('\\')) {
                    toast("Invalid name"); return@setPositiveButton
                }
                saveCurrentFile()
                val f = File(scriptsDir, name)
                if (!f.exists()) f.writeText("")
                refreshFileList()
                openFile(f)
                drawerLayout.closeDrawer(GravityCompat.START)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showFileOptions(file: File) {
        AlertDialog.Builder(this)
            .setTitle(file.name)
            .setItems(arrayOf("Rename", "Delete")) { _, which ->
                when (which) {
                    0 -> promptRename(file)
                    1 -> confirmDelete(file)
                }
            }
            .show()
    }

    private fun promptRename(file: File) {
        val input = EditText(this).apply { setText(file.name) }
        AlertDialog.Builder(this)
            .setTitle("Rename")
            .setView(wrapWithMargin(input))
            .setPositiveButton("Rename") { _, _ ->
                var name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                if (!name.endsWith(".py")) name += ".py"
                val dst = File(scriptsDir, name)
                if (file.renameTo(dst)) {
                    if (currentFile == file) openFile(dst)
                    refreshFileList()
                } else toast("Rename failed")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDelete(file: File) {
        AlertDialog.Builder(this)
            .setTitle("Delete ${file.name}?")
            .setPositiveButton("Delete") { _, _ ->
                file.delete()
                if (currentFile == file) {
                    currentFile = null
                    openFile(pickAnyFile())
                }
                refreshFileList()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun wrapWithMargin(v: View): View {
        val wrap = LinearLayout(this)
        val pad = (16 * resources.displayMetrics.density).toInt()
        wrap.setPadding(pad, pad / 2, pad, 0)
        wrap.addView(
            v, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        return wrap
    }

    // ------------------------------------------------------------ console

    private fun setupConsole() {
        findViewById<View>(R.id.btnClearConsole).setOnClickListener {
            consoleText.text = ""
        }
        findViewById<View>(R.id.btnToggleConsole).setOnClickListener {
            setConsoleCollapsed(!consoleCollapsed)
        }
        // Restore the collapsed/expanded state from last session.
        setConsoleCollapsed(
            getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean("consoleCollapsed", false)
        )
        val send = {
            val line = inputField.text.toString()
            inputField.setText("")
            appendConsole(line + "\n", inputColor)
            inputQueue.offer(line)
        }
        findViewById<View>(R.id.btnSendInput).setOnClickListener { send() }
        inputField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                send(); true
            } else false
        }
    }

    /** Collapse the console to just its header bar, or expand it again. */
    private fun setConsoleCollapsed(collapsed: Boolean) {
        consoleCollapsed = collapsed
        consoleScroll.visibility = if (collapsed) View.GONE else View.VISIBLE
        if (collapsed) inputRow.visibility = View.GONE
        val btn = findViewById<android.widget.ImageButton>(R.id.btnToggleConsole)
        // The chevron points up to collapse, down to expand.
        btn.rotation = if (collapsed) 180f else 0f
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean("consoleCollapsed", collapsed).apply()
    }

    private fun appendConsole(text: String, color: Int) {
        val span = SpannableString(text)
        span.setSpan(
            ForegroundColorSpan(color), 0, text.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        synchronized(consoleLock) {
            consoleBuffer.append(span)
            if (flushScheduled) return
            flushScheduled = true
        }
        uiHandler.postDelayed({ flushConsole() }, 33)
    }

    private fun flushConsole() {
        val chunk: CharSequence
        synchronized(consoleLock) {
            chunk = SpannableStringBuilder(consoleBuffer)
            consoleBuffer.clear()
            flushScheduled = false
        }
        if (chunk.isEmpty()) return
        consoleText.append(chunk)
        val len = consoleText.text.length
        if (len > MAX_CONSOLE_CHARS) {
            consoleText.text = SpannableStringBuilder(
                consoleText.text, len - MAX_CONSOLE_CHARS / 2, len
            )
        }
        consoleScroll.post { consoleScroll.fullScroll(View.FOCUS_DOWN) }
    }

    // ------------------------------------------------------------ run/stop

    /** Called from the Python thread via Chaquopy. */
    inner class RunCallback {
        fun onOutput(text: String, isErr: Boolean) {
            appendConsole(text, if (isErr) stderrColor else stdoutColor)
        }

        fun onInputRequest(prompt: String): String? {
            runOnUiThread {
                if (consoleCollapsed) setConsoleCollapsed(false)
                if (prompt.isNotEmpty()) appendConsole(prompt, stdoutColor)
                // Let the window resize so the input bar rides above the keyboard.
                window.setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                inputRow.visibility = View.VISIBLE
                inputField.requestFocus()
            }
            val line = inputQueue.take()
            runOnUiThread {
                inputRow.visibility = View.GONE
                window.setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
            }
            return if (line == EOF_SENTINEL) null else line
        }

        fun onPlot(path: String) {
            runOnUiThread { showPlot(path) }
        }

        fun onFinished(ok: Boolean) {
            runOnUiThread {
                running = false
                runMenuItem?.isVisible = true
                stopMenuItem?.isVisible = false
                inputRow.visibility = View.GONE
                window.setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
                appendConsole(
                    if (ok) "\n[Finished]\n" else "\n[Finished with errors]\n",
                    if (ok) inputColor else stderrColor
                )
            }
        }
    }

    private fun runScript() {
        if (!pythonReady) { reportStartupError(); return }
        if (running) return
        val file = currentFile ?: return
        saveCurrentFile()
        running = true
        runMenuItem?.isVisible = false
        stopMenuItem?.isVisible = true
        setConsoleCollapsed(false)
        inputQueue.clear()
        appendConsole("\n▶ Running ${file.name}\n", inputColor)
        val callback = RunCallback()
        Thread({
            try {
                runner.callAttr("run_script", file.absolutePath, callback)
            } catch (e: Throwable) {
                runOnUiThread {
                    appendConsole("Internal error: $e\n", stderrColor)
                    callback.onFinished(false)
                }
            }
        }, "PyRun").start()
    }

    private fun stopScript() {
        if (!running) return
        runner.callAttr("stop_script")
        // If the script is blocked waiting for input(), unblock it with EOF.
        inputQueue.offer(EOF_SENTINEL)
    }

    private fun showPlot(path: String) {
        val bmp = BitmapFactory.decodeFile(path) ?: return
        val img = ImageView(this).apply {
            setImageBitmap(bmp)
            adjustViewBounds = true
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        AlertDialog.Builder(this)
            .setTitle("Plot")
            .setView(img)
            .setPositiveButton("Close", null)
            .show()
    }

    // ------------------------------------------------------------ pip

    // A curated catalogue of popular, pure-Python packages that install
    // reliably on-device (label to pip name). Native packages are pre-bundled.
    private val libraryCatalog = listOf(
        "rich — pretty terminal output" to "rich",
        "requests — HTTP client" to "requests",
        "beautifulsoup4 — HTML/XML parsing" to "beautifulsoup4",
        "sympy — symbolic mathematics" to "sympy",
        "PyYAML — YAML files" to "pyyaml",
        "python-dateutil — date utilities" to "python-dateutil",
        "tabulate — pretty tables" to "tabulate",
        "colorama — coloured text" to "colorama",
        "tqdm — progress bars" to "tqdm",
        "more-itertools — iterator helpers" to "more-itertools",
        "click — build CLIs" to "click",
        "Jinja2 — templating" to "jinja2",
        "Faker — fake data" to "faker",
        "emoji — emoji support" to "emoji",
        "humanize — human-readable values" to "humanize",
        "pyfiglet — ASCII-art text" to "pyfiglet",
        "wikipedia — Wikipedia API" to "wikipedia",
        "httpx — modern HTTP client" to "httpx"
    )

    private fun showLibrariesDialog() {
        if (!pythonReady) { reportStartupError(); return }
        // Discover what's already installed so those entries can be pre-ticked.
        Thread({
            val installed = HashSet<String>()
            try {
                val arr = JSONArray(runner.callAttr("list_packages").toString())
                for (i in 0 until arr.length()) {
                    installed.add(arr.getJSONObject(i).getString("name")
                        .lowercase().replace("_", "-"))
                }
            } catch (_: Throwable) {
            }
            runOnUiThread { buildLibrariesDialog(installed) }
        }, "LibList").start()
    }

    private fun buildLibrariesDialog(installed: Set<String>) {
        val labels = libraryCatalog.map { (label, pip) ->
            if (installed.contains(pip.lowercase())) "✓ $label" else label
        }.toTypedArray()
        val checked = BooleanArray(libraryCatalog.size) { i ->
            installed.contains(libraryCatalog[i].second.lowercase())
        }
        val preinstalled = checked.copyOf()

        AlertDialog.Builder(this)
            .setTitle("Install libraries")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("Install selected") { _, _ ->
                val specs = libraryCatalog.filterIndexed { i, _ ->
                    checked[i] && !preinstalled[i]
                }.map { it.second }
                if (specs.isEmpty()) {
                    toast("Nothing new selected")
                } else {
                    pipInstall(specs.joinToString(" "))
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    @SuppressLint("SetTextI18n")
    private fun showPipDialog() {
        if (!pythonReady) { reportStartupError(); return }
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val pkgInput = EditText(this).apply {
            hint = "package name, e.g. rich"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        row.addView(pkgInput, LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row)
        val note = TextView(this).apply {
            text = "Pure-Python packages install directly on the device. " +
                "numpy, pandas, matplotlib, pillow and requests are pre-installed. " +
                "Long-press a package you installed to uninstall it."
            textSize = 12f
        }
        root.addView(note)
        val listView = ListView(this)
        root.addView(listView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            (280 * density).toInt()
        ))

        val dialog = AlertDialog.Builder(this)
            .setTitle("Pip packages")
            .setView(root)
            .setPositiveButton("Install", null)
            .setNegativeButton("Close", null)
            .create()

        fun reload() {
            Thread {
                try {
                    val json = runner.callAttr("list_packages").toString()
                    val arr = JSONArray(json)
                    val items = ArrayList<String>()
                    val userFlags = ArrayList<Boolean>()
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val user = o.optBoolean("user")
                        items.add(
                            o.getString("name") + "  " + o.getString("version") +
                                if (user) "   (installed by you)" else ""
                        )
                        userFlags.add(user)
                    }
                    runOnUiThread {
                        listView.adapter = ArrayAdapter(
                            this, android.R.layout.simple_list_item_1, items
                        )
                        listView.setOnItemLongClickListener { _, _, pos, _ ->
                            if (userFlags[pos]) {
                                val name = items[pos].split("  ")[0]
                                AlertDialog.Builder(this)
                                    .setTitle("Uninstall $name?")
                                    .setPositiveButton("Uninstall") { _, _ ->
                                        Thread {
                                            runner.callAttr("uninstall_package", name)
                                            runOnUiThread {
                                                toast("$name removed")
                                            }
                                            dialog.dismiss()
                                        }.start()
                                    }
                                    .setNegativeButton("Cancel", null)
                                    .show()
                            }
                            true
                        }
                    }
                } catch (e: Throwable) {
                    runOnUiThread { toast("Failed to list packages: $e") }
                }
            }.start()
        }
        reload()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val spec = pkgInput.text.toString().trim()
                if (spec.isEmpty()) return@setOnClickListener
                dialog.dismiss()
                pipInstall(spec)
            }
        }
        dialog.show()
    }

    private fun pipInstall(spec: String) {
        setConsoleCollapsed(false)
        appendConsole("\n▶ pip install $spec\n", inputColor)
        val callback = RunCallback()
        Thread({
            try {
                val rc = runner.callAttr("pip_install", spec, callback).toInt()
                runOnUiThread {
                    appendConsole(
                        if (rc == 0) "\n[pip finished OK]\n"
                        else "\n[pip failed with code $rc]\n",
                        if (rc == 0) inputColor else stderrColor
                    )
                }
            } catch (e: Throwable) {
                runOnUiThread { appendConsole("pip error: $e\n", stderrColor) }
            }
        }, "PipInstall").start()
    }

    // ------------------------------------------------------------ menu

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        runMenuItem = menu.findItem(R.id.action_run)
        stopMenuItem = menu.findItem(R.id.action_stop)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_run -> runScript()
            R.id.action_stop -> stopScript()
            R.id.action_files -> drawerLayout.openDrawer(GravityCompat.START)
            R.id.action_save -> { saveCurrentFile(); toast("Saved") }
            R.id.action_new -> promptNewFile()
            R.id.action_libraries -> showLibrariesDialog()
            R.id.action_pip -> showPipDialog()
            R.id.action_text_larger -> applyTextSize(
                (editor.textSize / resources.displayMetrics.scaledDensity) + 1f)
            R.id.action_text_smaller -> applyTextSize(
                ((editor.textSize / resources.displayMetrics.scaledDensity) - 1f)
                    .coerceAtLeast(8f))
            R.id.action_about -> showAbout()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun showAbout() {
        val pyVersion =
            if (pythonReady) runner.callAttr("python_version").toString()
            else "Python unavailable (engine failed to start)"
        AlertDialog.Builder(this)
            .setTitle("Python IDE")
            .setMessage(
                "$pyVersion (Chaquopy)\n\n" +
                    "Pre-installed: numpy, pandas, matplotlib, pillow, requests\n\n" +
                    "• Run scripts with live output and input()\n" +
                    "• matplotlib plots pop up automatically\n" +
                    "• Install pure-Python packages with pip on device\n" +
                    "• Files are stored in the app's private storage"
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onPause() {
        super.onPause()
        saveCurrentFile()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START)
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }
}
