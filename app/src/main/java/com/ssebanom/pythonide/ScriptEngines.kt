package com.ssebanom.pythonide

import org.json.JSONArray
import org.json.JSONObject
import org.luaj.vm2.LuaError
import org.luaj.vm2.lib.jse.JsePlatform
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.RhinoException
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import java.io.OutputStream
import java.io.PrintStream

/**
 * Runs and syntax-checks JavaScript (Mozilla Rhino) and Lua (LuaJ). Both are
 * pure-JVM interpreters, so they execute directly on the device. Rhino runs in
 * interpreted mode (optimization level -1) because Android cannot load the JVM
 * bytecode its compiler would otherwise generate.
 */
object ScriptEngines {

    /** Bridge exposed to JS as `__out` so print/console can reach the console. */
    class OutSink(private val out: (String, Boolean) -> Unit) {
        fun write(s: String, err: Boolean) = out(s, err)
    }

    private const val JS_BOOTSTRAP = """
        var console = {
            log:   function() { __out.write(Array.prototype.slice.call(arguments).join(' ') + '\n', false); },
            info:  function() { __out.write(Array.prototype.slice.call(arguments).join(' ') + '\n', false); },
            warn:  function() { __out.write(Array.prototype.slice.call(arguments).join(' ') + '\n', true); },
            error: function() { __out.write(Array.prototype.slice.call(arguments).join(' ') + '\n', true); },
            debug: function() { __out.write(Array.prototype.slice.call(arguments).join(' ') + '\n', false); }
        };
        function print() { __out.write(Array.prototype.slice.call(arguments).join(' ') + '\n', false); }
    """

    // ---------------------------------------------------------- JavaScript

    fun runJavaScript(
        source: String, filename: String,
        out: (String, Boolean) -> Unit, cancel: () -> Boolean
    ) {
        val factory = object : ContextFactory() {
            override fun observeInstructionCount(cx: Context, count: Int) {
                if (cancel()) throw StopException()
            }
        }
        val cx = factory.enterContext()
        try {
            cx.setOptimizationLevel(-1)
            cx.setLanguageVersion(Context.VERSION_ES6)
            cx.setInstructionObserverThreshold(10000)
            val scope: Scriptable = cx.initStandardObjects()
            ScriptableObject.putProperty(
                scope, "__out", Context.javaToJS(OutSink(out), scope))
            cx.evaluateString(scope, JS_BOOTSTRAP, "<bootstrap>", 1, null)
            val result = cx.evaluateString(scope, source, filename, 1, null)
            if (result != null && result !is org.mozilla.javascript.Undefined) {
                val s = Context.toString(result)
                if (s.isNotEmpty() && s != "undefined") out("$s\n", false)
            }
        } catch (e: StopException) {
            out("\n[Stopped by user]\n", true)
        } catch (e: RhinoException) {
            out("${e.details()}\n  at line ${e.lineNumber()}\n", true)
        } catch (e: Throwable) {
            out("${e.javaClass.simpleName}: ${e.message}\n", true)
        } finally {
            Context.exit()
        }
    }

    fun checkJavaScript(source: String): String {
        val factory = ContextFactory()
        val cx = factory.enterContext()
        return try {
            cx.setOptimizationLevel(-1)
            cx.setLanguageVersion(Context.VERSION_ES6)
            cx.compileString(source, "<editor>", 1, null)
            "[]"
        } catch (e: RhinoException) {
            problemJson(e.lineNumber(), maxOf(1, e.columnNumber()), e.details(), "error")
        } catch (e: Throwable) {
            "[]"
        } finally {
            Context.exit()
        }
    }

    // ----------------------------------------------------------------- Lua

    fun runLua(
        source: String, filename: String,
        out: (String, Boolean) -> Unit, cancel: () -> Boolean
    ) {
        val globals = JsePlatform.standardGlobals()
        globals.STDOUT = PrintStream(ForwardingStream { out(it, false) }, true)
        globals.STDERR = PrintStream(ForwardingStream { out(it, true) }, true)
        try {
            val chunk = globals.load(source, "editor")
            chunk.call()
        } catch (e: LuaError) {
            out("${e.message}\n", true)
        } catch (e: Throwable) {
            out("${e.javaClass.simpleName}: ${e.message}\n", true)
        }
    }

    fun checkLua(source: String): String {
        return try {
            val globals = JsePlatform.standardGlobals()
            globals.load(source, "editor")
            "[]"
        } catch (e: LuaError) {
            val (line, msg) = parseLuaError(e.message ?: "Syntax error")
            problemJson(line, 1, msg, "error")
        } catch (e: Throwable) {
            "[]"
        }
    }

    // ------------------------------------------------------------- helpers

    private class StopException : RuntimeException()

    /** OutputStream that decodes UTF-8 bytes and forwards them as strings. */
    private class ForwardingStream(val sink: (String) -> Unit) : OutputStream() {
        override fun write(b: Int) = sink(String(byteArrayOf(b.toByte())))
        override fun write(b: ByteArray, off: Int, len: Int) =
            sink(String(b, off, len, Charsets.UTF_8))
    }

    private val LUA_LOC = Regex("""editor:(\d+):\s*(.*)""", RegexOption.DOT_MATCHES_ALL)

    private fun parseLuaError(message: String): Pair<Int, String> {
        val m = LUA_LOC.find(message)
        return if (m != null) {
            (m.groupValues[1].toIntOrNull() ?: 1) to m.groupValues[2].trim()
        } else 1 to message
    }

    private fun problemJson(line: Int, col: Int, message: String, severity: String): String {
        val o = JSONObject()
            .put("line", if (line < 1) 1 else line)
            .put("col", col)
            .put("endLine", if (line < 1) 1 else line)
            .put("endCol", col + 1)
            .put("message", message)
            .put("severity", severity)
        return JSONArray().put(o).toString()
    }
}
