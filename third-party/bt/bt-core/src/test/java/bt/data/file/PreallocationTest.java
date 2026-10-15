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

import bt.data.InsufficientStorageException;
import bt.data.PreallocationMode;
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
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * TOR-2 (preallocation): each mode must do exactly what it promises, idempotently, and the
 * free-space check must reject an impossible download before a single byte of the transfer is
 * attempted - not halfway through it.
 */
public class PreallocationTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void noneCreatesNothingAndReservesNothing() throws IOException {
        Path file = tmp.newFolder("none").toPath().resolve("movie.mkv");
        Preallocation.apply(file, 4096, PreallocationMode.NONE);
        assertFalse("NONE must not touch the file system", Files.exists(file));
    }

    @Test
    public void zeroLengthFilesAreNeverTouched() throws IOException {
        Path file = tmp.newFolder("empty").toPath().resolve("readme.txt");
        Preallocation.apply(file, 0, PreallocationMode.FULL);
        assertFalse("a 0-byte torrent file must not be preallocated", Files.exists(file));
    }

    @Test
    public void sparseExtendsToFullLengthAndHolesReadAsZeros() throws IOException {
        Path dir = tmp.newFolder("sparse").toPath();
        Path file = dir.resolve("movie.mkv");
        Preallocation.apply(file, 1024 * 1024, PreallocationMode.SPARSE);

        assertTrue(Files.exists(file));
        assertEquals(1024 * 1024L, Files.size(file));
        // Whatever the platform does about holes internally, the logical content must read back
        // as zeros - that is what the SHA-1 verifier sees for regions data has not filled yet.
        byte[] head = new byte[4096];
        try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(
                file, java.nio.file.StandardOpenOption.READ)) {
            ByteBuffer buffer = ByteBuffer.wrap(head);
            ch.read(buffer, 0);
        }
        assertArrayEquals(new byte[4096], head);
    }

    @Test
    public void fullWritesZerosAcrossTheWholeLength() throws IOException {
        Path file = tmp.newFolder("full").toPath().resolve("data.bin");
        Preallocation.apply(file, 200_000, PreallocationMode.FULL);
        assertEquals(200_000L, Files.size(file));
        byte[] all = Files.readAllBytes(file);
        for (int i = 0; i < all.length; i++) {
            if (all[i] != 0) {
                fail("byte " + i + " is not zero after FULL preallocation");
            }
        }
    }

    @Test
    public void fullKeepsExistingBytesAndOnlyFillsTheMissingTail() throws IOException {
        Path file = tmp.newFolder("tail").toPath().resolve("partial.bin");
        byte[] head = new byte[1000];
        Arrays.fill(head, (byte) 0x5A);
        Files.write(file, head);

        Preallocation.apply(file, 10_000, PreallocationMode.FULL);

        assertEquals(10_000L, Files.size(file));
        byte[] all = Files.readAllBytes(file);
        for (int i = 0; i < head.length; i++) {
            assertEquals("existing byte " + i + " must survive", (byte) 0x5A, all[i]);
        }
        for (int i = head.length; i < all.length; i++) {
            assertEquals("filled byte " + i + " must be zero", (byte) 0, all[i]);
        }
    }

    @Test
    public void preallocationIsIdempotentAndLeavesMtimeAlone() throws IOException {
        Path file = tmp.newFolder("idem").toPath().resolve("movie.mkv");
        Preallocation.apply(file, 64 * 1024, PreallocationMode.SPARSE);
        FileTime before = Files.getLastModifiedTime(file);

        // The exact case a restart with preallocation + fast resume hits: already at full size,
        // so the call must not rewrite or re-touch the file (a bumped mtime would veto resume).
        Preallocation.apply(file, 64 * 1024, PreallocationMode.SPARSE);
        FileTime after = Files.getLastModifiedTime(file);
        assertEquals("second apply must not modify the file", before, after);
        assertEquals(64 * 1024L, Files.size(file));
    }

    @Test
    public void insufficientSpaceFailsBeforeCreatingAnything() throws IOException {
        Path dir = tmp.newFolder("nospace").toPath();
        Path file = dir.resolve("huge.bin");
        try {
            Preallocation.apply(file, Long.MAX_VALUE, PreallocationMode.SPARSE);
            fail("Long.MAX_VALUE bytes cannot fit on any volume");
        } catch (InsufficientStorageException expected) {
            assertTrue(expected.getMessage().contains("disk space"));
        }
        assertFalse("a rejected preallocation must leave no file behind", Files.exists(file));

        // FULL has to fail the same check before its first zero reaches the disk
        try {
            Preallocation.apply(file, Long.MAX_VALUE, PreallocationMode.FULL);
            fail("Long.MAX_VALUE bytes cannot fit on any volume");
        } catch (InsufficientStorageException expected) {
            // same path, same guarantee
        }
        assertFalse(Files.exists(file));
    }

    @Test
    public void parentFoldersAreCreatedForNestedTorrentPaths() throws IOException {
        Path file = tmp.newFolder("nested").toPath().resolve("bundle").resolve("docs").resolve("a.txt");
        Preallocation.apply(file, 128, PreallocationMode.SPARSE);
        assertTrue(Files.exists(file));
        assertEquals(128L, Files.size(file));
    }

    @Test
    public void fileSystemStorageHonorsTheModeAndDefaultStaysNone() throws IOException {
        Path root = tmp.newFolder("fs-default").toPath();
        TorrentFile entry = file("movie.mkv", 4096);
        Torrent torrent = torrent("movie.torrent", Collections.singletonList(entry));

        // default constructor: historical behavior, no eager file
        StorageUnit untouched = new FileSystemStorage(root).getUnit(torrent, entry);
        assertFalse("default FileSystemStorage must not create files up front",
                Files.exists(root.resolve("movie.mkv")));
        untouched.close();

        // opt-in: getUnit preallocates to the file's full length
        Path sparseRoot = tmp.newFolder("fs-sparse").toPath();
        FileSystemStorage storage = new FileSystemStorage(sparseRoot, 256, PreallocationMode.SPARSE);
        StorageUnit unit = storage.getUnit(torrent, entry);
        assertEquals(4096L, Files.size(sparseRoot.resolve("movie.mkv")));
        unit.close();
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

    private static Torrent torrent(String name, List<TorrentFile> fileList) {
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
