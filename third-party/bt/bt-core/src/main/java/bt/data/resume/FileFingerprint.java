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

/**
 * Identity data for one torrent file as it existed on disk at the moment a {@link ResumeState}
 * was saved: where it lives, how big it was, and when it was last modified.
 *
 * <p>A resumed state is only trusted when the current file matches all three - a moved,
 * resized or touched file disqualifies the whole state and forces a full re-hash.
 * A {@code size} of {@link #MISSING} records that the file did not exist when the state was
 * saved (a not-yet-started file in a multi-file torrent); trust then requires that it is
 * still absent. The modification time is only compared for non-empty files, because trivial
 * files are recreated by storage initialisation on every session and their timestamp is not
 * meaningful content evidence.</p>
 *
 * <p><b>Note that this class is not a part of the public API and is a subject to change.</b></p>
 */
public final class FileFingerprint {

    /** Sentinel recorded for a file that did not exist when the resume state was saved. */
    public static final long MISSING = -1;

    private final String path;
    private final long size;
    private final long modifiedMillis;

    public FileFingerprint(String path, long size, long modifiedMillis) {
        this.path = path;
        this.size = size;
        this.modifiedMillis = modifiedMillis;
    }

    public String getPath() {
        return path;
    }

    public long getSize() {
        return size;
    }

    public long getModifiedMillis() {
        return modifiedMillis;
    }

    @Override
    public String toString() {
        return getClass().getName() + " [path=" + path + ", size=" + size + ", mtime=" + modifiedMillis + "]";
    }
}
