package org.blaze.engine.network.webseed

import bt.net.Peer
import bt.net.PeerId
import bt.peer.PeerOptions
import java.net.InetAddress
import java.util.Optional

/**
 * A stand-in [Peer] that labels the blocks a web/HTTP seed (BEP-17 / BEP-19) feeds into `bt`'s
 * [bt.torrent.data.DataWorker].
 *
 * The library's write path only ever asks a peer for its address, port, id and options - it never
 * opens a socket back through this object, because the bytes are already in hand from the HTTP
 * fetch. So a lightweight loopback identity is enough to satisfy the API while keeping web-seed
 * writes distinguishable from real swarm traffic in logs and metrics.
 */
internal object WebSeedPeer : Peer {
    private val loopback: InetAddress = InetAddress.getLoopbackAddress()

    override fun getInetAddress(): InetAddress = loopback

    // A web seed has no BitTorrent listening port and no peer identity; both are "unknown".
    override fun isPortUnknown(): Boolean = true

    override fun getPort(): Int = UNKNOWN_PORT

    override fun getPeerId(): Optional<PeerId> = Optional.empty()

    override fun getOptions(): PeerOptions = PeerOptions.defaultOptions()

    private const val UNKNOWN_PORT = -1

    override fun toString(): String = "web-seed"
}
