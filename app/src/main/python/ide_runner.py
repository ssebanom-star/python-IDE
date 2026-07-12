"""Backend for the Python IDE app.

Runs user scripts with live stdout/stderr streaming, interactive input(),
matplotlib figure capture, KeyboardInterrupt-based stopping, and an
on-device pip that installs pure-Python packages into a writable
site-packages directory.
"""

import builtins
import ctypes
import importlib
import io
import json
import os
import shutil
import sys
import threading
import traceback

SITE_DIR = None
CACHE_DIR = None
PLOTS_DIR = None

_run_lock = threading.Lock()
_run_tid = None


def init(site_dir, cache_dir):
    """Called once at app startup."""
    global SITE_DIR, CACHE_DIR, PLOTS_DIR
    SITE_DIR = site_dir
    CACHE_DIR = cache_dir
    PLOTS_DIR = os.path.join(cache_dir, "plots")
    for d in (SITE_DIR, PLOTS_DIR):
        os.makedirs(d, exist_ok=True)
    if SITE_DIR not in sys.path:
        sys.path.insert(0, SITE_DIR)
    os.environ.setdefault("TMPDIR", cache_dir)
    os.environ["MPLBACKEND"] = "Agg"
    os.environ["MPLCONFIGDIR"] = os.path.join(cache_dir, "mpl")
    os.environ["HOME"] = cache_dir


class _CallbackWriter(io.TextIOBase):
    def __init__(self, callback, is_err):
        self.callback = callback
        self.is_err = is_err

    def writable(self):
        return True

    def write(self, s):
        s = str(s)
        if s:
            self.callback.onOutput(s, self.is_err)
        return len(s)

    def flush(self):
        pass


class _CallbackStdin(io.TextIOBase):
    def __init__(self, callback):
        self.callback = callback

    def readable(self):
        return True

    def readline(self, *args):
        line = self.callback.onInputRequest("")
        return ("" if line is None else line) + "\n"

    def read(self, *args):
        return self.readline()


def _patch_matplotlib(callback):
    """Make plt.show() render figures into PNGs and hand them to the app."""
    try:
        import matplotlib
        matplotlib.use("Agg", force=True)
        import matplotlib.pyplot as plt
    except Exception:
        return None

    orig_show = plt.show

    def show(*args, **kwargs):
        import matplotlib.pyplot as plt
        for num in plt.get_fignums():
            fig = plt.figure(num)
            path = os.path.join(PLOTS_DIR, "figure_%d.png" % num)
            fig.savefig(path, dpi=160, bbox_inches="tight",
                        facecolor=fig.get_facecolor())
            callback.onPlot(path)
        plt.close("all")

    plt.show = show
    return (plt, orig_show)


def run_script(path, callback):
    """Execute the file at `path` as __main__. Blocks until it finishes."""
    global _run_tid
    if not _run_lock.acquire(blocking=False):
        callback.onOutput("A script is already running.\n", True)
        callback.onFinished(False)
        return

    _run_tid = threading.get_ident()
    old_stdout, old_stderr, old_stdin = sys.stdout, sys.stderr, sys.stdin
    old_input = builtins.input
    old_cwd = os.getcwd()
    old_argv = sys.argv
    mpl_state = None
    ok = False
    try:
        sys.stdout = _CallbackWriter(callback, False)
        sys.stderr = _CallbackWriter(callback, True)
        sys.stdin = _CallbackStdin(callback)

        def patched_input(prompt=""):
            line = callback.onInputRequest(str(prompt))
            if line is None:
                raise EOFError("EOF when reading a line")
            return str(line)

        builtins.input = patched_input
        mpl_state = _patch_matplotlib(callback)

        script_dir = os.path.dirname(path)
        os.chdir(script_dir)
        if script_dir not in sys.path:
            sys.path.insert(0, script_dir)
        sys.argv = [path]

        with open(path, "r", encoding="utf-8") as f:
            source = f.read()

        code = compile(source, path, "exec")
        g = {
            "__name__": "__main__",
            "__file__": path,
            "__builtins__": builtins,
        }
        exec(code, g)

        # Flush any figures the script created but never show()-ed.
        if mpl_state is not None:
            try:
                import matplotlib.pyplot as plt
                if plt.get_fignums():
                    plt.show()
            except Exception:
                pass
        ok = True
    except KeyboardInterrupt:
        callback.onOutput("\n[Stopped by user]\n", True)
    except SystemExit as e:
        ok = e.code in (None, 0)
        if not ok:
            callback.onOutput("\n[Exited with code %s]\n" % e.code, True)
        else:
            ok = True
    except BaseException:
        etype, value, tb = sys.exc_info()
        # Hide the runner's own frame from the traceback.
        frames = traceback.extract_tb(tb)[1:]
        msg = "Traceback (most recent call last):\n" + \
              "".join(traceback.format_list(frames)) + \
              "".join(traceback.format_exception_only(etype, value))
        callback.onOutput(msg, True)
    finally:
        _run_tid = None
        if mpl_state is not None:
            try:
                mpl_state[0].show = mpl_state[1]
            except Exception:
                pass
        sys.stdout, sys.stderr, sys.stdin = old_stdout, old_stderr, old_stdin
        builtins.input = old_input
        sys.argv = old_argv
        try:
            os.chdir(old_cwd)
        except Exception:
            pass
        _run_lock.release()
        callback.onFinished(ok)


def stop_script():
    """Raise KeyboardInterrupt inside the running script's thread."""
    tid = _run_tid
    if tid is None:
        return False
    res = ctypes.pythonapi.PyThreadState_SetAsyncExc(
        ctypes.c_ulong(tid), ctypes.py_object(KeyboardInterrupt))
    return res == 1


# ---------------------------------------------------------------- pip ----

def pip_install(spec, callback):
    """Install package(s) into the app's writable site-packages."""
    old_stdout, old_stderr = sys.stdout, sys.stderr
    try:
        sys.stdout = _CallbackWriter(callback, False)
        sys.stderr = _CallbackWriter(callback, True)
        from pip._internal.cli.main import main as pip_main
        args = ["install", "--target", SITE_DIR, "--upgrade",
                "--prefer-binary", "--no-cache-dir",
                "--disable-pip-version-check"] + spec.split()
        rc = pip_main(args)
        importlib.invalidate_caches()
        return int(rc)
    except SystemExit as e:
        return int(e.code or 0)
    except Exception:
        traceback.print_exc()
        return 1
    finally:
        sys.stdout, sys.stderr = old_stdout, old_stderr


def list_packages():
    """JSON list of installed distributions, flagging user-installed ones."""
    import importlib.metadata as md
    pkgs = {}
    for dist in md.distributions():
        try:
            name = dist.metadata["Name"]
            if not name:
                continue
            location = str(getattr(dist, "_path", ""))
            user = SITE_DIR is not None and location.startswith(SITE_DIR)
            key = name.lower()
            # Prefer the user-installed copy when both exist.
            if key not in pkgs or user:
                pkgs[key] = {"name": name, "version": dist.version or "?",
                             "user": user}
        except Exception:
            continue
    out = sorted(pkgs.values(), key=lambda p: p["name"].lower())
    return json.dumps(out)


def uninstall_package(name):
    """Remove a user-installed package from SITE_DIR using its RECORD."""
    import importlib.metadata as md
    canon = name.lower().replace("-", "_")
    removed = False
    for entry in os.listdir(SITE_DIR):
        if not entry.endswith(".dist-info"):
            continue
        pkg = entry[:-len(".dist-info")].rsplit("-", 1)[0]
        if pkg.lower().replace("-", "_") != canon:
            continue
        dist_info = os.path.join(SITE_DIR, entry)
        record = os.path.join(dist_info, "RECORD")
        if os.path.exists(record):
            with open(record, encoding="utf-8") as f:
                for line in f:
                    rel = line.split(",")[0].strip()
                    if not rel:
                        continue
                    target = os.path.normpath(os.path.join(SITE_DIR, rel))
                    if target.startswith(SITE_DIR) and os.path.isfile(target):
                        try:
                            os.remove(target)
                        except OSError:
                            pass
        shutil.rmtree(dist_info, ignore_errors=True)
        removed = True
    # Clean up now-empty directories.
    for root, dirs, files in os.walk(SITE_DIR, topdown=False):
        if root != SITE_DIR and not dirs and not files:
            try:
                os.rmdir(root)
            except OSError:
                pass
    importlib.invalidate_caches()
    return removed


def python_version():
    return "Python %s" % sys.version.split()[0]


# ---------------------------------------------------------- live checking

def check_code(source):
    """Return a JSON list of problems for live, in-editor error checking.

    Each item: {line, col, endLine, endCol, message, severity}. Syntax errors
    come from compile(); pyflakes adds semantic warnings (undefined names,
    unused imports, redefinitions, ...) when the code parses cleanly.
    """
    problems = []
    try:
        compile(source, "<editor>", "exec")
    except SyntaxError as e:
        line = e.lineno or 1
        col = e.offset or 1
        problems.append({
            "line": line,
            "col": col,
            "endLine": getattr(e, "end_lineno", None) or line,
            "endCol": getattr(e, "end_offset", None) or (col + 1),
            "message": e.msg or "Syntax error",
            "severity": "error",
        })
        return json.dumps(problems)
    except Exception:
        return json.dumps(problems)

    # Code parses; add pyflakes warnings if available.
    try:
        from pyflakes import api as pf_api

        collector = problems

        class _Reporter:
            def unexpectedError(self, filename, msg):
                pass

            def syntaxError(self, filename, msg, lineno, offset, text):
                collector.append({
                    "line": lineno or 1,
                    "col": offset or 1,
                    "endLine": lineno or 1,
                    "endCol": (offset or 1) + 1,
                    "message": str(msg),
                    "severity": "error",
                })

            def flake(self, message):
                col = getattr(message, "col", 0) or 0
                try:
                    text = message.message % message.message_args
                except Exception:
                    text = str(message.message)
                collector.append({
                    "line": message.lineno,
                    "col": col + 1,
                    "endLine": message.lineno,
                    "endCol": col + 2,
                    "message": text,
                    "severity": "warning",
                })

        pf_api.check(source, "<editor>", reporter=_Reporter())
    except Exception:
        pass

    return json.dumps(problems)
