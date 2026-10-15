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

package bt.data.resume;

import bt.metainfo.TorrentId;

import java.util.Optional;

/**
 * Pluggable back-end that persists {@link ResumeState} per torrent.
 *
 * <p>The contract is deliberately forgiving on the read side: a store must never throw for data
 * it cannot read - an absent, unreadable or corrupt entry simply loads as
 * {@link Optional#empty()}, which makes the consumer fall back to a full verification. Losing
 * resume state must never cost more than the work of re-hashing.</p>
 *
 * @since 1.10 (blaze fork)
 */
public interface ResumeStore {

    /**
     * Loads the resume state previously saved for a torrent, if any usable one exists.
     *
     * @param torrentId Torrent identifier
     * @return Saved state, or empty when there is none or it cannot be read
     */
    Optional<ResumeState> load(TorrentId torrentId);

    /**
     * Atomically (re)writes the resume state for a torrent, replacing any previous entry.
     *
     * @param torrentId Torrent identifier
     * @param state     State to persist
     */
    void save(TorrentId torrentId, ResumeState state);

    /**
     * Removes any persisted state for a torrent. Absence of an entry is not an error.
     *
     * @param torrentId Torrent identifier
     */
    void delete(TorrentId torrentId);
}
