/*
 * Copyright (c) 2016—2021 Andrei Tomashpolskiy and individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package bt.torrent;

import bt.data.IDataDescriptorFactory;
import bt.event.EventBus;
import bt.metainfo.TorrentId;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * TOR-7: {@code finalizeSession} must be a synchronous completion signal — when it returns
 * {@code true} the descriptor is provably gone and cannot be resurrected by a late registration from
 * the dying session's executor — so the host no longer polls the registry (or sleeps) to learn that a
 * pause settled. {@code beginSession} is the one legitimate way to lift that guard: only a brand-new
 * client calls it, which is what keeps a resume working. The whole suite drives a real {@link EventBus}
 * (the library's synchronous delivery) and needs no network or runtime.
 */
public class AdhocTorrentRegistryFinalizeTest {

    /** A factory that produces a null data descriptor: enough to exercise registration/teardown bookkeeping. */
    private static final IDataDescriptorFactory NULL_FACTORY =
            (torrent, storage, fileCompletionCallback) -> null;

    private static TorrentId id(int b0) {
        byte[] bytes = new byte[TorrentId.length()];
        bytes[0] = (byte) b0;
        return TorrentId.fromBytes(bytes);
    }

    private AdhocTorrentRegistry newRegistry(EventBus bus) {
        return new AdhocTorrentRegistry(NULL_FACTORY, bus, bus);
    }

    @Test
    public void finalizeSessionRemovesTheRegisteredDescriptorSynchronously() {
        EventBus bus = new EventBus();
        AdhocTorrentRegistry registry = newRegistry(bus);
        TorrentId torrentId = id(1);

        registry.register(torrentId);
        assertTrue(registry.getDescriptor(torrentId).isPresent());

        // No polling, no timer: the synchronous event delivery unregisters before the call returns.
        assertTrue("finalizeSession must report a settled session on return", registry.finalizeSession(torrentId));
        assertFalse(registry.getDescriptor(torrentId).isPresent());
        assertFalse(registry.getTorrentIds().contains(torrentId));
    }

    @Test
    public void lateRegisterFromTheDyingSessionIsRejectedWhileFinalizing() {
        EventBus bus = new EventBus();
        AdhocTorrentRegistry registry = newRegistry(bus);
        TorrentId torrentId = id(2);

        registry.register(torrentId);
        assertTrue(registry.finalizeSession(torrentId));

        // A register() landing after the teardown belongs to the stopped session's chain; it must not
        // re-insert, or the next real session would die with "Torrent already registered".
        try {
            registry.register(torrentId);
            fail("expected the finalizing guard to reject a late registration");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("finalized"));
        }
        assertFalse(registry.getDescriptor(torrentId).isPresent());
    }

    @Test
    public void beginSessionLiftsTheGuardSoAFreshResumeRegisters() {
        EventBus bus = new EventBus();
        AdhocTorrentRegistry registry = newRegistry(bus);
        TorrentId torrentId = id(3);

        registry.register(torrentId);
        registry.finalizeSession(torrentId);

        // A brand-new session (DefaultClient#startAsync) announces itself before its chain registers.
        registry.beginSession(torrentId);
        registry.register(torrentId);
        assertTrue("a resumed session must be able to register the same torrent again",
                registry.getDescriptor(torrentId).isPresent());
    }

    @Test
    public void finalizeOfNeverRegisteredIdReportsSettledWithoutError() {
        EventBus bus = new EventBus();
        AdhocTorrentRegistry registry = newRegistry(bus);

        // An id that never registered (or was already cleaned up) is trivially settled — the engine's
        // fast path should short-circuit, not fall into its bounded retry rounds.
        assertTrue(registry.finalizeSession(id(4)));
    }

    @Test
    public void finalizeIsIdempotentAndStaysSettled() {
        EventBus bus = new EventBus();
        AdhocTorrentRegistry registry = newRegistry(bus);
        TorrentId torrentId = id(5);

        registry.register(torrentId);
        assertTrue(registry.finalizeSession(torrentId));
        // A second teardown (e.g. a re-fired stop) must not error and must remain settled.
        assertTrue(registry.finalizeSession(torrentId));
        assertFalse(registry.getDescriptor(torrentId).isPresent());
    }
}
