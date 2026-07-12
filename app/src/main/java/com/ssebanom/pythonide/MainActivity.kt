package com.ssebanom.pythonide

import android.annotation.SuppressLint
import android.graphics.BitmapFactory
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
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
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
    private lateinit var gutter: TextView
    private lateinit var consoleText: TextView
    private lateinit var consoleScroll: ScrollView
    private lateinit var inputRow: LinearLayout
    private lateinit var inputField: EditText
    private lateinit var fileListView: ListView

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
        gutter = findViewById(R.id.gutter)
        consoleText = findViewById(R.id.consoleText)
        consoleScroll = findViewById(R.id.consoleScroll)
        inputRow = findViewById(R.id.inputRow)
        inputField = findViewById(R.id.inputField)
        fileListView = findViewById(R.id.fileList)

        setSupportActionBar(toolbar)

        stdoutColor = ContextCompat.getColor(this, R.color.console_stdout)
        stderrColor = ContextCompat.getColor(this, R.color.console_stderr)
        inputColor = ContextCompat.getColor(this, R.color.console_input)

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
        consoleScroll.visibility = View.VISIBLE
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
        if (lines != lastLineCount) {
            lastLineCount = lines
            gutter.text = (1..lines).joinToString("\n")
        }
    }

    private fun applyTextSize(sp: Float) {
        editor.textSize = sp
        gutter.textSize = sp
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putFloat("textSize", sp).apply()
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
        updateGutter(editor.text)
        highlighter.highlight(editor.text)
        supportActionBar?.subtitle = file.name
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString("lastFile", file.name).apply()
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
            consoleScroll.visibility =
                if (consoleScroll.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
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
                if (prompt.isNotEmpty()) appendConsole(prompt, stdoutColor)
                inputRow.visibility = View.VISIBLE
                inputField.requestFocus()
            }
            val line = inputQueue.take()
            runOnUiThread { inputRow.visibility = View.GONE }
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
        consoleScroll.visibility = View.VISIBLE
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
        consoleScroll.visibility = View.VISIBLE
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
