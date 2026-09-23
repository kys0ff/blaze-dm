package org.blaze.osintegration.singleinstance

import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.net.Socket
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.io.InputStream
import java.io.OutputStream

/**
 * The launcher side of the single-instance channel: connect to the running owner (unix socket if one
 * is published, otherwise the loopback TCP fallback), deliver one authenticated frame, read the ack.
 *
 * A false return means "the owner could not be reached at all" - the coordinator then decides whether
 * to take over ownership or start a plain second GUI; the caller never retries the *content*, because
 * a delivered frame is never resent (duplicate download creation is worse than a lost one here).
 */
internal object InstanceClient {
    private val logger = LoggerFactory.getLogger(InstanceClient::class.java)
    private const val CONNECT_TIMEOUT_MILLIS = 2_000
    private const val READ_TIMEOUT_MILLIS = 3_000

    fun send(socketFile: Path, tcpPortFile: Path, frameJson: String): Boolean {
        val connection = open(socketFile, tcpPortFile) ?: return false
        // A hung owner must not hang the launcher forever: close the socket under timeout, which
        // aborts the blocking read; the frame itself is written atomically before the wait starts.
        val watchdog = java.util.Timer("blaze-instance-timeout", true).also {
            it.schedule(object : java.util.TimerTask() { override fun run() { runCatching { connection.close() } } },
                READ_TIMEOUT_MILLIS.toLong())
        }
        return connection.use {
            runCatching {
                InstanceProtocol.writeFrame(it.output, frameJson)
                InstanceProtocol.readLineCapped(it.input) == InstanceProtocol.REPLY_OK
            }.getOrElse {
                logger.warn("Could not complete the single-instance hand-off", it)
                false
            }
        }.also { watchdog.cancel() }
    }

    private fun open(socketFile: Path, tcpPortFile: Path): Connection? {
        if (Files.exists(socketFile)) {
            runCatching {
                val socket = SocketChannel.open(StandardProtocolFamily.UNIX)
                socket.connect(UnixDomainSocketAddress.of(socketFile.toAbsolutePath().toString()))
                return Connection(Channels.newInputStream(socket), Channels.newOutputStream(socket), socket)
            }.onFailure { logger.debug("Unix instance socket unreachable, trying the TCP fallback", it) }
        }
        val port = runCatching { Files.readString(tcpPortFile).trim().toInt() }.getOrNull() ?: return null
        return runCatching {
            val socket = Socket()
            socket.connect(java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), port), CONNECT_TIMEOUT_MILLIS)
            socket.soTimeout = READ_TIMEOUT_MILLIS
            Connection(socket.getInputStream(), socket.getOutputStream(), socket)
        }.getOrElse {
            logger.debug("Loopback instance fallback unreachable", it)
            null
        }
    }

    internal class Connection(
        val input: InputStream,
        val output: OutputStream,
        private val closable: java.io.Closeable,
    ) : java.io.Closeable by closable
}
