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

package bt.data;

import bt.metainfo.Torrent;

import java.util.List;

/**
 * Implements data verification strategy.
 *
 * @since 1.2
 */
public interface ChunkVerifier {

    /**
     * Conducts verification of the provided list of chunks and updates bitfield with the results.
     *
     * @param chunks List of chunks
     * @param bitfield the local bitfield for the torrent
     * @return true if all chunks have been verified successfully (meaning that all data is present and correct)
     * @since 1.2
     */
    boolean verify(List<ChunkDescriptor> chunks, LocalBitfield bitfield);

    /**
     * Context-aware variant of {@link #verify(List, LocalBitfield)}: implementations that can
     * skip or narrow verification based on where the torrent's data lives (e.g. trust-but-verify
     * resume of a previously verified bitfield) use the torrent metainfo and storage to
     * fingerprint the files. The default implementation ignores the context and verifies
     * everything, which keeps the behavior of existing verifiers unchanged.
     *
     * @param chunks   List of chunks
     * @param bitfield the local bitfield for the torrent
     * @param torrent  Torrent metainfo
     * @param storage  Storage back-end holding the torrent's files
     * @return true if all chunks have been verified successfully
     */
    default boolean verify(List<ChunkDescriptor> chunks, LocalBitfield bitfield, Torrent torrent, Storage storage) {
        return verify(chunks, bitfield);
    }

    /**
     * Called once when a torrent session is closed cleanly, with the final local bitfield.
     * Verifiers that persist derived state (e.g. a resume snapshot) hook in here; a process
     * crash means this callback never ran, and whatever was saved earlier is the state from
     * the last clean close - deliberately stale by then, and rejected by fingerprinting on the
     * next start. The default implementation does nothing.
     *
     * <p>Implementations must not throw: this runs on the shutdown path.</p>
     *
     * @param torrent  Torrent metainfo
     * @param storage  Storage back-end holding the torrent's files
     * @param bitfield Final local bitfield of the session
     */
    default void onTorrentClosed(Torrent torrent, Storage storage, LocalBitfield bitfield) {
    }

    /**
     * Conducts verification of the provided chunk.
     *
     * @param chunk Chunk
     * @return true if the chunk has been verified successfully (meaning that all data is present and correct)
     * @since 1.2
     */
    boolean verify(ChunkDescriptor chunk);

    /**
     * @since 1.9
     */
    boolean verifyIfPresent(ChunkDescriptor chunk);
}
