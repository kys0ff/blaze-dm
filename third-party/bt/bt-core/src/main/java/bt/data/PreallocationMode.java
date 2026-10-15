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
 * How much of a torrent file's disk space is reserved before the transfer starts.
 *
 * <p>The three settings trade startup cost and safety against laziness:
 * <ul>
 *     <li>{@link #NONE} — files grow as data is written. Nothing is reserved up front, so a full
 *     disk is only discovered mid-transfer when a write fails.</li>
 *     <li>{@link #SPARSE} — each file is extended to its full length immediately
 *     (a single trailing byte is written; the body is left as a filesystem hole). Cheap on
 *     ext4/APFS, where holes consume no blocks until they are filled, but the free-space check
 *     still fails fast for a download that could not ever complete.</li>
 *     <li>{@link #FULL} — the whole length is written with zeros up front. Guarantees the space is
 *     physically there before a byte arrives, at the cost of rewriting it all. Only worth asking
 *     for when a hard up-front reservation matters more than the write.</li>
 * </ul>
 *
 * <p><b>Platform note (why SPARSE is not free everywhere):</b> extending a file through the JDK's
 * {@code FileChannel} does not set the OS sparse attribute. On Linux (ext4/btrfs) and macOS (APFS)
 * the extended region is a real hole and costs metadata only; on Windows/NTFS the clusters across
 * the whole length are allocated by the extension itself, so SPARSE reserves (and charges) the full
 * size there just like FULL would — it simply skips the zero bytes. Blaze targets all three, so the
 * free-space check runs for SPARSE as well as FULL.
 *
 * @since 1.10 (blaze fork)
 */
public enum PreallocationMode {
    NONE,
    SPARSE,
    FULL
}
