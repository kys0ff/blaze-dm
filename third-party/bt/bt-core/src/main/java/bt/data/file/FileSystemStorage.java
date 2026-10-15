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

package bt.data.file;

import bt.data.PreallocationMode;
import bt.data.Storage;
import bt.data.StorageUnit;
import bt.metainfo.Torrent;
import bt.metainfo.TorrentFile;

import java.io.File;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Provides file-system based storage for torrent files.
 *
 * <p>Information about the collection of files in a torrent is stored as a list of strings in the torrent metainfo.
 * This makes it possible for malicious parties to create torrents with malformed pathnames,
 * e.g. by using relative paths (. and ..), empty and invalid directory and file names, etc.
 * In the worst case this can lead to loss and corruption of user data and execution of arbitrary code.
 *
 * <p>This storage implementation performs normalization of file paths, ensuring that:
 * <ul>
 *     <li>all torrent files are stored inside the root directory of this storage (see {@link #FileSystemStorage(File)})</li>
 *     <li>all individual paths are checked for potential issues and fixed in a consistent way (see below)</li>
 * </ul>
 *
 * <p><b>Algorithm for resolving paths:</b><br>
 * 1) The following transformations are applied to each individual path element:
 * <ul>
 *     <li>trimming whitespaces,</li>
 *     <li>truncating trailing dots and whitespaces recursively,</li>
 *     <li>substituting empty names with an underscore character
 *         (this also includes names that became empty after the truncation of whitespaces and dots),</li>
 *     <li>in case there is a leading or trailing file separator,
 *         it is assumed that the path starts or ends with an empty name, respectively.</li>
 * </ul><br>
 * 2) Normalized path elements are concatenated together using {@link File#separator} as the delimiter.
 *
 * <p><b>Examples:</b><br>
 * {@code "a/b/c"     => "a/b/c"}<br>
 * {@code " a/  b /c" => "a/b/c"}<br>
 * {@code ".a/.b"     => ".a/.b"}<br>
 * {@code "a./.b."    => "a/.b"}<br>
 * {@code ""          => "_"}<br>
 * {@code "a//b"      => "a/_/b"}<br>
 * {@code "."         => "_"}<br>
 * {@code ".."        => "_"}<br>
 * {@code "/"         => "_/_"}<br>
 * {@code "/a/b/c"    => "_/a/b/c"}<br>
 *
 * @since 1.0
 */
public class FileSystemStorage implements Storage {
    private static final int DEFAULT_MAX_OPEN_FILES = 256;
    private final OpenFileCache cache;
    private final Path rootDirectory;
    private final PathNormalizer pathNormalizer;
    private final PreallocationMode preallocationMode;

    /**
     * Create a file-system based storage inside a given directory.
     *
     * @param rootDirectory Root directory for this storage. All torrent files will be stored inside this directory.
     * @since 1.0
     */
    public FileSystemStorage(File rootDirectory) {
        this(rootDirectory.toPath());
    }

    public FileSystemStorage(Path rootDirectory) {
        this(rootDirectory, DEFAULT_MAX_OPEN_FILES);
    }

    /**
     * Construct a new FileSystemStorage
     *
     * @param rootDirectory the root directory to store files
     * @param maxOpenFiles  the max number of open files this storage can use for efficiency
     */
    public FileSystemStorage(Path rootDirectory, int maxOpenFiles) {
        this(rootDirectory, maxOpenFiles, PreallocationMode.NONE);
    }

    /**
     * Construct a new FileSystemStorage that reserves each file's full length up front.
     *
     * @param rootDirectory     the root directory to store files
     * @param maxOpenFiles      the max number of open files this storage can use for efficiency
     * @param preallocationMode how (and whether) to preallocate each torrent file; {@link
     *                          PreallocationMode#NONE} keeps the historical grow-on-write behavior
     * @since 1.10 (blaze fork)
     */
    public FileSystemStorage(Path rootDirectory, int maxOpenFiles, PreallocationMode preallocationMode) {
        this.cache = new OpenFileCache(maxOpenFiles);
        this.rootDirectory = rootDirectory;
        this.pathNormalizer = new PathNormalizer(rootDirectory.getFileSystem());
        this.preallocationMode = preallocationMode;
    }

    @Override
    public StorageUnit getUnit(Torrent torrent, TorrentFile torrentFile) {
        Path location = resolveLocation(torrent, torrentFile);
        Preallocation.apply(location, torrentFile.getSize(), preallocationMode);
        return new FileSystemStorageUnit(cache, location.getParent(), location.getFileName().toString(),
                torrentFile.getSize());
    }

    @Override
    public Optional<Path> locationOf(Torrent torrent, TorrentFile torrentFile) {
        return Optional.of(resolveLocation(torrent, torrentFile).toAbsolutePath().normalize());
    }

    /**
     * Resolves the on-disk location of a torrent file, with the same normalization that
     * {@link #getUnit(Torrent, TorrentFile)} writes through.
     */
    private Path resolveLocation(Torrent torrent, TorrentFile torrentFile) {
        Path torrentDirectory;
        if (torrent.getFiles().size() == 1) {
            torrentDirectory = rootDirectory;
        } else {
            String normalizedName = pathNormalizer.normalize(torrent.getName());
            torrentDirectory = rootDirectory.resolve(normalizedName);
        }
        String normalizedPath = pathNormalizer.normalize(torrentFile.getPathElements());
        return torrentDirectory.resolve(normalizedPath);
    }

    @Override
    public void flush() {
        cache.flush();
    }
}
