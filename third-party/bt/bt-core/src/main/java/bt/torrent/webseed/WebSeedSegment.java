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

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * One contiguous byte range of a single torrent file that a piece (or part of a piece) resolves to
 * when fetching from a web/HTTP seed (BEP-17 / BEP-19).
 *
 * <p>A piece is laid out over the concatenation of all torrent files, so a piece that straddles a
 * file boundary is described by more than one segment, in file order; the caller fetches them in
 * order and concatenates the results to rebuild the piece.</p>
 */
public final class WebSeedSegment {

    private final int fileIndex;
    private final List<String> filePath;
    private final long fromInclusive;
    private final long toInclusive;

    /**
     * @param fileIndex    index of the file within {@code torrent.getFiles()}
     * @param filePath     the file's path elements (directories then file name)
     * @param fromInclusive first byte of the range, relative to the start of this file (inclusive)
     * @param toInclusive   last byte of the range, relative to the start of this file (inclusive)
     */
    public WebSeedSegment(int fileIndex, List<String> filePath, long fromInclusive, long toInclusive) {
        if (fromInclusive < 0 || toInclusive < fromInclusive) {
            throw new IllegalArgumentException("Invalid range: [" + fromInclusive + ", " + toInclusive + "]");
        }
        this.fileIndex = fileIndex;
        this.filePath = Collections.unmodifiableList(Objects.requireNonNull(filePath));
        this.fromInclusive = fromInclusive;
        this.toInclusive = toInclusive;
    }

    public int getFileIndex() {
        return fileIndex;
    }

    public List<String> getFilePath() {
        return filePath;
    }

    public long getFromInclusive() {
        return fromInclusive;
    }

    public long getToInclusive() {
        return toInclusive;
    }

    /** Number of bytes this segment covers. Always fits in an {@code int} for a single piece. */
    public int length() {
        return Math.toIntExact(toInclusive - fromInclusive + 1);
    }

    @Override
    public String toString() {
        return "WebSeedSegment{file=" + filePath + " [" + fromInclusive + ".." + toInclusive + "]}";
    }
}
