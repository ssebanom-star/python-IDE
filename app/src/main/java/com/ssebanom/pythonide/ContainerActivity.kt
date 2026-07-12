package com.ssebanom.pythonide

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/**
 * Terminal front-end for the proot Linux container. Lets the user set up an
 * Alpine rootfs, install language toolchains with `apk add`, run arbitrary
 * shell commands, and run the current editor file inside the container.
 */
class ContainerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FILE = "file_name"
    }

    private lateinit var output: TextView
    private lateinit var outputScroll: ScrollView
    private lateinit var input: EditText
    private lateinit var scriptsDir: File
    private var currentFileName: String? = null

    @Volatile private var busy = false
    @Volatile private var cancelRequested = false

    private val bg = Color.parseColor("#121317")
    private val fg = Color.parseColor("#D8DBE0")
    private val accent = Color.parseColor("#4FC3F7")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Linux container (experimental)"
        scriptsDir = File(filesDir, "scripts").apply { mkdirs() }
        currentFileName = intent.getStringExtra(EXTRA_FILE)

        val density = resources.displayMetrics.density
        val pad = (10 * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }

        // Quick-action buttons.
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(pad, pad, pad, 0)
        }
        fun addAction(label: String, run: () -> Unit) {
            actions.addView(Button(this).apply {
                text = label
                isAllCaps = false
                setOnClickListener { run() }
            })
        }
        addAction("Set up / Reset") { confirmSetup() }
        addAction("🖥 Install Desktop") { installDesktop() }
        addAction("🖥 Start Desktop") { startDesktop() }
        addAction("🖥 Open Desktop") {
            startActivity(android.content.Intent(this, DesktopActivity::class.java))
        }
        addAction("🖥 Stop Desktop") {
            ContainerManager.stopDesktop()
            append("\n[desktop stop requested]\n")
        }
        addAction("apk update") { runInContainer("apk update") }
        addAction("Node.js") { installPkg("nodejs npm") }
        addAction("Python3") { installPkg("python3 py3-pip") }
        addAction("Ruby") { installPkg("ruby") }
        addAction("Go") { installPkg("go") }
        addAction("PHP") { installPkg("php") }
        addAction("Rust") { installPkg("rust cargo") }
        addAction("C/C++") { installPkg("build-base") }
        addAction("Perl") { installPkg("perl") }
        currentFileName?.let { name ->
            addAction("▶ Run $name") { runCurrentFile(name) }
        }
        root.addView(HorizontalScrollView(this).apply { addView(actions) })

        // Output.
        output = TextView(this).apply {
            setTextColor(fg)
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(pad, pad, pad, pad)
        }
        outputScroll = ScrollView(this).apply { addView(output) }
        root.addView(outputScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // Command input.
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#1B1D22"))
            setPadding(pad, 0, pad, 0)
        }
        val prompt = TextView(this).apply {
            text = "$ "
            setTextColor(accent)
            typeface = Typeface.MONOSPACE
        }
        input = EditText(this).apply {
            hint = "shell command (e.g. node -v)"
            setHintTextColor(Color.parseColor("#5A5D63"))
            setTextColor(fg)
            typeface = Typeface.MONOSPACE
            background = null
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, id, _ ->
                if (id == EditorInfo.IME_ACTION_SEND) { sendCommand(); true } else false
            }
        }
        val send = Button(this).apply {
            text = "Run"; isAllCaps = false
            setOnClickListener { sendCommand() }
        }
        val cancel = Button(this).apply {
            text = "Stop"; isAllCaps = false
            setOnClickListener { cancelRequested = true }
        }
        inputRow.addView(prompt)
        inputRow.addView(input, LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        inputRow.addView(send)
        inputRow.addView(cancel)
        root.addView(inputRow)

        setContentView(root)

        if (!ContainerManager.isInstalled(this)) {
            append(
                "Welcome to the experimental Linux container.\n\n" +
                    "This runs a real Alpine Linux via proot, so you can install " +
                    "language toolchains that Android can't otherwise run:\n" +
                    "  apk add nodejs • ruby • go • php • rust • python3 …\n\n" +
                    "Tap \"Set up / Reset\" to unpack it (about 5 MB, one time).\n"
            )
        } else {
            append(
                "Container ready. Try: node -v   or   apk add nodejs && node -v\n" +
                    "For a full Linux desktop: 🖥 Install Desktop → 🖥 Start Desktop.\n"
            )
        }
    }

    private fun confirmSetup() {
        if (busy) { append("\n[busy — wait for the current command]\n"); return }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Set up Linux container")
            .setMessage(
                "Unpack the Alpine Linux rootfs into app storage. Existing " +
                    "container packages will be reset. Continue?"
            )
            .setPositiveButton("Set up") { _, _ -> doSetup() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun doSetup() {
        if (busy) return
        busy = true
        append("\n=== Setting up container ===\n")
        Thread({
            val ok = ContainerManager.setup(this) { append(it) }
            runOnUiThread {
                busy = false
                append(if (ok) "\n✓ Done.\n" else "\n✗ Setup failed.\n")
            }
        }, "ContainerSetup").start()
    }

    private fun installPkg(pkgs: String) {
        runInContainer("apk add $pkgs && echo '--- installed: $pkgs ---'")
    }

    // ------------------------------------------------------ desktop (GUI)

    private fun installDesktop() {
        if (ContainerManager.desktopInstalled(this)) {
            append("\nDesktop is already installed — tap \"Start Desktop\".\n")
            return
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Install Linux desktop (XFCE)")
            .setMessage(
                "Downloads the XFCE desktop, VNC server and noVNC viewer " +
                    "into the container (roughly 200–300 MB). Continue?"
            )
            .setPositiveButton("Install") { _, _ ->
                runInContainer(
                    "apk update && apk add ${ContainerManager.DESKTOP_PACKAGES} " +
                        "&& echo '--- desktop installed: tap Start Desktop ---'"
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startDesktop() {
        if (!ContainerManager.isInstalled(this)) {
            append("\nContainer isn't set up yet — tap \"Set up / Reset\" first.\n")
            return
        }
        if (!ContainerManager.desktopInstalled(this)) {
            append("\nDesktop isn't installed yet — tap \"Install Desktop\" first.\n")
            return
        }
        if (ContainerManager.desktopRunning()) {
            append("\nDesktop already running — tap \"Open Desktop\".\n")
            startActivity(android.content.Intent(this, DesktopActivity::class.java))
            return
        }
        // Render the desktop at half the physical resolution (landscape),
        // capped for VNC performance; even numbers keep encoders happy.
        val dm = resources.displayMetrics
        var w = maxOf(dm.widthPixels, dm.heightPixels)
        var h = minOf(dm.widthPixels, dm.heightPixels)
        val cap = 1600
        if (w > cap) { h = h * cap / w; w = cap }
        w = w and 1.inv(); h = h and 1.inv()  // force even dimensions
        append("\n=== Starting desktop (${w}x${h}) — first start takes ~20–30 s ===\n")
        val ok = ContainerManager.startDesktop(this, scriptsDir, w, h) { append(it) }
        if (ok) {
            append("Opening desktop view in 10 s… (or tap \"Open Desktop\")\n")
            output.postDelayed({
                if (!isFinishing && ContainerManager.desktopRunning()) {
                    startActivity(
                        android.content.Intent(this, DesktopActivity::class.java))
                }
            }, 10_000)
        }
    }

    private fun runCurrentFile(name: String) {
        val interp = when {
            name.endsWith(".js") -> "node"
            name.endsWith(".py") -> "python3"
            name.endsWith(".rb") -> "ruby"
            name.endsWith(".go") -> "go run"
            name.endsWith(".php") -> "php"
            name.endsWith(".pl") -> "perl"
            name.endsWith(".lua") -> "lua5.4"
            name.endsWith(".sh") -> "sh"
            else -> "sh"
        }
        runInContainer("cd /root/scripts && $interp '$name'")
    }

    private fun sendCommand() {
        val cmd = input.text.toString().trim()
        if (cmd.isEmpty()) return
        input.setText("")
        runInContainer(cmd)
    }

    private fun runInContainer(command: String) {
        if (busy) { append("\n[busy — wait for the current command]\n"); return }
        if (!ContainerManager.isInstalled(this)) {
            append("\nContainer isn't set up yet — tap \"Set up / Reset\" first.\n")
            return
        }
        busy = true
        cancelRequested = false
        append("\n$ $command\n")
        Thread({
            val rc = ContainerManager.run(
                this, command, scriptsDir, { append(it) }, { cancelRequested })
            runOnUiThread {
                busy = false
                append("\n[exit $rc]\n")
            }
        }, "ContainerRun").start()
    }

    private fun append(text: String) {
        runOnUiThread {
            output.append(text)
            outputScroll.post { outputScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }
}
