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

import bt.data.Storage;
import bt.metainfo.Torrent;
import bt.metainfo.TorrentId;
import bt.torrent.callbacks.FileDownloadCompleteCallback;

import java.util.Collection;
import java.util.Optional;

/**
 * Registry of all torrents known to the current runtime.
 *
 * @since 1.0
 */
public interface TorrentRegistry {

    /**
     * Get all torrents, that have been registered in the runtime.
     *
     * @return All registered torrents
     * @since 1.2
     */
    Collection<Torrent> getTorrents();

    /**
     * Get all torrents, that have been registered in the runtime.
     *
     * @return All registered torrents
     * @since 1.3
     */
    Collection<TorrentId> getTorrentIds();

    /**
     * Get a torrent with a given torrent ID, if exists.
     *
     * @return {@link Optional#empty()} if this torrent ID is not known to the current runtime.
     * @since 1.0
     */
    Optional<Torrent> getTorrent(TorrentId torrentId);

    /**
     * Get a torrent descriptor for a given torrent, if exists.
     *
     * @return {@link Optional#empty()} if torrent descriptor hasn't been created yet.
     * @since 1.0
     * @deprecated since 1.3 in favor of {@link #getDescriptor(TorrentId)}
     */
    Optional<TorrentDescriptor> getDescriptor(Torrent torrent);

    /**
     * Get a torrent descriptor for a given torrent, if exists.
     *
     * @return {@link Optional#empty()} if torrent descriptor hasn't been created yet.
     * @since 1.3
     */
    Optional<TorrentDescriptor> getDescriptor(TorrentId torrentId);

    /**
     * Get an existing torrent descriptor for a given torrent
     * or create a new one if it does not exist.
     *
     * @param storage Storage to use for storing this torrent's files.
     *                Will be used when creating a new torrent descriptor.
     * @return Torrent descriptor
     * @since 1.0
     * @deprecated since 1.3 in favor of more clearly named {@link #register(Torrent, Storage, FileDownloadCompleteCallback)}
     */
    TorrentDescriptor getOrCreateDescriptor(Torrent torrent, Storage storage);

    /**
     * Get an existing torrent descriptor for a given torrent
     * or create a new one if it does not exist.
     *
     * @param storage Storage to use for storing this torrent's files.
     *                Will be used when creating a new torrent descriptor.
     * @return Torrent descriptor
     * @since 1.3
     */
    TorrentDescriptor register(Torrent torrent, Storage storage,
                               FileDownloadCompleteCallback fileCompletionCallback);

    /**
     * Register a session state for the specified torrent
     *
     * @param torrentId the torrent id
     * @param state     the session state of the torrent
     * @since 1.10
     */
    void registerSessionState(TorrentId torrentId, TorrentSessionState state);

    /**
     * Get an existing torrent descriptor for a given torrent ID
     * or create a new one if it does not exist.
     *
     * @return Torrent descriptor
     * @since 1.3
     */
    TorrentDescriptor register(TorrentId torrentId);

    boolean isSupportedAndActive(TorrentId torrentId);

    /**
     * Announce that a brand-new session is about to start for the given torrent, so the registry must
     * accept a fresh registration for it. A torn-down session ({@link #finalizeSession}) records that
     * torrent as finalizing so that a late {@code register()} from the *dying* session's executor can
     * no longer orphan a descriptor in the map; this call lifts that guard, and is made only by a
     * client that is genuinely starting a new transfer.
     *
     * <p>The default implementation is a no-op, for registries that do not track finalizing state.
     *
     * @param torrentId the torrent whose next registration should be allowed
     * @since 1.10 (blaze fork)
     */
    default void beginSession(TorrentId torrentId) {
        // no-op by default
    }

    /**
     * Synchronously tear down the session for the given torrent and report whether it is fully
     * unregistered by the time this method returns.
     *
     * <p>Unlike merely signalling a stop, this is a *completion* signal: when it returns
     * {@code true} the descriptor is provably gone from the registry and no in-flight registration
     * from the dying session will resurrect it, so the caller can start the next session for the same
     * torrent without polling the registry or waiting on a timer.
     *
     * <p>The default implementation performs no teardown and returns {@code false}, telling the
     * caller that a synchronous finalization is not available and it must fall back to its own
     * polling. Registries that can finalize inline (the in-memory one) override this.
     *
     * @param torrentId the torrent to finalize
     * @return {@code true} if the session is fully unregistered on return
     * @since 1.10 (blaze fork)
     */
    default boolean finalizeSession(TorrentId torrentId) {
        return false;
    }
}
