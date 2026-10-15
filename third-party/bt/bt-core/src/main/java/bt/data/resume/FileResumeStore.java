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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Optional;

/**
 * File-system backed {@link ResumeStore}: one small binary file per torrent, named by the
 * hex torrent ID inside a single resume directory.
 *
 * <p>Writes go to a temporary sibling and are moved over the target atomically
 * ({@link StandardCopyOption#ATOMIC_MOVE} with replacement), so a crash mid-save can only ever
 * leave the previous complete entry or no entry at all - never a half-written one that might
 * be misread as fresh state. Reads treat every anomaly (missing, truncated, wrong magic,
 * unknown version, inconsistent counts) as "no usable state", never as an error.</p>
 *
 * <p><b>Note that this class is not a part of the public API and is a subject to change.</b></p>
 *
 * @since 1.10 (blaze fork)
 */
public class FileResumeStore implements ResumeStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(FileResumeStore.class);

    // "BTR1" - Bt Resume, format version 1
    private static final int MAGIC = 0x42545231;
    private static final int VERSION = 1;

    private static final String FILE_SUFFIX = ".resume";
    private static final String TMP_SUFFIX = ".resume.tmp";

    private final Path directory;

    public FileResumeStore(Path directory) {
        this.directory = directory;
    }

    @Override
    public Optional<ResumeState> load(TorrentId torrentId) {
        Path file = directory.resolve(torrentId + FILE_SUFFIX);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try (DataInputStream in = new DataInputStream(Files.newInputStream(file))) {
            int magic = in.readInt();
            int version = in.readInt();
            if (magic != MAGIC || version != VERSION) {
                LOGGER.debug("Ignoring resume file with unexpected header (magic={}, version={}): {}",
                        magic, version, file);
                return Optional.empty();
            }

            byte[] id = new byte[TorrentId.length()];
            in.readFully(id);
            if (!java.util.Arrays.equals(id, torrentId.getBytes())) {
                LOGGER.debug("Ignoring resume file with mismatched torrent ID: {}", file);
                return Optional.empty();
            }

            int piecesTotal = in.readInt();
            if (piecesTotal <= 0) {
                LOGGER.debug("Ignoring resume file with illegal pieces total {}: {}", piecesTotal, file);
                return Optional.empty();
            }

            int bitmaskLength = in.readInt();
            if (bitmaskLength < 0 || bitmaskLength > (piecesTotal + 7) / 8 + 8) {
                LOGGER.debug("Ignoring resume file with implausible bitmask length {}: {}", bitmaskLength, file);
                return Optional.empty();
            }
            byte[] bitmaskBytes = new byte[bitmaskLength];
            in.readFully(bitmaskBytes);
            BitSet verified = BitSet.valueOf(bitmaskBytes);
            if (verified.previousSetBit(Integer.MAX_VALUE) >= piecesTotal) {
                LOGGER.debug("Ignoring resume file with bits set beyond piece count {}: {}", piecesTotal, file);
                return Optional.empty();
            }

            long savedAt = in.readLong();

            int fileCount = in.readInt();
            if (fileCount < 0 || fileCount > 1_000_000) {
                LOGGER.debug("Ignoring resume file with implausible file count {}: {}", fileCount, file);
                return Optional.empty();
            }
            List<FileFingerprint> fingerprints = new ArrayList<>(fileCount);
            for (int i = 0; i < fileCount; i++) {
                String path = in.readUTF();
                long size = in.readLong();
                long modified = in.readLong();
                fingerprints.add(new FileFingerprint(path, size, modified));
            }

            return Optional.of(new ResumeState(id, piecesTotal, verified, fingerprints, savedAt));
        } catch (IOException | IllegalArgumentException e) {
            // EOFException, malformed UTF, unreadable file - all mean "no usable state"
            LOGGER.debug("Failed to read resume file " + file + "; falling back to full verification", e);
            return Optional.empty();
        }
    }

    @Override
    public void save(TorrentId torrentId, ResumeState state) {
        Path target = directory.resolve(torrentId + FILE_SUFFIX);
        Path tmp = directory.resolve(torrentId + TMP_SUFFIX);
        try {
            Files.createDirectories(directory);
            try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(tmp))) {
                out.writeInt(MAGIC);
                out.writeInt(VERSION);
                out.write(state.getTorrentId());
                out.writeInt(state.getPiecesTotal());
                byte[] bitmaskBytes = state.getVerifiedPieces().toByteArray();
                out.writeInt(bitmaskBytes.length);
                out.write(bitmaskBytes);
                out.writeLong(state.getSavedAtMillis());
                List<FileFingerprint> fingerprints = state.getFingerprints();
                out.writeInt(fingerprints.size());
                for (FileFingerprint fingerprint : fingerprints) {
                    out.writeUTF(fingerprint.getPath());
                    out.writeLong(fingerprint.getSize());
                    out.writeLong(fingerprint.getModifiedMillis());
                }
                out.flush();
            }
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                // some filesystems refuse atomic moves onto an existing file; a plain replace keeps
                // the same content, and the worst case of a crash here is the *previous* entry
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            deleteQuietly(tmp);
            throw new UncheckedIOException("Failed to save resume state for torrent " + torrentId, e);
        }
    }

    @Override
    public void delete(TorrentId torrentId) {
        try {
            Files.deleteIfExists(directory.resolve(torrentId + FILE_SUFFIX));
        } catch (IOException e) {
            LOGGER.debug("Failed to delete resume file for torrent " + torrentId, e);
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // the tmp file is the only thing at stake; leaving it is mere litter
        }
    }
}
