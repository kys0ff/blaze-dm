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

package bt.torrent.webseed;

import bt.metainfo.Torrent;
import bt.metainfo.TorrentFile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Maps a BitTorrent piece onto the byte ranges of the underlying files that a web/HTTP seed
 * (BEP-17 {@code httpseeds} / BEP-19 {@code url-list}) serves.
 *
 * <p>Pieces are defined over the concatenation of every file in the torrent, in {@code files}
 * order. A piece therefore either sits wholly inside one file or straddles a boundary between
 * consecutive files; in the latter case it is described by two or more {@link WebSeedSegment}s,
 * listed in file order, whose fetched bytes concatenate to exactly the piece. This is the
 * correctness-critical, HTTP-agnostic half of web-seed support — the transport itself is supplied
 * by {@link WebSeedFetcher}, which the host application implements.</p>
 */
public final class WebSeedPlanner {

    private WebSeedPlanner() {
    }

    /**
     * @return the ordered file segments covering the whole of piece {@code pieceIndex}; empty if the
     *         index is out of range or the piece spans only zero-length files
     * @throws IllegalArgumentException if {@code pieceIndex} is negative
     */
    public static List<WebSeedSegment> planPiece(Torrent torrent, int pieceIndex) {
        if (pieceIndex < 0) {
            throw new IllegalArgumentException("Negative piece index: " + pieceIndex);
        }
        long chunkSize = torrent.getChunkSize();
        long totalSize = torrent.getSize();
        if (chunkSize <= 0 || totalSize <= 0) {
            return Collections.emptyList();
        }
        long pieceStart = Math.multiplyExact((long) pieceIndex, chunkSize);
        if (pieceStart >= totalSize) {
            return Collections.emptyList();
        }
        long pieceEnd = Math.min(pieceStart + chunkSize, totalSize); // exclusive

        List<WebSeedSegment> segments = new ArrayList<>(2);
        long cursor = 0;
        List<TorrentFile> files = torrent.getFiles();
        for (int i = 0; i < files.size(); i++) {
            TorrentFile file = files.get(i);
            long fileSize = file.getSize();
            long fileStart = cursor;
            long fileEnd = cursor + fileSize; // exclusive
            cursor = fileEnd;

            if (fileSize <= 0) {
                continue; // a zero-length file occupies no bytes in the piece stream
            }
            long from = Math.max(pieceStart, fileStart);
            long to = Math.min(pieceEnd, fileEnd); // exclusive
            if (from >= to) {
                continue;
            }
            segments.add(new WebSeedSegment(i, file.getPathElements(), from - fileStart, to - fileStart - 1));
        }
        return segments;
    }

    /**
     * Total number of pieces in the torrent — the count of SHA-1 hashes, equivalently
     * {@code ceil(size / chunkSize)}.
     */
    public static int pieceCount(Torrent torrent) {
        long chunkSize = torrent.getChunkSize();
        long totalSize = torrent.getSize();
        if (chunkSize <= 0 || totalSize <= 0) {
            return 0;
        }
        return Math.toIntExact((totalSize + chunkSize - 1) / chunkSize);
    }

    /** Byte length of a specific piece (the last piece may be shorter than the chunk size). */
    public static int pieceLength(Torrent torrent, int pieceIndex) {
        long chunkSize = torrent.getChunkSize();
        long totalSize = torrent.getSize();
        long start = Math.multiplyExact((long) pieceIndex, chunkSize);
        if (start >= totalSize) {
            return 0;
        }
        return Math.toIntExact(Math.min(chunkSize, totalSize - start));
    }
}
