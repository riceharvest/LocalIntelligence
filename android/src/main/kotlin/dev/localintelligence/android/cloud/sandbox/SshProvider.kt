package dev.localintelligence.android.cloud.sandbox

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpException
import com.jcraft.jsch.UserInfo
import dev.localintelligence.core.tool.ToolError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.atomic.AtomicBoolean

// ===========================================================================
// The user's own SSH server, through JSch (com.github.mwiede:jsch, a maintained fork of JSch).
// Commands run over an exec channel in the user's login shell, in their home folder. Files go
// over SFTP, with paths relative to that home folder unless they start with /.
//
// HOST KEY. The server's key is pinned on the first connect, and a different key is refused.
// The pin is the SHA-256 fingerprint of the raw key blob, written as "SHA256:" plus unpadded
// base64 (the format OpenSSH prints), and saved in Settings as "host:port SHA256:...". JSch asks
// the HostKeyRepository before any login, so a changed key never receives the password or key.
// The first key is saved only after the login succeeds, so a failed login pins nothing.
//
// Passwords and private keys are read from Settings through SandboxCredentials and sent only to
// this server. Keys with a passphrase are not supported: JSch would need an interactive prompt.
// ===========================================================================

/** The id the Choice setting stores for this provider. */
internal const val SSH_ID: String = "ssh"

private const val CONNECT_TIMEOUT_MS = 20_000
private const val SOCKET_TIMEOUT_MS = 30_000
private const val MAX_OUTPUT_BYTES = 1024 * 1024
private const val CLOSE_WAIT_MS = 5_000L
private const val IDENTITY_NAME = "localintelligence-sandbox"
private const val FINGERPRINT_PREFIX = "SHA256:"

/**
 * The SSH provider. Built per call from the live settings. Each call opens its own connection and
 * closes it, so no connection outlives the call that made it.
 *
 * [pinnedKey] reads the saved pin and [savePin] stores a new one; both go through Settings, so a
 * pin survives a restart.
 */
internal class SshProvider(
    private val host: String,
    private val port: Int,
    private val user: String,
    private val privateKey: String,
    private val password: String,
    private val pinnedKey: () -> String,
    private val savePin: (String) -> Unit,
) : SandboxProvider {

    private val hostPort = "$host:$port"

    override suspend fun create(idleMinutes: Int): SandboxInfo = SandboxInfo(SSH_ID, "started", null)

    override suspend fun info(id: String): SandboxInfo = SandboxInfo(SSH_ID, "started", null)

    override suspend fun start(id: String) = Unit

    override suspend fun stop(id: String) = Unit

    override suspend fun exec(info: SandboxInfo, command: String, timeoutSeconds: Int): ExecResult =
        withContext(Dispatchers.IO) {
            withSession { session ->
                val channel = session.openChannel("exec") as ChannelExec
                val stderr = CappedOutput(MAX_OUTPUT_BYTES)
                channel.setCommand(command)
                channel.setInputStream(null)
                channel.setErrStream(stderr)
                val stdout = channel.inputStream
                channel.connect(CONNECT_TIMEOUT_MS)
                // A command that runs past its timeout is cut by closing its channel. The reader then
                // sees the end of the stream, and the flag tells the caller why.
                val timedOut = AtomicBoolean(false)
                val timer = Timer("ssh-exec-timeout", true)
                timer.schedule(
                    object : TimerTask() {
                        override fun run() {
                            timedOut.set(true)
                            channel.disconnect()
                        }
                    },
                    timeoutSeconds * 1000L,
                )
                try {
                    val out = readCapped(stdout, MAX_OUTPUT_BYTES)
                    val deadline = System.currentTimeMillis() + CLOSE_WAIT_MS
                    while (!channel.isClosed && System.currentTimeMillis() < deadline) Thread.sleep(20)
                    if (timedOut.get()) {
                        throw SandboxFailure(
                            ToolError.Timeout("ssh exec"),
                            "The command did not finish within $timeoutSeconds seconds, so it was stopped.",
                        )
                    }
                    val errors = stderr.text()
                    val output = out.text + if (errors.isNotEmpty()) (if (out.text.endsWith("\n") || out.text.isEmpty()) "" else "\n") + errors else ""
                    val cut = if (out.cut || stderr.cut) "\n(output over 1 MB was cut)" else ""
                    ExecResult(exitCode = channel.exitStatus, output = output + cut)
                } finally {
                    timer.cancel()
                    channel.disconnect()
                }
            }
        }

    override suspend fun writeFile(info: SandboxInfo, path: String, bytes: ByteArray): Unit =
        withContext(Dispatchers.IO) {
            withSftp { channel ->
                val slash = path.lastIndexOf('/')
                if (slash > 0) makeFolders(channel, path.substring(0, slash))
                channel.put(ByteArrayInputStream(bytes), path)
            }
        }

    override suspend fun readFile(info: SandboxInfo, path: String): ByteArray = withContext(Dispatchers.IO) {
        withSftp { channel ->
            val attributes = channel.stat(path)
            if (attributes.isDir) {
                throw SandboxFailure(ToolError.Unavailable("folder"), "$path is a folder, not a file.")
            }
            if (attributes.size > MAX_FILE_BYTES) {
                throw SandboxFailure(
                    ToolError.Unavailable("file too large"),
                    "$path is larger than ${MAX_FILE_BYTES / 1024} KB, which is too large to read here.",
                )
            }
            channel.get(path).use { readBounded(it, MAX_FILE_BYTES) }
        }
    }

    /** Opens a connection, checks and pins the host key, logs in, runs [block], and closes everything. */
    private fun <T> withSession(block: (Session) -> T): T {
        val repository = PinningRepository(pinnedFingerprint())
        val jsch = JSch()
        jsch.setHostKeyRepository(repository)
        if (privateKey.isNotBlank()) {
            jsch.addIdentity(IDENTITY_NAME, normalizePem(privateKey).toByteArray(Charsets.UTF_8), null, null)
        }
        val session = jsch.getSession(user, host, port)
        if (password.isNotBlank()) session.setPassword(password)
        session.setConfig("StrictHostKeyChecking", "yes")
        session.timeout = SOCKET_TIMEOUT_MS
        try {
            session.connect(CONNECT_TIMEOUT_MS)
            repository.observed?.let { savePin("$hostPort $it") }
            return block(session)
        } catch (e: JSchException) {
            throw sshFailure(e)
        } finally {
            session.disconnect()
        }
    }

    private fun <T> withSftp(block: (ChannelSftp) -> T): T = withSession { session ->
        val channel = session.openChannel("sftp") as ChannelSftp
        channel.connect(CONNECT_TIMEOUT_MS)
        try {
            block(channel)
        } catch (e: SftpException) {
            throw sftpFailure(e)
        } finally {
            channel.disconnect()
        }
    }

    /** The fingerprint saved for this host and port, or null when none is saved for them. */
    private fun pinnedFingerprint(): String? {
        val saved = pinnedKey()
        if (!saved.startsWith("$hostPort ")) return null
        return saved.substringAfter(' ').takeIf { it.startsWith(FINGERPRINT_PREFIX) }
    }

    private fun sshFailure(e: JSchException): SandboxFailure {
        val message = e.message.orEmpty()
        return when {
            message.contains("HostKey has been changed", ignoreCase = true) -> SandboxFailure(
                ToolError.PermissionDenied("host key changed"),
                "The SSH server's key is not the one pinned in Settings, so the connection was refused. " +
                    "If you re-keyed the server yourself, clear the SSH server fingerprint in Settings and try again.",
            )
            message.contains("Auth fail", ignoreCase = true) -> SandboxFailure(
                ToolError.PermissionDenied("ssh login"),
                "The SSH server refused the login. Check the user, private key and password in Settings.",
            )
            else -> SandboxFailure(
                ToolError.Unavailable("ssh ${e.javaClass.simpleName}"),
                "Could not connect to the SSH server (${message.take(ERROR_CHARS)}). Check the host, port and network.",
            )
        }
    }

    private fun sftpFailure(e: SftpException): SandboxFailure =
        if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) {
            SandboxFailure(ToolError.NotFound("no such file"), "There is no such file on the SSH server.")
        } else {
            SandboxFailure(
                ToolError.Unavailable("sftp ${e.id}"),
                "The SSH server refused the file operation (${(e.message ?: "").take(ERROR_CHARS)}).",
            )
        }

    /** Creates each folder of [dir] that does not exist yet, as mkdir -p would. SFTP has no such call. */
    private fun makeFolders(channel: ChannelSftp, dir: String) {
        val absolute = dir.startsWith("/")
        var built = ""
        for (part in dir.split('/').filter { it.isNotEmpty() }) {
            built = when {
                built.isNotEmpty() -> "$built/$part"
                absolute -> "/$part"
                else -> part
            }
            try {
                channel.stat(built)
            } catch (e: SftpException) {
                channel.mkdir(built)
            }
        }
    }

    private companion object {
        const val ERROR_CHARS = 160
    }
}

/**
 * Pins the first key it sees, and refuses any other key afterwards. JSch calls [check] during key
 * exchange, before the login, so a refused key never gets a password or a key sent to it.
 */
private class PinningRepository(private val pinned: String?) : HostKeyRepository {
    /** The fingerprint of the key seen on the first connect, to be saved once the login succeeds. */
    var observed: String? = null
        private set

    override fun check(host: String, key: ByteArray): Int {
        val fingerprint = fingerprintOf(key)
        return when {
            pinned == null -> {
                observed = fingerprint
                HostKeyRepository.OK
            }
            pinned == fingerprint -> HostKeyRepository.OK
            else -> HostKeyRepository.CHANGED
        }
    }

    override fun add(hostkey: HostKey, ui: UserInfo?) = Unit

    override fun remove(host: String, type: String?) = Unit

    override fun remove(host: String, type: String?, key: ByteArray?) = Unit

    override fun getKnownHostsRepositoryID(): String = IDENTITY_NAME

    override fun getHostKey(): Array<HostKey> = emptyArray()

    override fun getHostKey(host: String, type: String?): Array<HostKey> = emptyArray()
}

/** The OpenSSH-style SHA-256 fingerprint of a raw host key blob. */
internal fun fingerprintOf(keyBlob: ByteArray): String =
    FINGERPRINT_PREFIX + Base64.getEncoder().withoutPadding().encodeToString(
        MessageDigest.getInstance("SHA-256").digest(keyBlob),
    )

/**
 * A pasted private key rebuilt with its line breaks. A single-line field drops them, and JSch needs
 * them. Text that has no PEM header is returned as it was, and JSch reports the problem.
 */
internal fun normalizePem(raw: String): String {
    val text = raw.trim()
    val match = Regex("-----BEGIN ([A-Z0-9 ]+)-----(.*?)-----END ([A-Z0-9 ]+)-----", RegexOption.DOT_MATCHES_ALL)
        .find(text) ?: return text + "\n"
    val label = match.groupValues[1]
    val body = match.groupValues[2].filterNot { it.isWhitespace() }
    return "-----BEGIN $label-----\n" + body.chunked(64).joinToString("\n") + "\n-----END $label-----\n"
}

/** Reads a stream to its end, keeping at most [max] bytes. The rest is read and dropped, so the server is never held. */
private fun readCapped(input: InputStream, max: Int): CappedText {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    var cut = false
    while (true) {
        val n = input.read(buffer)
        if (n < 0) break
        val room = max - out.size()
        if (room > 0) out.write(buffer, 0, minOf(room, n))
        if (n > room) cut = true
    }
    return CappedText(out.toString(Charsets.UTF_8.name()), cut)
}

private class CappedText(val text: String, val cut: Boolean)

/** An error stream that keeps its first [max] bytes and drops the rest. JSch writes to it from its own thread. */
private class CappedOutput(private val max: Int) : OutputStream() {
    private val out = ByteArrayOutputStream()
    var cut = false
        private set

    override fun write(b: Int) {
        if (out.size() < max) out.write(b) else cut = true
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        val room = max - out.size()
        if (room > 0) out.write(b, off, minOf(room, len))
        if (len > room) cut = true
    }

    fun text(): String = out.toString(Charsets.UTF_8.name())
}
