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

package bt.metainfo;

import bt.tracker.AnnounceKey;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * @since 1.0
 */
public interface Torrent {

    /**
     * @since 1.3
     */
    TorrentSource getSource();

    /**
     * @return Announce key, or {@link Optional#empty()} for trackerless torrents
     * @since 1.1
     */
    Optional<AnnounceKey> getAnnounceKey();

    /**
     * @return Torrent ID.
     * @since 1.0
     */
    TorrentId getTorrentId();

    /**
     * @return Suggested name for this torrent.
     * @since 1.0
     */
    String getName();

    /**
     * @return Size of a chunk, in bytes.
     * @since 1.0
     */
    long getChunkSize();

    /**
     * @return Sequence of SHA-1 hashes of all chunks in this torrent.
     * @since 1.0
     */
    Iterable<byte[]> getChunkHashes();

    /**
     * @return Total size of all chunks in this torrent, in bytes.
     * @since 1.0
     */
    long getSize();

    /**
     * @return Information on the files contained in this torrent.
     * @since 1.0
     */
    List<TorrentFile> getFiles();

    /**
     * @return True if this torrent is private (see BEP-27)
     * @since 1.0
     */
    boolean isPrivate();

    /**
     * @return Creation time of the torrent
     * @since 1.5
     */
    Optional<Instant> getCreationDate();

    /**
     * @return Creator of the torrent (usually name and version of the program used to create the .torrent file)
     * @since 1.5
     */
    Optional<String> getCreatedBy();

    /**
     * Web seed base URLs from the {@code url-list} entry (BEP-19).
     * Each URL serves the whole torrent over ordinary HTTP; a piece is fetched with
     * {@code GET {url}/{path}?piece={index}&range={start}-{end}}.
     *
     * @return Web seed URLs, empty if the torrent declares none
     * @since 1.0 (blaze fork)
     */
    default List<String> getWebSeedUrls() {
        return Collections.emptyList();
    }

    /**
     * HTTP/FTP seed URLs from the {@code httpseeds} entry (BEP-17).
     * Each URL serves the torrent as a byte-addressable stream; a piece is fetched with a
     * {@code Range: bytes={start}-{end}} request over the concatenated torrent.
     *
     * @return HTTP seed URLs, empty if the torrent declares none
     * @since 1.0 (blaze fork)
     */
    default List<String> getHttpSeedUrls() {
        return Collections.emptyList();
    }
}
