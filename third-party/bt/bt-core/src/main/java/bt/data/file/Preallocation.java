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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Reserves a file's full length before any data arrives, per {@link PreallocationMode}. Shared by
 * this library's {@link FileSystemStorage} and (through its own storage) any host application that
 * wants identical semantics, so the free-space rule and the platform behavior are documented once.
 *
 * <p>Every mode is <b>idempotent</b>: a file already at or above its target length is left exactly
 * as it is. That matters twice over — it keeps an existing seed's bytes (and their modification
 * time, which the fast-resume fingerprint gate reads) untouched, and it makes re-opening a partial
 * download a no-op rather than a re-reservation.
 *
 * @since 1.10 (blaze fork)
 */
public final class Preallocation {

    /** Zero-fill granularity for {@link PreallocationMode#FULL}; big enough to amortize syscalls. */
    private static final int ZERO_FILL_CHUNK_BYTES = 64 * 1024;

    private Preallocation() {
    }

    /**
     * Applies {@code mode} to {@code file}, sizing it to {@code size}.
     *
     * @throws InsufficientStorageException when the target volume cannot hold the remaining bytes
     *                                      (checked for SPARSE and FULL before anything is written)
     * @throws UncheckedIOException         on any other I/O failure creating or extending the file
     */
    public static void apply(Path file, long size, PreallocationMode mode) {
        if (mode == PreallocationMode.NONE || size <= 0) {
            return;
        }

        long current = sizeOf(file);
        long needed = size - current;
        if (needed <= 0) {
            return;
        }

        try {
            ensureParentExists(file);
            checkFreeSpace(file, needed);

            switch (mode) {
                case SPARSE:
                    extendSparse(file, size);
                    break;
                case FULL:
                    zeroFill(file, current, size);
                    break;
                default:
                    // NONE returned above; unreachable, but keep the switch exhaustive.
                    break;
            }
        } catch (InsufficientStorageException e) {
            throw e;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to preallocate " + file, e);
        }
    }

    private static void extendSparse(Path file, long size) throws IOException {
        // Writing a single zero byte at the last offset extends the file to `size`; everything
        // before it is whatever the filesystem makes of the gap (a hole on ext4/APFS, allocated
        // clusters on NTFS - see PreallocationMode for why the free-space check covers both).
        try (FileChannel channel = openForExtension(file)) {
            channel.write(ByteBuffer.allocate(1), size - 1);
        }
    }

    private static void zeroFill(Path file, long from, long size) throws IOException {
        try (FileChannel channel = openForExtension(file)) {
            ByteBuffer zeros = ByteBuffer.allocate(ZERO_FILL_CHUNK_BYTES);
            long offset = from;
            while (offset < size) {
                int chunk = (int) Math.min(zeros.capacity(), size - offset);
                int written = 0;
                while (written < chunk) {
                    zeros.clear();
                    zeros.limit(chunk - written);
                    int progress = channel.write(zeros, offset + written);
                    if (progress <= 0) {
                        throw new IOException("Zero-fill made no progress writing at " + file);
                    }
                    written += progress;
                }
                offset += chunk;
            }
        }
    }

    private static FileChannel openForExtension(Path file) throws IOException {
        return FileChannel.open(file,
                StandardOpenOption.WRITE, StandardOpenOption.CREATE);
    }

    private static void ensureParentExists(Path file) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    private static void checkFreeSpace(Path file, long needed) throws IOException {
        Path probe = file.getParent();
        if (probe == null) {
            return;
        }
        FileStore store = Files.getFileStore(probe);
        long usable = store.getUsableSpace();
        if (usable < needed) {
            throw new InsufficientStorageException(
                    "Not enough disk space on " + store + " to write " + needed
                            + " bytes (only " + usable + " bytes available)");
        }
    }

    private static long sizeOf(Path file) {
        try {
            return Files.exists(file) ? Files.size(file) : 0L;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read the size of " + file, e);
        }
    }
}
