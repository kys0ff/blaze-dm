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
import bt.metainfo.TorrentId;
import bt.metainfo.TorrentSource;
import bt.tracker.AnnounceKey;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * TOR-3 (web seeds): the piece-to-file-range mapping must reconstruct each piece byte-for-byte from
 * the concatenated file stream — including pieces that straddle a file boundary and the short final
 * piece — so the transport layer never has to reason about torrent layout.
 */
public class WebSeedPlannerTest {

    @Test
    public void singleFilePieceMapsToOneInFileSegment() {
        Torrent torrent = torrent(100, file("a.bin", 250));

        List<WebSeedSegment> piece0 = WebSeedPlanner.planPiece(torrent, 0);
        assertEquals(1, piece0.size());
        assertEquals(0, piece0.get(0).getFileIndex());
        assertEquals(0, piece0.get(0).getFromInclusive());
        assertEquals(99, piece0.get(0).getToInclusive());
        assertEquals(100, piece0.get(0).length());
    }

    @Test
    public void shortFinalPieceIsClippedToTorrentSize() {
        Torrent torrent = torrent(100, file("a.bin", 250));

        List<WebSeedSegment> last = WebSeedPlanner.planPiece(torrent, 2);
        assertEquals(1, last.size());
        assertEquals(200, last.get(0).getFromInclusive());
        assertEquals(249, last.get(0).getToInclusive());
        assertEquals(50, last.get(0).length());
        assertEquals(50, WebSeedPlanner.pieceLength(torrent, 2));
    }

    @Test
    public void pieceStraddlingFileBoundarySplitsInOrder() {
        // f0 = [0,60), f1 = [60,120); piece 0 = absolute [0,100) → f0[0..59] + f1[0..39]
        Torrent torrent = torrent(100, file("dir/a.txt", 60), file("dir/b.txt", 60));

        List<WebSeedSegment> piece0 = WebSeedPlanner.planPiece(torrent, 0);
        assertEquals(2, piece0.size());

        assertEquals(0, piece0.get(0).getFileIndex());
        assertEquals(0, piece0.get(0).getFromInclusive());
        assertEquals(59, piece0.get(0).getToInclusive());
        assertEquals(60, piece0.get(0).length());

        assertEquals(1, piece0.get(1).getFileIndex());
        assertEquals(0, piece0.get(1).getFromInclusive());   // file-relative
        assertEquals(39, piece0.get(1).getToInclusive());
        assertEquals(40, piece0.get(1).length());

        int total = piece0.stream().mapToInt(WebSeedSegment::length).sum();
        assertEquals(WebSeedPlanner.pieceLength(torrent, 0), total);
    }

    @Test
    public void laterPieceInsideSecondFileIsSingleSegment() {
        Torrent torrent = torrent(100, file("a.txt", 60), file("b.txt", 60));

        // piece 1 = absolute [100,120) → entirely within f1, at file offsets [40,59]
        List<WebSeedSegment> piece1 = WebSeedPlanner.planPiece(torrent, 1);
        assertEquals(1, piece1.size());
        assertEquals(1, piece1.get(0).getFileIndex());
        assertEquals(40, piece1.get(0).getFromInclusive());
        assertEquals(59, piece1.get(0).getToInclusive());
        assertEquals(20, piece1.get(0).length());
    }

    @Test
    public void zeroLengthFilesAreSkipped() {
        Torrent torrent = torrent(100, file("empty.txt", 0), file("data.bin", 100));

        List<WebSeedSegment> piece0 = WebSeedPlanner.planPiece(torrent, 0);
        assertEquals(1, piece0.size());
        assertEquals(1, piece0.get(0).getFileIndex());
        assertEquals(0, piece0.get(0).getFromInclusive());
        assertEquals(99, piece0.get(0).getToInclusive());
    }

    @Test
    public void outOfRangePieceYieldsNoSegments() {
        Torrent torrent = torrent(100, file("a.bin", 250));
        assertEquals(3, WebSeedPlanner.pieceCount(torrent));
        assertTrue(WebSeedPlanner.planPiece(torrent, 3).isEmpty());
    }

    private static TorrentFile file(String path, long size) {
        List<String> elements = Arrays.asList(path.split("/"));
        return new TorrentFile() {
            @Override
            public long getSize() {
                return size;
            }

            @Override
            public List<String> getPathElements() {
                return elements;
            }
        };
    }

    private static Torrent torrent(long chunkSize, TorrentFile... files) {
        List<TorrentFile> fileList = Arrays.asList(files);
        long size = Arrays.stream(files).mapToLong(TorrentFile::getSize).sum();
        return new Torrent() {
            @Override
            public TorrentSource getSource() {
                return null;
            }

            @Override
            public Optional<AnnounceKey> getAnnounceKey() {
                return Optional.empty();
            }

            @Override
            public TorrentId getTorrentId() {
                return TorrentId.fromBytes(new byte[TorrentId.length()]);
            }

            @Override
            public String getName() {
                return "torrent";
            }

            @Override
            public long getChunkSize() {
                return chunkSize;
            }

            @Override
            public Iterable<byte[]> getChunkHashes() {
                return Collections.emptyList();
            }

            @Override
            public long getSize() {
                return size;
            }

            @Override
            public List<TorrentFile> getFiles() {
                return fileList;
            }

            @Override
            public boolean isPrivate() {
                return false;
            }

            @Override
            public Optional<java.time.Instant> getCreationDate() {
                return Optional.empty();
            }

            @Override
            public Optional<String> getCreatedBy() {
                return Optional.empty();
            }
        };
    }
}
