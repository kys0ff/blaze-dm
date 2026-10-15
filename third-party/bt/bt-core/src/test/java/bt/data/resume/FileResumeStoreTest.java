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
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Round-trip and corruption behavior of the file-based resume store: what saves must reload
 * byte-for-byte, and how every unusable entry must degrade to "no state" instead of throwing.
 */
public class FileResumeStoreTest {

    private static final TorrentId ID = TorrentId.fromBytes(sequence());

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void saveThenLoadRoundTripsEveryField() {
        FileResumeStore store = newStore();
        ResumeState state = sampleState();

        store.save(ID, state);
        Optional<ResumeState> loaded = store.load(ID);

        assertTrue(loaded.isPresent());
        ResumeState read = loaded.get();
        assertArrayEquals(state.getTorrentId(), read.getTorrentId());
        assertEquals(state.getPiecesTotal(), read.getPiecesTotal());
        assertEquals(state.getVerifiedPieces(), read.getVerifiedPieces());
        assertEquals(state.getSavedAtMillis(), read.getSavedAtMillis());
        assertEquals(state.getFingerprints().size(), read.getFingerprints().size());
        for (int i = 0; i < state.getFingerprints().size(); i++) {
            FileFingerprint expected = state.getFingerprints().get(i);
            FileFingerprint actual = read.getFingerprints().get(i);
            assertEquals(expected.getPath(), actual.getPath());
            assertEquals(expected.getSize(), actual.getSize());
            assertEquals(expected.getModifiedMillis(), actual.getModifiedMillis());
        }
    }

    @Test
    public void saveReplacesPreviousEntry() {
        FileResumeStore store = newStore();
        store.save(ID, stateWithBits(bitSet(0, 1)));
        store.save(ID, stateWithBits(bitSet(5)));

        ResumeState read = store.load(ID).orElseThrow(AssertionError::new);
        assertEquals(stateWithBits(bitSet(5)).getVerifiedPieces(), read.getVerifiedPieces());
    }

    @Test
    public void missingEntryLoadsAsEmpty() {
        assertFalse(newStore().load(ID).isPresent());
    }

    @Test
    public void deleteRemovesEntryAndToleratesAbsence() {
        FileResumeStore store = newStore();
        store.save(ID, sampleState());
        store.delete(ID);
        assertFalse(store.load(ID).isPresent());
        store.delete(ID); // absent is not an error
    }

    @Test
    public void garbageFileLoadsAsEmpty() throws IOException {
        Path dir = storeDir();
        Files.write(dir.resolve(ID + ".resume"), new byte[]{1, 2, 3, 4, 5});
        assertFalse(newStore().load(ID).isPresent());
    }

    @Test
    public void truncatedFileLoadsAsEmpty() {
        FileResumeStore store = newStore();
        store.save(ID, sampleState());
        try {
            Path file = storeDir().resolve(ID + ".resume");
            byte[] all = Files.readAllBytes(file);
            Files.write(file, Arrays.copyOf(all, all.length / 2));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        assertFalse(store.load(ID).isPresent());
    }

    @Test
    public void entryUnderWrongTorrentIdLoadsAsEmpty() throws IOException {
        // a valid blob for ID, pasted under another torrent ID's file name
        FileResumeStore store = newStore();
        store.save(ID, sampleState());
        TorrentId other = TorrentId.fromBytes(new byte[TorrentId.length()]);
        Files.copy(storeDir().resolve(ID + ".resume"), storeDir().resolve(other + ".resume"));
        assertFalse(store.load(other).isPresent());
    }

    @Test
    public void bitsBeyondPieceCountLoadAsEmpty() throws IOException {
        // hand-crafted blob: piecesTotal = 4 but the bitmask claims a piece beyond that
        byte[] bitmask = new byte[2];
        bitmask[1] = 0x40; // bit 14 (byte 1, bit 6 from LSB) - way past the declared 4 pieces
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(storeDir().resolve(ID + ".resume")))) {
            out.writeInt(0x42545231);
            out.writeInt(1);
            out.write(ID.getBytes());
            out.writeInt(4);
            out.writeInt(bitmask.length);
            out.write(bitmask);
            out.writeLong(1234L);
            out.writeInt(0);
        }
        assertFalse(newStore().load(ID).isPresent());
    }

    private FileResumeStore newStore() {
        return new FileResumeStore(storeDir());
    }

    private Path storeDir() {
        try {
            // some tests write raw blobs before the store ever saves, so the dir must exist
            return Files.createDirectories(tmp.getRoot().toPath().resolve("resume"));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[] sequence() {
        byte[] id = new byte[TorrentId.length()];
        for (int i = 0; i < id.length; i++) id[i] = (byte) i;
        return id;
    }

    private static BitSet bitSet(int... indices) {
        BitSet bits = new BitSet();
        for (int i : indices) bits.set(i);
        return bits;
    }

    private static List<FileFingerprint> fingerprints() {
        return Collections.singletonList(new FileFingerprint("C:/data/movie.mkv", 123456789L, 1700000000000L));
    }

    private static ResumeState sampleState() {
        return new ResumeState(ID.getBytes(), 42, bitSet(0, 5, 41), fingerprints(), 1700000000111L);
    }

    private static ResumeState stateWithBits(BitSet bits) {
        return new ResumeState(ID.getBytes(), 42, bits, fingerprints(), 1700000000000L);
    }
}
