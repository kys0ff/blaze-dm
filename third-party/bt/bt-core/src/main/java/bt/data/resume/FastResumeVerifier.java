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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Random;

/**
 * Verification decorator that turns the full re-hash of torrent data on every restart into a
 * cheap trust-then-spot-check of a persisted {@link ResumeState}.
 *
 * <p>On session close ({@link #onTorrentClosed(Torrent, Storage, LocalBitfield)}) it saves the
 * verified-piece bitfield together with the size and modification time of every torrent file.
 * On the next start the saved state is only trusted when <em>all</em> of the following hold,
 * and any single failure means the ordinary full re-hash, exactly as before this feature:</p>
 * <ul>
 *     <li>a resume entry exists for this torrent ID and is internally consistent
 *         (piece count matches the metainfo);</li>
 *     <li>the storage can report file locations, and every file still matches its recorded
 *         path, size and (for non-empty files) modification time;</li>
 *     <li>a configurable number of random pieces claimed as verified are re-hashed right now
 *         and match their digest.</li>
 * </ul>
 *
 * <p>The spot-check runs <em>before</em> any persisted bit is applied to the live bitfield, so
 * a rejected state leaves no trace to unwind. An accepted state only marks pieces verified -
 * the delegate still hashes everything else, which keeps the unverified-and-missing data on the
 * ordinary "recheck, then download what is absent" path. Stale state from a crash is impossible
 * to mistake for fresh state because it is only ever written on a clean close; and even a
 * same-size same-mtime content edit is caught by the spot-check with probability
 * 1 - (1 - bad/total)^checks.</p>
 *
 * <p><b>Note that this class is not a part of the public API and is a subject to change.</b></p>
 *
 * @since 1.10 (blaze fork)
 */
public class FastResumeVerifier implements ChunkVerifier {

    private static final Logger LOGGER = LoggerFactory.getLogger(FastResumeVerifier.class);

    private final ChunkVerifier delegate;
    private final ResumeStore store;
    private final int spotCheckCount;
    private final Random random;

    public FastResumeVerifier(ChunkVerifier delegate, ResumeStore store, int spotCheckCount) {
        this(delegate, store, spotCheckCount, new Random());
    }

    /** Seedable variant for tests; production code uses {@link #FastResumeVerifier(ChunkVerifier, ResumeStore, int)}. */
    public FastResumeVerifier(ChunkVerifier delegate, ResumeStore store, int spotCheckCount, Random random) {
        if (spotCheckCount < 0) {
            throw new IllegalArgumentException("spotCheckCount must be >= 0, got " + spotCheckCount);
        }
        this.delegate = delegate;
        this.store = store;
        this.spotCheckCount = spotCheckCount;
        this.random = random;
    }

    @Override
    public boolean verify(List<ChunkDescriptor> chunks, LocalBitfield bitfield) {
        // No torrent/storage context means no way to fingerprint the files - hash everything.
        return delegate.verify(chunks, bitfield);
    }

    @Override
    public boolean verify(List<ChunkDescriptor> chunks, LocalBitfield bitfield, Torrent torrent, Storage storage) {
        int resumed = tryResumeVerification(chunks, bitfield, torrent, storage);
        if (resumed >= 0) {
            LOGGER.info("Fast resume for '{}': {} of {} pieces trusted from saved state, re-hashing the rest",
                    torrent.getName(), resumed, chunks.size());
            // The delegate skips already-verified chunks, so only the remaining ones are hashed.
            return delegate.verify(chunks, bitfield);
        }
        return delegate.verify(chunks, bitfield, torrent, storage);
    }

    /**
     * Validates the saved state and, if it is trustworthy, marks its verified pieces on the
     * bitfield. Leaves the bitfield untouched on every rejection path.
     *
     * @return number of pieces trusted from the saved state, or -1 when a full re-hash is needed
     */
    private int tryResumeVerification(List<ChunkDescriptor> chunks, LocalBitfield bitfield,
                                      Torrent torrent, Storage storage) {
        try {
            ResumeState state = store.load(torrent.getTorrentId()).orElse(null);
            if (state == null) {
                return -1;
            }
            if (state.getPiecesTotal() != chunks.size()) {
                LOGGER.debug("Resume state piece count {} does not match torrent chunk count {}",
                        state.getPiecesTotal(), chunks.size());
                return -1;
            }
            BitSet verified = state.getVerifiedPieces();
            if (verified.isEmpty()) {
                return -1;
            }
            if (!fingerprintsMatch(state, torrent, storage)) {
                return -1;
            }
            // Spot-check before applying anything: a failed check must leave no bits to unwind.
            List<Integer> candidates = setBits(verified, chunks.size());
            if (!spotChecksPass(candidates, chunks)) {
                return -1;
            }
            for (Integer pieceIndex : candidates) {
                bitfield.markLocalPieceVerified(pieceIndex);
            }
            return candidates.size();
        } catch (RuntimeException e) {
            LOGGER.warn("Resume check failed unexpectedly for torrent " + torrent.getTorrentId()
                    + "; falling back to full verification", e);
            return -1;
        }
    }

    private boolean fingerprintsMatch(ResumeState state, Torrent torrent, Storage storage) {
        List<FileFingerprint> saved = state.getFingerprints();
        List<TorrentFile> files = torrent.getFiles();
        if (saved.size() != files.size()) {
            LOGGER.debug("Resume state covers {} files but torrent has {}", saved.size(), files.size());
            return false;
        }
        for (int i = 0; i < files.size(); i++) {
            Optional<Path> location = storage.locationOf(torrent, files.get(i));
            if (!location.isPresent()) {
                LOGGER.debug("Storage cannot report file locations; cannot trust resume state");
                return false;
            }
            Path path = location.get();
            FileFingerprint fingerprint = saved.get(i);
            if (!path.toAbsolutePath().normalize().toString().equals(fingerprint.getPath())) {
                LOGGER.debug("File moved ({} vs {}); resume state rejected", path, fingerprint.getPath());
                return false;
            }
            try {
                boolean exists = Files.exists(path);
                if (fingerprint.getSize() == FileFingerprint.MISSING) {
                    if (exists) {
                        LOGGER.debug("File {} appeared since the resume state was saved; state rejected", path);
                        return false;
                    }
                    continue;
                }
                if (!exists) {
                    LOGGER.debug("File {} disappeared since the resume state was saved; state rejected", path);
                    return false;
                }
                if (Files.size(path) != fingerprint.getSize()) {
                    LOGGER.debug("File {} changed size ({} vs {}); resume state rejected",
                            path, Files.size(path), fingerprint.getSize());
                    return false;
                }
                // Empty files are recreated by storage init on every session; their timestamp is
                // not evidence about content, and their (zero) content is verified trivially.
                if (fingerprint.getSize() > 0
                        && Files.getLastModifiedTime(path).toMillis() != fingerprint.getModifiedMillis()) {
                    LOGGER.debug("File {} was touched since the resume state was saved; state rejected", path);
                    return false;
                }
            } catch (IOException e) {
                LOGGER.debug("Failed to stat file " + path + "; resume state rejected", e);
                return false;
            }
        }
        return true;
    }

    private boolean spotChecksPass(List<Integer> verifiedPieces, List<ChunkDescriptor> chunks) {
        // zero means "trust the fingerprint gate alone"; otherwise at most spotCheckCount pieces,
        // and a small verified set is simply checked in full
        int checks = spotCheckCount == 0 ? 0 : Math.min(spotCheckCount, verifiedPieces.size());
        if (checks == 0) {
            return true;
        }
        List<Integer> shuffled = new ArrayList<>(verifiedPieces);
        Collections.shuffle(shuffled, random);
        for (int i = 0; i < checks; i++) {
            int pieceIndex = shuffled.get(i);
            if (!delegate.verify(chunks.get(pieceIndex))) {
                LOGGER.info("Resume spot-check failed for piece {}; falling back to full re-hash", pieceIndex);
                return false;
            }
        }
        return true;
    }

    private static List<Integer> setBits(BitSet bitSet, int limit) {
        List<Integer> indices = new ArrayList<>();
        for (int i = bitSet.nextSetBit(0); i >= 0 && i < limit; i = bitSet.nextSetBit(i + 1)) {
            indices.add(i);
        }
        return indices;
    }

    @Override
    public void onTorrentClosed(Torrent torrent, Storage storage, LocalBitfield bitfield) {
        try {
            BitSet verified = bitfield.getBitmask();
            if (verified.isEmpty()) {
                // Nothing was ever verified - no state worth keeping (also clears a stale entry).
                store.delete(torrent.getTorrentId());
                return;
            }

            List<TorrentFile> files = torrent.getFiles();
            List<FileFingerprint> fingerprints = new ArrayList<>(files.size());
            for (TorrentFile file : files) {
                Optional<Path> location = storage.locationOf(torrent, file);
                if (!location.isPresent()) {
                    LOGGER.debug("Storage cannot report file locations; resume state not saved");
                    return;
                }
                Path path = location.get().toAbsolutePath().normalize();
                if (Files.exists(path)) {
                    long size = Files.size(path);
                    long modified = size > 0 ? Files.getLastModifiedTime(path).toMillis() : 0;
                    fingerprints.add(new FileFingerprint(path.toString(), size, modified));
                } else {
                    fingerprints.add(new FileFingerprint(path.toString(), FileFingerprint.MISSING, 0));
                }
            }

            store.save(torrent.getTorrentId(), new ResumeState(
                    torrent.getTorrentId().getBytes(), bitfield.getPiecesTotal(),
                    verified, fingerprints, System.currentTimeMillis()));
        } catch (Exception e) {
            // Resume state is an optimization; failing to save it must never disturb shutdown.
            LOGGER.warn("Failed to save resume state for torrent " + torrent.getTorrentId()
                    + "; next start will do a full re-hash", e);
        }
    }

    @Override
    public boolean verify(ChunkDescriptor chunk) {
        return delegate.verify(chunk);
    }

    @Override
    public boolean verifyIfPresent(ChunkDescriptor chunk) {
        return delegate.verifyIfPresent(chunk);
    }
}
