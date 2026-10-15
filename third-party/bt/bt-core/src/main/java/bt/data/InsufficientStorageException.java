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

package bt.data;

/**
 * Raised before a transfer starts when the target volume cannot hold what the torrent will
 * eventually write. It is deliberately a distinct type so callers can surface an actionable
 * "not enough disk space" error instead of an opaque I/O failure mid-download.
 *
 * @since 1.10 (blaze fork)
 */
public class InsufficientStorageException extends RuntimeException {

    public InsufficientStorageException(String message) {
        super(message);
    }

    public InsufficientStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
