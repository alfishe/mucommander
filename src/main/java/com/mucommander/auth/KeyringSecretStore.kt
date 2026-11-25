/*
 * This file is part of trolCommander, http://www.trolsoft.ru/en/soft/trolcommander
 *
 * trolCommander is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * trolCommander is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.mucommander.auth

import org.slf4j.LoggerFactory
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * Stores account passwords in the desktop keyring (freedesktop Secret Service: GNOME Keyring, KWallet, KeePassXC...)
 * through the `secret-tool` command line client of libsecret.
 *
 * Every call runs in a separate process with a timeout, so a keyring daemon that doesn't answer (no session bus,
 * remote session without a keyring, ...) can never hang the application: [isAvailable] simply turns false and
 * [AccountStore] keeps passwords in its own file instead. Passwords are passed through stdin/stdout only, never on a
 * command line.
 */
object KeyringSecretStore {
    private val LOGGER = LoggerFactory.getLogger(KeyringSecretStore::class.java)

    private const val ATTR_APP = "application"
    private const val APP = "trolcommander"
    private const val ATTR_ACCOUNT = "account"

    /** Quick health check of the keyring service: it must answer an empty search within this time */
    private const val PROBE_TIMEOUT_SEC = 4L
    /** Reads/writes may trigger an unlock prompt of the keyring, give the user time to type the password */
    private const val ACCESS_TIMEOUT_SEC = 60L

    @Volatile
    private var available: Boolean? = null

    /** Human readable name of the backend, shown in the credentials manager */
    const val DISPLAY_NAME = "Secret Service (desktop keyring)"

    /**
     * Returns true if the keyring answers. The result is computed once (it may take up to a few seconds when the
     * keyring is broken) and cached for the session; call [probeAsync] early to have it ready.
     */
    @JvmStatic
    fun isAvailable(): Boolean {
        available?.let { return it }
        synchronized(this) {
            available?.let { return it }
            val result = probe()
            available = result
            return result
        }
    }

    /** Starts the availability check in the background so that the first connection doesn't wait for it. */
    @JvmStatic
    fun probeAsync() {
        if (available == null) {
            Thread({ isAvailable() }, "keyring-probe").apply { isDaemon = true }.start()
        }
    }

    private fun probe(): Boolean {
        if (System.getProperty("trolcommander.keyring", "auto") == "off") {
            LOGGER.info("keyring disabled by system property")
            return false
        }
        val tool = findTool() ?: run {
            LOGGER.info("secret-tool not found, passwords are stored in the credentials file")
            return false
        }
        // An empty search doesn't need the keyring to be unlocked: exit code 0 or 1 within the timeout = healthy
        val r = run(listOf(tool, "search", ATTR_APP, "$APP-probe"), null, PROBE_TIMEOUT_SEC)
        val ok = r != null && (r.exitCode == 0 || r.exitCode == 1)
        LOGGER.info("desktop keyring {}", if (ok) "available" else "not responding, using the credentials file")
        return ok
    }

    /** Returns the stored password of the given account, null if there is none or the keyring failed. */
    @JvmStatic
    fun get(accountId: String): String? {
        val tool = findTool() ?: return null
        val r = run(listOf(tool, "lookup", ATTR_APP, APP, ATTR_ACCOUNT, accountId), null, ACCESS_TIMEOUT_SEC)
        if (r == null || r.exitCode != 0) {
            return null
        }
        return r.stdout
    }

    /** Stores (or replaces) the password of an account; returns false if the keyring refused or didn't answer. */
    @JvmStatic
    fun put(accountId: String, label: String, password: String): Boolean {
        val tool = findTool() ?: return false
        val r = run(
            listOf(tool, "store", "--label=trolCommander: $label", ATTR_APP, APP, ATTR_ACCOUNT, accountId),
            password, ACCESS_TIMEOUT_SEC
        )
        return r != null && r.exitCode == 0
    }

    /** Removes the password of an account from the keyring (no-op if absent). */
    @JvmStatic
    fun delete(accountId: String) {
        val tool = findTool() ?: return
        run(listOf(tool, "clear", ATTR_APP, APP, ATTR_ACCOUNT, accountId), null, ACCESS_TIMEOUT_SEC)
    }

    private class Result(val exitCode: Int, val stdout: String)

    private fun run(cmd: List<String>, stdin: String?, timeoutSec: Long): Result? {
        return try {
            val p = ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            p.outputStream.use { out ->
                if (stdin != null) {
                    out.write(stdin.toByteArray(StandardCharsets.UTF_8))
                }
            }
            // The output is tiny (a password), reading it before waitFor() can't dead-lock on a full pipe buffer,
            // but a hung process would block the read: read on a helper thread and bound everything by the timeout.
            var stdout = ""
            val reader = Thread({
                stdout = try {
                    p.inputStream.readBytes().toString(StandardCharsets.UTF_8)
                } catch (_: Exception) {
                    ""
                }
            }, "secret-tool-reader").apply { isDaemon = true; start() }
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                LOGGER.warn("secret-tool {} timed out", cmd.getOrNull(1))
                return null
            }
            reader.join(1000)
            Result(p.exitValue(), stdout)
        } catch (e: Exception) {
            LOGGER.warn("secret-tool {} failed: {}", cmd.getOrNull(1), e.message)
            null
        }
    }

    private fun findTool(): String? {
        val path = System.getenv("PATH") ?: return null
        return path.split(File.pathSeparator)
            .map { File(it, "secret-tool") }
            .firstOrNull { it.canExecute() }
            ?.absolutePath
    }
}
