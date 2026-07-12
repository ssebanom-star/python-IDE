package com.ssebanom.pythonide

import android.content.Context
import java.io.File

/**
 * A rootless Linux container powered by proot + an Alpine rootfs, both bundled
 * in the APK. This is how a language toolchain can actually be installed and
 * run on an unrooted Android device (the same mechanism Termux/proot-distro
 * use): proot emulates chroot/mount via ptrace, so `apk add nodejs`, `ruby`,
 * `go`, etc. run inside the guest.
 *
 * Requires the app to target an SDK old enough to execute files from its
 * private storage (see targetSdk in build.gradle).
 */
object ContainerManager {

    private const val ASSET_DIR = "container"
    private val BOOTSTRAP = listOf(
        "proot", "loader", "loader32", "libtalloc.so.2",
        "libandroid-shmem.so", "busybox"
    )
    // Neutral extension so aapt doesn't gunzip/rename it; still gzip content.
    private const val ROOTFS_ASSET = "alpine-rootfs.dat"
    private const val MARKER = ".installed"

    private fun home(ctx: Context) = File(ctx.filesDir, "container")
    private fun binDir(ctx: Context) = File(home(ctx), "bin")
    private fun libDir(ctx: Context) = File(home(ctx), "lib")
    private fun rootfs(ctx: Context) = File(home(ctx), "rootfs")
    private fun tmpDir(ctx: Context) = File(ctx.cacheDir, "container-tmp")
    private fun proot(ctx: Context) = File(binDir(ctx), "proot")
    private fun busybox(ctx: Context) = File(binDir(ctx), "busybox")

    fun isInstalled(ctx: Context): Boolean =
        File(rootfs(ctx), MARKER).exists() && proot(ctx).canExecute()

    /**
     * Extract the bootstrap tools and Alpine rootfs. Streams progress to [out].
     * Returns true on success. Safe to re-run (rebuilds from scratch).
     */
    fun setup(ctx: Context, out: (String) -> Unit): Boolean {
        try {
            out("Preparing container directories…\n")
            home(ctx).mkdirs(); binDir(ctx).mkdirs(); libDir(ctx).mkdirs()
            tmpDir(ctx).mkdirs()
            rootfs(ctx).deleteRecursively(); rootfs(ctx).mkdirs()

            out("Unpacking proot and busybox…\n")
            copyAsset(ctx, "$ASSET_DIR/proot", proot(ctx), exec = true)
            copyAsset(ctx, "$ASSET_DIR/loader", File(binDir(ctx), "loader"), exec = true)
            copyAsset(ctx, "$ASSET_DIR/loader32", File(binDir(ctx), "loader32"), exec = true)
            copyAsset(ctx, "$ASSET_DIR/busybox", busybox(ctx), exec = true)
            copyAsset(ctx, "$ASSET_DIR/libtalloc.so.2",
                File(libDir(ctx), "libtalloc.so.2"), exec = false)
            copyAsset(ctx, "$ASSET_DIR/libandroid-shmem.so",
                File(libDir(ctx), "libandroid-shmem.so"), exec = false)

            out("Extracting Alpine Linux rootfs (this runs once)…\n")
            val tarball = File(ctx.cacheDir, ROOTFS_ASSET)
            copyAsset(ctx, "$ASSET_DIR/$ROOTFS_ASSET", tarball, exec = false)
            // busybox tar auto-detects gzip and preserves symlinks/permissions.
            val rc = execHost(
                listOf(busybox(ctx).absolutePath, "tar", "-xf",
                    tarball.absolutePath, "-C", rootfs(ctx).absolutePath),
                ctx, out
            )
            tarball.delete()
            if (rc != 0) { out("\nrootfs extraction failed (code $rc)\n"); return false }

            out("Configuring network and repositories…\n")
            File(rootfs(ctx), "etc").mkdirs()
            File(rootfs(ctx), "etc/resolv.conf")
                .writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
            File(rootfs(ctx), "etc/apk").mkdirs()
            File(rootfs(ctx), "etc/apk/repositories").writeText(
                "https://dl-cdn.alpinelinux.org/alpine/latest-stable/main\n" +
                "https://dl-cdn.alpinelinux.org/alpine/latest-stable/community\n"
            )
            File(rootfs(ctx), "root").mkdirs()
            File(rootfs(ctx), MARKER).writeText("ok")
            out("\nContainer ready. Alpine Linux is installed.\n")
            return true
        } catch (e: Throwable) {
            out("\nSetup error: ${e.message}\n")
            return false
        }
    }

    /**
     * Run a shell [command] inside the container, binding the user's scripts
     * folder at /root/scripts. Streams combined output to [out]; [cancel] lets
     * the caller abort. Returns the process exit code.
     */
    fun run(
        ctx: Context, command: String, scriptsDir: File,
        out: (String) -> Unit, cancel: () -> Boolean
    ): Int {
        val argv = listOf(
            proot(ctx).absolutePath,
            "--kill-on-exit",
            "--link2symlink",
            "-r", rootfs(ctx).absolutePath,
            "-0",
            "-b", "/proc", "-b", "/dev", "-b", "/sys",
            "-b", "${tmpDir(ctx).absolutePath}:/tmp",
            "-b", "${scriptsDir.absolutePath}:/root/scripts",
            "-w", "/root",
            "/bin/sh", "-lc", command
        )
        val pb = ProcessBuilder(argv).redirectErrorStream(true)
        val env = pb.environment()
        env["PROOT_LOADER"] = File(binDir(ctx), "loader").absolutePath
        env["PROOT_LOADER_32"] = File(binDir(ctx), "loader32").absolutePath
        env["PROOT_TMP_DIR"] = tmpDir(ctx).absolutePath
        env["PROOT_NO_SECCOMP"] = "1"
        env["LD_LIBRARY_PATH"] = "${libDir(ctx).absolutePath}:/system/lib64"
        env["HOME"] = "/root"
        env["TERM"] = "xterm-256color"
        env["TMPDIR"] = "/tmp"
        env["LANG"] = "C.UTF-8"
        env["PATH"] =
            "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        return try {
            val proc = pb.start()
            proc.outputStream.close()
            val reader = proc.inputStream.bufferedReader()
            val buf = CharArray(2048)
            while (true) {
                if (cancel()) { proc.destroy(); out("\n[Stopped]\n"); break }
                val n = reader.read(buf)
                if (n < 0) break
                if (n > 0) out(String(buf, 0, n))
            }
            proc.waitFor()
        } catch (e: Throwable) {
            out("\nFailed to start container: ${e.message}\n")
            -1
        }
    }

    /** Run a bootstrap tool on the host (outside proot), e.g. busybox tar. */
    private fun execHost(argv: List<String>, ctx: Context, out: (String) -> Unit): Int {
        val pb = ProcessBuilder(argv).redirectErrorStream(true)
        pb.environment()["PROOT_TMP_DIR"] = tmpDir(ctx).absolutePath
        return try {
            val proc = pb.start()
            proc.inputStream.bufferedReader().forEachLine { out(it + "\n") }
            proc.waitFor()
        } catch (e: Throwable) {
            out("exec error: ${e.message}\n"); -1
        }
    }

    private fun copyAsset(ctx: Context, assetPath: String, dest: File, exec: Boolean) {
        dest.parentFile?.mkdirs()
        ctx.assets.open(assetPath).use { input ->
            dest.outputStream().use { input.copyTo(it) }
        }
        if (exec) dest.setExecutable(true, false)
    }
}
