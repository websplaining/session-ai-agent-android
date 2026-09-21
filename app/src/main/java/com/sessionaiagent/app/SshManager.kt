package com.sessionaiagent.app

import android.util.Base64
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.xfer.InMemorySourceFile
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.security.PublicKey
import java.util.concurrent.TimeUnit

/** Thin wrapper around SSHJ: password auth, TOFU host-key capture, SFTP upload, exec streaming. */
class SshManager {

    private var ssh: SSHClient? = null

    /**
     * Connects and authenticates. Captures the SHA-256 host-key fingerprint.
     * If [expectedFingerprint] is provided and differs -> SecurityException (possible MITM / rebuilt server).
     * Returns the fingerprint.
     */
    fun connect(host: String, port: Int, user: String, password: String, expectedFingerprint: String?): String {
        disconnect()
        val captured = arrayOf("")
        val client = SSHClient()
        client.addHostKeyVerifier(object : HostKeyVerifier {
            override fun verify(hostname: String, p: Int, key: PublicKey): Boolean {
                val digest = MessageDigest.getInstance("SHA-256").digest(key.encoded)
                captured[0] = "SHA256:" + Base64.encodeToString(digest, Base64.NO_WRAP or Base64.NO_PADDING)
                return true
            }

            override fun findExistingAlgorithms(hostname: String, p: Int): List<String> = emptyList()
        })
        client.connectTimeout = 20_000
        client.timeout = 20_000
        try {
            client.connect(host, port)
            client.authPassword(user, password)
        } catch (e: Exception) {
            try { client.disconnect() } catch (_: Exception) {}
            throw IllegalArgumentException(friendlyError(e))
        }
        expectedFingerprint?.takeIf { it.isNotEmpty() }?.let { expected ->
            if (expected != captured[0]) {
                try { client.disconnect() } catch (_: Exception) {}
                throw SecurityException(
                    "SERVER HOST KEY CHANGED!\n\nExpected: $expected\nGot: ${captured[0]}\n\n" +
                        "This can happen if the server was rebuilt, or could indicate a man-in-the-middle attack. " +
                        "If you rebuilt/reinstalled the server, clear the app's data to trust it again."
                )
            }
        }
        ssh = client
        return captured[0]
    }

    private fun friendlyError(e: Exception): String {
        val msg = e.message ?: ""
        return when {
            msg.contains("Auth fail", true) ->
                "Authentication failed - check the username and password. Password login must be enabled on your server."
            msg.contains("Connection refused", true) ->
                "Connection refused - check the IP address and that SSH is running on that port."
            msg.contains("timed out", true) || msg.contains("timeout", true) ->
                "Connection timed out - check the server IP and port."
            msg.contains("UnknownHost", true) -> "Unknown host - check the server IP or hostname."
            else -> msg.ifEmpty { "SSH connection failed" }
        }
    }

    /** Uploads the installer script to /tmp and marks it executable. */
    fun uploadInstaller(script: String) {
        val client = ssh ?: throw IllegalStateException("not connected")
        val bytes = script.toByteArray(Charsets.UTF_8)
        client.newSFTPClient().use { sftp ->
            sftp.put(object : InMemorySourceFile() {
                override fun getName() = "saa-app-setup.sh"
                override fun getLength() = bytes.size.toLong()
                override fun getInputStream(): InputStream = ByteArrayInputStream(bytes)
            }, "/tmp/saa-app-setup.sh")
            sftp.chmod("/tmp/saa-app-setup.sh", 493) // 0755
        }
    }

    /** Runs a command, returns captured stdout. */
    fun execCapture(command: String, timeoutSec: Long = 300): String {
        val client = ssh ?: throw IllegalStateException("not connected")
        val session = client.startSession()
        try {
            val cmd = session.exec(command)
            val out = cmd.inputStream.bufferedReader().readText()
            val err = cmd.errorStream.bufferedReader().readText()
            cmd.join(timeoutSec, TimeUnit.SECONDS)
            return out + err
        } finally {
            try { session.close() } catch (_: Exception) {}
        }
    }

    /** Starts the installer detached (survives SSH drops) and returns the remote log path. */
    fun uploadAndExecDetached(env: String, logPath: String): String {
        val start = "rm -f $logPath; touch $logPath; " +
            "setsid env $env bash /tmp/saa-app-setup.sh > $logPath 2>&1 < /dev/null & echo SAA_STARTED"
        val out = execCapture(start, 60)
        if (!out.contains("SAA_STARTED")) throw IllegalStateException("could not start installer: ${out.take(200)}")
        return logPath
    }

    /** Streams a remote log file; stops when [stop] returns true. */
    fun streamLog(logPath: String, onLine: (String) -> Unit, stop: () -> Boolean) {
        val client = ssh ?: throw IllegalStateException("not connected")
        val session = client.startSession()
        try {
            val cmd = session.exec("tail -n +1 -f $logPath")
            val reader = cmd.inputStream.bufferedReader()
            val t = Thread {
                try {
                    reader.forEachLine { line ->
                        if (stop()) return@forEachLine
                        onLine(line)
                    }
                } catch (_: Exception) {
                }
            }
            t.isDaemon = true
            t.start()
            while (!stop()) Thread.sleep(150)
            t.interrupt()
        } finally {
            try { session.close() } catch (_: Exception) {}
        }
    }

    fun disconnect() {
        try { ssh?.disconnect() } catch (_: Exception) {}
        ssh = null
    }

    companion object {
        /** POSIX single-quote escaping for env values. */
        fun shQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

        fun envString(pairs: Map<String, String>): String =
            pairs.entries.joinToString(" ") { "${it.key}=${shQuote(it.value)}" }
    }
}
