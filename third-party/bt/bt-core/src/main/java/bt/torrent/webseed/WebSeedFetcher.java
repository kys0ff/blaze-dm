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

import java.io.IOException;

/**
 * Transport seam for web/HTTP seeds (BEP-17 / BEP-19).
 *
 * <p>The library deliberately owns only the piece-to-file-range mapping ({@link WebSeedPlanner})
 * and the download orchestration; the actual byte transfer is provided by the host application so
 * the fork keeps zero dependency on any HTTP client. The blaze-dm engine implements this over its
 * existing HTTP stack and therefore inherits its connection pooling, retry and speed limiting.</p>
 */
public interface WebSeedFetcher {

    /**
     * Fetches the bytes of a single file segment from a seed.
     *
     * @param seedUrl the seed's base URL as declared in {@code url-list} / {@code httpseeds}
     * @param segment the file and byte range within it to retrieve
     * @return exactly {@link WebSeedSegment#length()} bytes
     * @throws IOException on a transport failure or when the server does not honour the request
     *                     (e.g. a non-{@code 206} response to a ranged request)
     */
    byte[] fetch(String seedUrl, WebSeedSegment segment) throws IOException;
}
