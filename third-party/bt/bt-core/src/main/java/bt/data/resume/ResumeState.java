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

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;

/**
 * Persisted per-torrent resume state: which pieces were verified as good at the moment of the
 * save, together with the {@link FileFingerprint} of every torrent file at that same moment.
 *
 * <p>The fingerprints are an integral part of the state, not metadata: a {@link ResumeState} is
 * only ever trusted after every file still matches its recorded identity (and a spot-check of
 * the claimed pieces passes). Anything else falls back to a full re-hash.</p>
 *
 * <p><b>Note that this class is not a part of the public API and is a subject to change.</b></p>
 */
public final class ResumeState {

    private final byte[] torrentId;
    private final int piecesTotal;
    private final BitSet verifiedPieces;
    private final List<FileFingerprint> fingerprints;
    private final long savedAtMillis;

    public ResumeState(byte[] torrentId,
                       int piecesTotal,
                       BitSet verifiedPieces,
                       List<FileFingerprint> fingerprints,
                       long savedAtMillis) {
        this.torrentId = torrentId;
        this.piecesTotal = piecesTotal;
        this.verifiedPieces = verifiedPieces;
        this.fingerprints = Collections.unmodifiableList(new ArrayList<>(fingerprints));
        this.savedAtMillis = savedAtMillis;
    }

    public byte[] getTorrentId() {
        return torrentId;
    }

    public int getPiecesTotal() {
        return piecesTotal;
    }

    /**
     * @return Copy of the bitmask of pieces verified when this state was saved. Bits may only
     * cover indices below {@link #getPiecesTotal()}.
     */
    public BitSet getVerifiedPieces() {
        return (BitSet) verifiedPieces.clone();
    }

    public List<FileFingerprint> getFingerprints() {
        return fingerprints;
    }

    public long getSavedAtMillis() {
        return savedAtMillis;
    }
}
