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

import bt.data.ChunkDescriptor;
import bt.data.ChunkVerifier;
import bt.data.LocalBitfield;
import bt.data.Storage;
import bt.metainfo.Torrent;
import bt.metainfo.TorrentFile;
import bt.metainfo.TorrentId;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Behavior of the fast-resume decorator: what it trusts, what it refuses, and in which order it
 * acts. The delegate is a recording fake so the test can see exactly which pieces were spot-checked,
 * which bits were pre-applied when the delegate fell back to hashing, and whether that fallback
 * ran through the context-aware (full) entry point.
 */
public class FastResumeVerifierTest {

    private static final int PIECES = 5;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Torrent torrent;
    private List<ChunkDescriptor> chunks;
    private RecordingVerifier delegate;
    private Path dataDir;

    /** Torrent files: "a.bin" and "b.bin", resolved by name under {@link #dataDir}. */
    private Storage locationAwareStorage;
    private final Storage blindStorage = new Storage() {
        @Override
        public bt.data.StorageUnit getUnit(Torrent torrent, TorrentFile torrentFile) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void flush() {
        }
        // locationOf stays the interface default: Optional.empty()
    };

    @Before
    public void setUp() {
        dataDir = tmp.getRoot().toPath();
        torrent = fakeTorrent(TorrentId.fromBytes(idBytes(1)),
                fakeFile("a.bin", 3000), fakeFile("b.bin", 2000));
        chunks = new ArrayList<>();
        for (int i = 0; i < PIECES; i++) chunks.add(new FakeChunk(i));
        delegate = new RecordingVerifier(chunks);
        locationAwareStorage = nameKeyedStorage();
    }

    // ---------------------------------------------------------------------------------------
    // load side
    // ---------------------------------------------------------------------------------------

    @Test
    public void noSavedStateRunsPlainFullVerification() {
        FastResumeVerifier verifier = newVerifier(2, locationAwareStorageDir());
        LocalBitfield bitfield = newBitfield();

        boolean complete = verifier.verify(chunks, bitfield, torrent, locationAwareStorage);

        assertTrue(complete);
        assertTrue(delegate.ops.contains("context-full"));
        assertEquals(Arrays.asList(0, 1, 2, 3, 4), delegate.hashed);
        assertNoSpotChecks();
    }

    @Test
    public void matchingFingerprintsAndPassingSpotChecksTrustTheSavedBits() throws IOException {
        writeDataFile("a.bin", 3000);
        writeDataFile("b.bin", 2000);
        saveClosedState(new int[]{0, 1, 2}, locationAwareStorage);

        FastResumeVerifier verifier = newVerifier(2, locationAwareStorageDir());
        LocalBitfield bitfield = newBitfield();

        boolean complete = verifier.verify(chunks, bitfield, torrent, locationAwareStorage);

        assertTrue(complete);
        // only the two pieces not covered by the resume state were hashed
        assertEquals(Arrays.asList(3, 4), delegate.hashed);
        assertFalse(delegate.ops.contains("context-full"));
        // and the trusted bits were applied before the delegate ever ran
        assertTrue(bitfield.isVerified(0));
        assertTrue(bitfield.isVerified(1));
        assertTrue(bitfield.isVerified(2));
    }

    @Test
    public void spotChecksRunBeforeAnyBitIsApplied() throws IOException {
        writeDataFile("a.bin", 3000);
        writeDataFile("b.bin", 2000);
        saveClosedState(new int[]{0, 1, 2}, locationAwareStorage);
        delegate.bad.add(2); // a spot check will eventually re-hash a secretly-bad trusted piece

        FastResumeVerifier verifier = newVerifier(3, locationAwareStorageDir()); // checks all 3
        LocalBitfield bitfield = newBitfield();

        boolean complete = verifier.verify(chunks, bitfield, torrent, locationAwareStorage);

        assertFalse(complete);
        // The failed state left no applied bits behind: the fallback full path entered with an
        // empty bitfield ("full:0") right after the context-aware entry, and every candidate
        // trusted piece was spot-checked before that fallback began.
        int contextFull = delegate.ops.indexOf("context-full");
        assertTrue(contextFull >= 0);
        assertEquals("full:0", delegate.ops.get(contextFull + 1));
        // Spot checks run first and bail at the first mismatch: the op right before the fallback
        // is the failing spot of the secretly-bad piece 2 (shuffle order decides how many came
        // before it - at least that one, never more than the three trusted candidates).
        assertEquals("spot:2", delegate.ops.get(contextFull - 1));
        long spotsBeforeFallback = delegate.ops.subList(0, contextFull).stream()
                .filter(op -> op.startsWith("spot:")).count();
        assertTrue("spot count out of range: " + spotsBeforeFallback,
                spotsBeforeFallback >= 1 && spotsBeforeFallback <= 3);
        // the fallback hashed everything
        assertEquals(Arrays.asList(0, 1, 2, 3, 4), delegate.hashed);
        // after the full re-hash the good pieces verified but the secretly-bad one did not
        assertTrue(bitfield.isVerified(0));
        assertTrue(bitfield.isVerified(1));
        assertFalse(bitfield.isVerified(2));
        assertTrue(bitfield.isVerified(3));
        assertTrue(bitfield.isVerified(4));
        // no piece of the rejected state was ever applied
        assertEquals(4, bitfield.getPiecesComplete());
    }

    private void assertNoSpotChecks() {
        for (String op : delegate.ops) {
            assertFalse("unexpected spot check: " + op, op.startsWith("spot:"));
        }
    }

    @Test
    public void changedFileSizeRejectsTheSavedState() throws IOException {
        writeDataFile("a.bin", 3000);
        writeDataFile("b.bin", 2000);
        saveClosedState(new int[]{0, 1, 2}, locationAwareStorage);
        writeDataFile("a.bin", 2500); // resized behind our back

        FastResumeVerifier verifier = newVerifier(2, locationAwareStorageDir());
        LocalBitfield bitfield = newBitfield();

        verifier.verify(chunks, bitfield, torrent, locationAwareStorage);

        assertTrue(delegate.ops.contains("context-full"));
        assertEquals(Arrays.asList(0, 1, 2, 3, 4), delegate.hashed);
    }

    @Test
    public void changedModificationTimeRejectsTheSavedState() throws IOException {
        writeDataFile("a.bin", 3000);
        writeDataFile("b.bin", 2000);
        saveClosedState(new int[]{0, 1, 2}, locationAwareStorage);
        Files.setLastModifiedTime(dataDir.resolve("a.bin"),
                java.nio.file.attribute.FileTime.from(Instant.now().plusSeconds(120)));

        FastResumeVerifier verifier = newVerifier(2, locationAwareStorageDir());
        LocalBitfield bitfield = newBitfield();

        verifier.verify(chunks, bitfield, torrent, locationAwareStorage);

        assertTrue(delegate.ops.contains("context-full"));
    }

    @Test
    public void disappearedFileRejectsTheSavedState() throws IOException {
        writeDataFile("a.bin", 3000);
        writeDataFile("b.bin", 2000);
        saveClosedState(new int[]{0, 1, 2}, locationAwareStorage);
        Files.delete(dataDir.resolve("b.bin"));

        FastResumeVerifier verifier = newVerifier(2, locationAwareStorageDir());
        LocalBitfield bitfield = newBitfield();

        verifier.verify(chunks, bitfield, torrent, locationAwareStorage);

        assertTrue(delegate.ops.contains("context-full"));
    }

    @Test
    public void pieceCountMismatchRejectsTheSavedState() throws IOException {
        writeDataFile("a.bin", 3000);
        writeDataFile("b.bin", 2000);
        // state claims six pieces for a five-piece torrent
        ResumeState state = new ResumeState(torrent.getTorrentId().getBytes(), PIECES + 1,
                bitSet(0, 1, 2), currentFingerprints(), System.currentTimeMillis());
        new FileResumeStore(locationAwareStorageDir()).save(torrent.getTorrentId(), state);

        FastResumeVerifier verifier = newVerifier(2, locationAwareStorageDir());
        LocalBitfield bitfield = newBitfield();

        verifier.verify(chunks, bitfield, torrent, locationAwareStorage);

        assertTrue(delegate.ops.contains("context-full"));
    }

    @Test
    public void storageWithoutLocationsDisablesTrust() throws IOException {
        writeDataFile("a.bin", 3000);
        writeDataFile("b.bin", 2000);
        saveClosedState(new int[]{0, 1, 2}, locationAwareStorage);

        FastResumeVerifier verifier = newVerifier(2, locationAwareStorageDir());
        LocalBitfield bitfield = newBitfield();

        verifier.verify(chunks, bitfield, torrent, blindStorage);

        assertTrue(delegate.ops.contains("context-full"));
    }

    @Test
    public void zeroSpotCheckCountTrustsTheFingerprintGateAlone() throws IOException {
        writeDataFile("a.bin", 3000);
        writeDataFile("b.bin", 2000);
        saveClosedState(new int[]{0, 1, 2}, locationAwareStorage);

        FastResumeVerifier verifier = newVerifier(0, locationAwareStorageDir());
        LocalBitfield bitfield = newBitfield();

        verifier.verify(chunks, bitfield, torrent, locationAwareStorage);

        assertNoSpotChecks();
        assertEquals(Arrays.asList(3, 4), delegate.hashed);
    }

    // ---------------------------------------------------------------------------------------
    // save side
    // ---------------------------------------------------------------------------------------

    @Test
    public void cleanClosePersistsBitsWithFreshFingerprints() throws IOException {
        writeDataFile("a.bin", 3000);
        // "b.bin" deliberately never created: a not-yet-started file, recorded as MISSING
        LocalBitfield bitfield = newBitfield();
        bitfield.markLocalPieceVerified(0);
        bitfield.markLocalPieceVerified(2);

        FastResumeVerifier verifier = newVerifier(2, locationAwareStorageDir());
        verifier.onTorrentClosed(torrent, locationAwareStorage, bitfield);

        ResumeState saved = new FileResumeStore(locationAwareStorageDir())
                .load(torrent.getTorrentId()).orElseThrow(AssertionError::new);
        assertEquals(PIECES, saved.getPiecesTotal());
        BitSet bits = saved.getVerifiedPieces();
        assertTrue(bits.get(0));
        assertFalse(bits.get(1));
        assertTrue(bits.get(2));

        List<FileFingerprint> fps = saved.getFingerprints();
        assertEquals(2, fps.size());
        assertEquals(dataDir.resolve("a.bin").toAbsolutePath().normalize().toString(), fps.get(0).getPath());
        assertEquals(3000, fps.get(0).getSize());
        assertEquals(Files.getLastModifiedTime(dataDir.resolve("a.bin")).toMillis(), fps.get(0).getModifiedMillis());
        assertEquals(dataDir.resolve("b.bin").toAbsolutePath().normalize().toString(), fps.get(1).getPath());
        assertEquals(FileFingerprint.MISSING, fps.get(1).getSize());
    }

    @Test
    public void cleanCloseWithNothingVerifiedClearsTheEntry() throws IOException {
        writeDataFile("a.bin", 3000);
        writeDataFile("b.bin", 2000);
        saveClosedState(new int[]{0}, locationAwareStorage);

        FastResumeVerifier verifier = newVerifier(2, locationAwareStorageDir());
        verifier.onTorrentClosed(torrent, locationAwareStorage, newBitfield());

        assertFalse(new FileResumeStore(locationAwareStorageDir())
                .load(torrent.getTorrentId()).isPresent());
    }

    @Test
    public void cleanCloseAgainstBlindStorageSavesNothing() throws IOException {
        LocalBitfield bitfield = newBitfield();
        bitfield.markLocalPieceVerified(0);

        FastResumeVerifier verifier = newVerifier(2, locationAwareStorageDir());
        verifier.onTorrentClosed(torrent, blindStorage, bitfield);

        assertFalse(new FileResumeStore(locationAwareStorageDir())
                .load(torrent.getTorrentId()).isPresent());
    }

    // ---------------------------------------------------------------------------------------
    // fixtures
    // ---------------------------------------------------------------------------------------

    private FastResumeVerifier newVerifier(int spotCheckCount, Path resumeDir) {
        return new FastResumeVerifier(delegate, new FileResumeStore(resumeDir),
                spotCheckCount, new Random(0xBEEF));
    }

    private Path locationAwareStorageDir() {
        return tmp.getRoot().toPath().resolve("resume");
    }

    /** Saves a resume state for the given verified pieces using the real store + real file stats. */
    private void saveClosedState(int[] verifiedPieces, Storage storage) {
        LocalBitfield bitfield = newBitfield();
        for (int piece : verifiedPieces) {
            bitfield.markLocalPieceVerified(piece);
        }
        // exercise the production save path itself
        newVerifier(0, locationAwareStorageDir()).onTorrentClosed(torrent, storage, bitfield);
        // the save above persisted the pieces that were verified at that moment; nothing else
    }

    private List<FileFingerprint> currentFingerprints() {
        List<FileFingerprint> fps = new ArrayList<>();
        for (TorrentFile file : torrent.getFiles()) {
            Path path = dataDir.resolve(file.getPathElements().get(0)).toAbsolutePath().normalize();
            try {
                if (Files.exists(path)) {
                    fps.add(new FileFingerprint(path.toString(), Files.size(path),
                            Files.getLastModifiedTime(path).toMillis()));
                } else {
                    fps.add(new FileFingerprint(path.toString(), FileFingerprint.MISSING, 0));
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return fps;
    }

    private void writeDataFile(String name, int sizeBytes) throws IOException {
        byte[] content = new byte[sizeBytes];
        Arrays.fill(content, (byte) 0x5A);
        Files.write(dataDir.resolve(name), content);
    }

    private static byte[] idBytes(int seed) {
        byte[] id = new byte[TorrentId.length()];
        for (int i = 0; i < id.length; i++) id[i] = (byte) (seed + i);
        return id;
    }

    private static BitSet bitSet(int... indices) {
        BitSet bits = new BitSet();
        for (int i : indices) bits.set(i);
        return bits;
    }

    private static LocalBitfield newBitfield() {
        return new LocalBitfield(PIECES, null) {
            @Override
            protected void fileFinishedCallback(TorrentFile tf) {
            }
        };
    }

    private static TorrentFile fakeFile(String name, long size) {
        return new TorrentFile() {
            @Override
            public long getSize() {
                return size;
            }

            @Override
            public List<String> getPathElements() {
                return Collections.singletonList(name);
            }
        };
    }

    private static Torrent fakeTorrent(TorrentId id, TorrentFile... files) {
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
                return id;
            }

            @Override
            public String getName() {
                return "fake.torrent";
            }

            @Override
            public long getChunkSize() {
                return 1024;
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

    /** Maps each fake torrent file to {@code dataDir/<its single path element>}. */
    private Storage nameKeyedStorage() {
        Map<String, Path> byName = new HashMap<>();
        for (TorrentFile file : torrent.getFiles()) {
            byName.put(String.join("/", file.getPathElements()), dataDir.resolve(file.getPathElements().get(0)));
        }
        return new Storage() {
            @Override
            public bt.data.StorageUnit getUnit(Torrent t, TorrentFile torrentFile) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<Path> locationOf(Torrent t, TorrentFile torrentFile) {
                return Optional.ofNullable(byName.get(String.join("/", torrentFile.getPathElements())));
            }

            @Override
            public void flush() {
            }
        };
    }

    /** Minimal chunk: identity only, everything else is the fake delegate's business. */
    private static final class FakeChunk implements ChunkDescriptor {
        final int index;

        FakeChunk(int index) {
            this.index = index;
        }

        @Override
        public byte[] getChecksum() {
            throw new UnsupportedOperationException();
        }

        @Override
        public bt.data.DataRange getData() {
            throw new UnsupportedOperationException();
        }

        @Override
        public int blockCount() {
            throw new UnsupportedOperationException();
        }

        @Override
        public long length() {
            throw new UnsupportedOperationException();
        }

        @Override
        public long blockSize() {
            throw new UnsupportedOperationException();
        }

        @Override
        public long lastBlockSize() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isPresent(int blockIndex) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isComplete() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isEmpty() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void clear() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String toString() {
            return "chunk-" + index;
        }
    }

    /**
     * Records every interaction: spot checks ("spot:N"), the plain full verify ("full:K" with the
     * number of already-verified pieces at entry), and the context-aware fallback
     * ("context-full"). {@code bad} pieces fail verification.
     */
    private static final class RecordingVerifier implements ChunkVerifier {
        final List<ChunkDescriptor> chunks;
        final List<String> ops = new ArrayList<>();
        final List<Integer> hashed = new ArrayList<>();
        final java.util.Set<Integer> bad = new java.util.HashSet<>();

        RecordingVerifier(List<ChunkDescriptor> chunks) {
            this.chunks = chunks;
        }

        @Override
        public boolean verify(List<ChunkDescriptor> chunks, LocalBitfield bitfield) {
            ops.add("full:" + countVerified(bitfield));
            for (int i = 0; i < chunks.size(); i++) {
                if (bitfield.isVerified(i)) continue;
                hashed.add(i);
                if (!bad.contains(i)) {
                    bitfield.markLocalPieceVerified(i);
                }
            }
            return bitfield.getPiecesRemaining() == 0;
        }

        @Override
        public boolean verify(List<ChunkDescriptor> chunks, LocalBitfield bitfield,
                              Torrent torrent, Storage storage) {
            ops.add("context-full");
            return verify(chunks, bitfield);
        }

        @Override
        public boolean verify(ChunkDescriptor chunk) {
            int index = chunks.indexOf(chunk);
            ops.add("spot:" + index);
            return index >= 0 && !bad.contains(index);
        }

        @Override
        public boolean verifyIfPresent(ChunkDescriptor chunk) {
            return verify(chunk);
        }

        private static int countVerified(LocalBitfield bitfield) {
            int count = 0;
            for (int i = 0; i < bitfield.getPiecesTotal(); i++) {
                if (bitfield.isVerified(i)) count++;
            }
            return count;
        }
    }
}
