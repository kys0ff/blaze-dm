package org.blaze.osintegration.singleinstance

import org.blaze.osintegration.ExternalOpenInbox
import org.blaze.osintegration.ExternalOpenParser
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.Path

/**
 * The owner side of the single-instance channel: a one-frame-per-connection, token-authenticated
 * local listener that feeds forwarded open requests straight into the shared [ExternalOpenInbox].
 *
 * Transport preference is an AF_UNIX socket in the data directory (Linux/macOS and Windows 10+);
 * where the platform cannot bind one, it falls back to a loopback-only TCP port whose number is
 * published next to the `0600` token - the frame still has to carry the token, so the fallback is
 * *authenticated* loopback, never an open network server. Every frame is re-parsed and re-validated
 * on arrival: authentication proves who sent it, not that its content is sane.
 */
internal class InstanceServer(
    private val socketFile: Path,
    private val tcpPortFile: Path,
    private val tokenStore: InstanceTokenStore,
    private val parser: ExternalOpenParser,
    private val inbox: ExternalOpenInbox,
) : Closeable {

    private val logger = LoggerFactory.getLogger(InstanceServer::class.java)

    @Volatile
    private var running = false
    private var thread: Thread? = null
    private var unixServer: ServerSocketChannel? = null
    private var tcpServer: ServerSocket? = null

    /** Binds the listener; false when neither transport can be opened (the caller still runs, minus forwarding). */
    fun start(): Boolean {
        tokenStore.token() // publish/refresh the shared secret before anyone may connect
        runCatching { Files.deleteIfExists(socketFile) } // stale socket of a dead owner: we hold the lock
        runCatching { Files.deleteIfExists(tcpPortFile) }

        val unix = runCatching {
            ServerSocketChannel.open(StandardProtocolFamily.UNIX).apply {
                bind(UnixDomainSocketAddress.of(socketFile.toAbsolutePath().toString()))
            }
        }.getOrNull()
        if (unix != null) {
            unixServer = unix
            startLoop("instance-unix") {
                val socket = unix.accept()
                Connection(Channels.newInputStream(socket), Channels.newOutputStream(socket), socket)
            }
            logger.info("Single-instance listener bound at {}", socketFile)
            return true
        }

        val tcp = runCatching {
            ServerSocket(0, 8, InetAddress.getLoopbackAddress()).also { server ->
                Files.writeString(tcpPortFile, server.localPort.toString())
                runCatching {
                    Files.setPosixFilePermissions(
                        tcpPortFile,
                        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"),
                    )
                } // Non-POSIX filesystems keep their ACL defaults.
            }
        }.getOrNull() ?: return false
        tcpServer = tcp
        startLoop("instance-tcp") {
            val socket = tcp.accept()
            Connection(socket.getInputStream(), socket.getOutputStream(), socket)
        }
        logger.info("Single-instance listener (loopback TCP fallback) on port {}", tcp.localPort)
        return true
    }

    private fun startLoop(name: String, accept: () -> Connection) {
        running = true
        thread = Thread({
            while (running) {
                val connection = try {
                    accept()
                } catch (e: Exception) {
                    // Either close() ran (normal shutdown) or the listener broke; exit either way.
                    if (running) logger.info("Instance listener stopped: {}", e.message)
                    break
                }
                runCatching { handle(connection) }
                    .onFailure { logger.warn("Failed serving a single-instance connection", it) }
                runCatching { connection.close() }
            }
        }, "blaze-$name").apply { isDaemon = true }
        thread?.start()
    }

    private fun handle(connection: Connection) {
        val frame = InstanceProtocol.readLineCapped(connection.input)
        val decoded = frame?.let { InstanceProtocol.decode(it) }
        val requests = decoded
            ?.takeIf { tokenStore.matches(it.token) && it.requests.size <= MAX_FRAMED_REQUESTS }
            ?.let { InstanceProtocol.toRequests(it, parser) }
        if (requests != null) {
            logger.info("Accepted {} forwarded open request(s) from a launcher process.", requests.size)
            inbox.submit(requests)
        } else {
            logger.warn("Rejected a single-instance frame (bad token, oversized or malformed request).")
        }
        InstanceProtocol.writeFrame(connection.output, if (requests != null) InstanceProtocol.REPLY_OK else InstanceProtocol.REPLY_ERR)
    }

    override fun close() {
        running = false
        runCatching { unixServer?.close() } // unblocks accept() in the loop
        runCatching { tcpServer?.close() }
        runCatching { Files.deleteIfExists(socketFile) }
        runCatching { Files.deleteIfExists(tcpPortFile) }
        unixServer = null
        tcpServer = null
        thread = null
    }

    /** One accepted peer, transport-agnostic: read the frame, write the reply, close. */
    private class Connection(
        val input: InputStream,
        val output: OutputStream,
        private val closable: Closeable,
    ) : Closeable by closable

    private companion object {
        const val MAX_FRAMED_REQUESTS = 64
    }
}
