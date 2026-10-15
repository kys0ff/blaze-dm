package org.blaze.engine.network

import bt.module.ServiceModule
import bt.tracker.udp.UdpTrackerFactory
import com.google.inject.Binder
import com.google.inject.Module

/**
 * Registers bt's UDP tracker client for `udp://` announce URLs.
 *
 * The UDP tracker implementation (`bt.tracker.udp.UdpTracker`) ships inside `bt-core`, which we
 * already depend on, but unlike the HTTP tracker there is no published module that auto-wires it
 * through the service loader - so before this, a torrent whose announce list is only `udp://`
 * trackers got zero peers from trackers and fell back entirely to DHT / PEX. Most public torrents
 * announce over UDP, so this closes a real discovery gap without adding any dependency.
 */
internal class UdpTrackerModule : Module {
    override fun configure(binder: Binder) {
        ServiceModule.extend(binder).addTrackerFactory(UdpTrackerFactory::class.java, "udp")
    }
}
