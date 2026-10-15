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

package bt.data.file;

import bt.data.StorageUnit;
import bt.metainfo.Torrent;
import bt.metainfo.TorrentFile;
import bt.metainfo.TorrentId;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@link FileSystemStorage#locationOf(Torrent, TorrentFile)} must name exactly the file that
 * {@link FileSystemStorage#getUnit(Torrent, TorrentFile)} writes through - same normalization,
 * same root choice for single- vs multi-file torrents - so that resume fingerprints describe the
 * real bytes on disk.
 */
public class FileSystemStorageLocationTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void singleFileTorrentLocationIsWhereTheUnitWrites() throws IOException {
        Path root = tmp.newFolder("downloads").toPath();
        FileSystemStorage storage = new FileSystemStorage(root);
        TorrentFile file = file("movie.mkv", 4096);
        Torrent torrent = torrent("movie.torrent", file);

        StorageUnit unit = storage.getUnit(torrent, file);
        unit.writeBlockFully(ByteBuffer.wrap(new byte[4096]), 0);
        unit.close();

        Path location = storage.locationOf(torrent, file).orElseThrow(AssertionError::new);
        assertTrue("location must point at the written file: " + location, Files.exists(location));
        assertEquals(4096L, Files.size(location));
        assertEquals(root.resolve("movie.mkv").toAbsolutePath().normalize(), location);
    }

    @Test
    public void multiFileTorrentLocationIncludesNormalizedTorrentFolder() throws IOException {
        Path root = tmp.newFolder("downloads2").toPath();
        FileSystemStorage storage = new FileSystemStorage(root);
        TorrentFile first = file(Arrays.asList("docs", "read me.txt"), 10);
        TorrentFile second = file("video.mp4", 20);
        Torrent torrent = torrent("Big Bundle.", first, second);

        StorageUnit unit = storage.getUnit(torrent, first);
        unit.writeBlockFully(ByteBuffer.wrap(new byte[10]), 0);
        unit.close();

        Path location = storage.locationOf(torrent, first).orElseThrow(AssertionError::new);
        assertTrue("location must point at the written file: " + location, Files.exists(location));
        // trailing dot trimmed from the torrent folder name, exactly like getUnit writes
        assertEquals(root.resolve("Big Bundle").resolve("docs").resolve("read me.txt")
                .toAbsolutePath().normalize(), location);
    }

    private static TorrentFile file(String name, long size) {
        return file(Collections.singletonList(name), size);
    }

    private static TorrentFile file(List<String> pathElements, long size) {
        return new TorrentFile() {
            @Override
            public long getSize() {
                return size;
            }

            @Override
            public List<String> getPathElements() {
                return pathElements;
            }
        };
    }

    private static Torrent torrent(String name, TorrentFile... files) {
        List<TorrentFile> fileList = Arrays.asList(files);
        return new Torrent() {
            @Override
            public bt.metainfo.TorrentSource getSource() {
                return null;
            }

            @Override
            public Optional<bt.tracker.AnnounceKey> getAnnounceKey() {
                return Optional.empty();
            }

            @Override
            public TorrentId getTorrentId() {
                return TorrentId.fromBytes(new byte[TorrentId.length()]);
            }

            @Override
            public String getName() {
                return name;
            }

            @Override
            public long getChunkSize() {
                return 16384;
            }

            @Override
            public Iterable<byte[]> getChunkHashes() {
                return Collections.emptyList();
            }

            @Override
            public long getSize() {
                return fileList.stream().mapToLong(TorrentFile::getSize).sum();
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
            public Optional<Instant> getCreationDate() {
                return Optional.empty();
            }

            @Override
            public Optional<String> getCreatedBy() {
                return Optional.empty();
            }
        };
    }
}
