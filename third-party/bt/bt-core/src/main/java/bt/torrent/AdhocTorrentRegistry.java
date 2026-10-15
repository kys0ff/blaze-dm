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
import bt.data.Storage;
import bt.event.EventSink;
import bt.event.EventSource;
import bt.metainfo.Torrent;
import bt.metainfo.TorrentId;
import bt.torrent.callbacks.FileDownloadCompleteCallback;
import com.google.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Simple in-memory torrent registry, that creates new descriptors upon request.
 *
 * <p><b>Note that this class implements a service.
 * Hence, is not a part of the public API and is a subject to change.</b></p>
 */
public class AdhocTorrentRegistry implements TorrentRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(AdhocTorrentRegistry.class);

    /** Fixed lock-stripe count guarding the register/finalize check-then-act; bounded, never grows. */
    private static final int TEARDOWN_LOCK_STRIPES = 64;

    private IDataDescriptorFactory dataDescriptorFactory;
    private final EventSink eventSink;

    private Set<TorrentId> torrentIds;
    private ConcurrentMap<TorrentId, Torrent> torrents;
    private ConcurrentMap<TorrentId, DefaultTorrentDescriptor> descriptors;

    /**
     * Torrents whose session is being torn down. While an ID is present, {@code register()} refuses to
     * re-insert it, so a late registration from the dying session's executor cannot orphan a descriptor
     * after {@link #finalizeSession} removed it. Lifted by {@link #beginSession} when a genuinely new
     * session starts. Fixed-size lock stripes guard the check-then-act against {@link #finalizeSession}
     * without leaking one lock per torrent in the process-wide shared runtime.
     */
    private final Set<TorrentId> finalizing = ConcurrentHashMap.newKeySet();
    private final ReentrantLock[] teardownLocks = newStripes();

    private static ReentrantLock[] newStripes() {
        ReentrantLock[] locks = new ReentrantLock[TEARDOWN_LOCK_STRIPES];
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new ReentrantLock();
        }
        return locks;
    }

    private ReentrantLock lockFor(TorrentId torrentId) {
        return teardownLocks[Math.floorMod(torrentId.hashCode(), TEARDOWN_LOCK_STRIPES)];
    }

    @Inject
    public AdhocTorrentRegistry(IDataDescriptorFactory dataDescriptorFactory,
                                EventSource eventSource,
                                EventSink eventSink) {

        this.dataDescriptorFactory = dataDescriptorFactory;
        this.eventSink = eventSink;

        this.torrentIds = ConcurrentHashMap.newKeySet();
        this.torrents = new ConcurrentHashMap<>();
        this.descriptors = new ConcurrentHashMap<>();

        eventSource.onTorrentStopped(null, e -> unregister(e.getTorrentId()));
    }

    @Override
    public Collection<Torrent> getTorrents() {
        return Collections.unmodifiableCollection(torrents.values());
    }

    @Override
    public Collection<TorrentId> getTorrentIds() {
        return Collections.unmodifiableCollection(torrentIds);
    }

    @Override
    public Optional<Torrent> getTorrent(TorrentId torrentId) {
        Objects.requireNonNull(torrentId, "Missing torrent ID");
        return Optional.ofNullable(torrents.get(torrentId));
    }

    @Override
    public Optional<TorrentDescriptor> getDescriptor(Torrent torrent) {
        return Optional.ofNullable(descriptors.get(torrent.getTorrentId()));
    }

    @Override
    public Optional<TorrentDescriptor> getDescriptor(TorrentId torrentId) {
        Objects.requireNonNull(torrentId, "Missing torrent ID");
        return Optional.ofNullable(descriptors.get(torrentId));
    }

    @Override
    public TorrentDescriptor getOrCreateDescriptor(Torrent torrent, Storage storage) {
        return register(torrent, storage, null);
    }

    @Override
    public TorrentDescriptor register(Torrent torrent, Storage storage,
                                      FileDownloadCompleteCallback completedFileCallbacks) {
        TorrentId torrentId = torrent.getTorrentId();

        ReentrantLock lock = lockFor(torrentId);
        lock.lock();
        try {
            rejectIfFinalizing(torrentId);

            DefaultTorrentDescriptor descriptor = descriptors.computeIfAbsent(torrentId, k -> new DefaultTorrentDescriptor());
            if (descriptor.getDataDescriptor() != null) {
                throw new IllegalStateException(
                        "Torrent already registered and data descriptor created: " + torrent.getTorrentId());
            }
            descriptor.setDataDescriptor(dataDescriptorFactory.createDescriptor(torrent, storage, completedFileCallbacks));

            torrentIds.add(torrentId);
            torrents.putIfAbsent(torrentId, torrent);

            return descriptor;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public TorrentDescriptor register(TorrentId torrentId) {
        ReentrantLock lock = lockFor(torrentId);
        lock.lock();
        try {
            rejectIfFinalizing(torrentId);
            return getDescriptor(torrentId).orElseGet(() -> {
                DefaultTorrentDescriptor descriptor = new DefaultTorrentDescriptor();

                DefaultTorrentDescriptor existing = descriptors.putIfAbsent(torrentId, descriptor);
                if (existing != null) {
                    descriptor = existing;
                } else {
                    torrentIds.add(torrentId);
                }

                return descriptor;
            });
        } finally {
            lock.unlock();
        }
    }

    private void rejectIfFinalizing(TorrentId torrentId) {
        if (finalizing.contains(torrentId)) {
            // The session for this torrent is being finalized; a register() reaching here belongs to
            // the dying session's executor. Refuse it so it cannot re-insert a descriptor behind the
            // teardown - the terminated processing chain is the correct outcome for that thread.
            throw new IllegalStateException("Torrent session is being finalized: " + torrentId);
        }
    }

    @Override
    public void beginSession(TorrentId torrentId) {
        // A brand-new client is starting (DefaultClient#startAsync) - lift any leftover finalizing
        // guard so this session's registration is accepted. The dying session never re-enters here.
        finalizing.remove(torrentId);
    }

    @Override
    public boolean finalizeSession(TorrentId torrentId) {
        Objects.requireNonNull(torrentId, "Missing torrent ID");
        ReentrantLock lock = lockFor(torrentId);
        lock.lock();
        try {
            // Gate first, so a register() racing with this teardown is rejected rather than orphaning;
            // then fire the stop event. EventBus delivers it synchronously in this thread, running our
            // own onTorrentStopped -> unregister (see the constructor) plus the other per-torrent
            // services, so there is nothing to poll: the descriptor is gone by the time we return.
            finalizing.add(torrentId);
            eventSink.fireTorrentStopped(torrentId);
            return isFinalized(torrentId);
        } finally {
            lock.unlock();
        }
    }

    private boolean isFinalized(TorrentId torrentId) {
        return !descriptors.containsKey(torrentId) && !torrentIds.contains(torrentId);
    }

    @Override
    public boolean isSupportedAndActive(TorrentId torrentId) {
        Optional<TorrentDescriptor> descriptor = getDescriptor(torrentId);
        // it's OK if descriptor is not present -- torrent might be being fetched at the time
        return getTorrentIds().contains(torrentId)
                && (!descriptor.isPresent() || descriptor.get().isActive());
    }

    private void unregister(TorrentId torrentId) {
        torrentIds.remove(torrentId);
        torrents.remove(torrentId);
        DefaultTorrentDescriptor torrentDescriptor = descriptors.remove(torrentId);
        if (torrentDescriptor != null) {
            try {
                if (torrentDescriptor.getDataDescriptor() != null) {
                    torrentDescriptor.getDataDescriptor().close();
                }
            } catch (IOException e) {
                LOGGER.error("closing DataDescriptor error, torrentId: {}", torrentId, e);
            }
        }
    }

    @Override
    public void registerSessionState(TorrentId torrentId, TorrentSessionState state) {
        descriptors.get(torrentId).setSessionState(state);
    }
}
