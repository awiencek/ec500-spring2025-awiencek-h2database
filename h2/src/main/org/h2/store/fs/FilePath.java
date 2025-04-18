/*
 * Copyright 2004-2025 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.store.fs;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.h2.store.fs.disk.FilePathDisk;
import org.h2.util.MathUtils;

/**
 * A path to a file. It similar to the Java 7 <code>java.nio.file.Path</code>,
 * but simpler, and works with older versions of Java. It also implements the
 * relevant methods found in <code>java.nio.file.FileSystem</code> and
 * <code>FileSystems</code>
 */
public abstract class FilePath {

    private static final FilePath defaultProvider;

    private static final ConcurrentHashMap<String, FilePath> providers;

    /**
     * The prefix for temporary files.
     */
    private static String tempRandom;
    private static long tempSequence;

    /**
     * The complete path (which may be absolute or relative, depending on the
     * file system).
     */
    public String name;

    static {
        ConcurrentHashMap<String, FilePath> map = new ConcurrentHashMap<>();
        FilePath p = new FilePathDisk();
        map.put(p.getScheme(), p);
        map.put("nio", p);
        defaultProvider = p;
        for (String c : new String[] {
                "org.h2.store.fs.mem.FilePathMem",
                "org.h2.store.fs.mem.FilePathMemLZF",
                "org.h2.store.fs.niomem.FilePathNioMem",
                "org.h2.store.fs.niomem.FilePathNioMemLZF",
                "org.h2.store.fs.split.FilePathSplit",
                "org.h2.store.fs.niomapped.FilePathNioMapped",
                "org.h2.store.fs.async.FilePathAsync",
                "org.h2.store.fs.zip.FilePathZip",
                "org.h2.store.fs.retry.FilePathRetryOnInterrupt"
        }) {
            try {
                p = (FilePath) Class.forName(c).getDeclaredConstructor().newInstance();
                map.put(p.getScheme(), p);
            } catch (Exception e) {
                // ignore - the files may be excluded in purpose
            }
        }
        providers = map;
    }

    /**
     * Get the file path object for the given path.
     * Windows-style '\' is replaced with '/'.
     *
     * @param path the path
     * @return the file path object
     */
    public static FilePath get(String path) {
        path = path.replace('\\', '/');
        int index = path.indexOf(':');
        if (index < 2) {
            // use the default provider if no prefix or
            // only a single character (drive name)
            return defaultProvider.getPath(path);
        }
        String scheme = path.substring(0, index);
        FilePath p = providers.get(scheme);
        if (p == null) {
            // provider not found - use the default
            p = defaultProvider;
        }
        return p.getPath(path);
    }

    /**
     * Register a file provider.
     *
     * @param provider the file provider
     */
    public static void register(FilePath provider) {
        providers.put(provider.getScheme(), provider);
    }

    /**
     * Unregister a file provider.
     *
     * @param provider the file provider
     */
    public static void unregister(FilePath provider) {
        providers.remove(provider.getScheme());
    }

    /**
     * Get the size of a file in bytes
     *
     * @return the size in bytes
     */
    public abstract long size();

    /**
     * Rename a file if this is allowed.
     *
     * @param newName the new fully qualified file name
     * @param atomicReplace whether the move should be atomic, and the target
     *            file should be replaced if it exists and replacing is possible
     */
    public abstract void moveTo(FilePath newName, boolean atomicReplace);

    /**
     * Create a new file.
     *
     * @return true if creating was successful
     */
    public abstract boolean createFile();

    /**
     * Checks if a file exists.
     *
     * @return true if it exists
     */
    public abstract boolean exists();

    /**
     * Delete a file or directory if it exists.
     * Directories may only be deleted if they are empty.
     */
    public abstract void delete();

    /**
     * List the files and directories in the given directory.
     *
     * @return the list of fully qualified file names
     */
    public abstract List<FilePath> newDirectoryStream();

    /**
     * Normalize a file name.
     *
     * @return the normalized file name
     */
    public abstract FilePath toRealPath();

    /**
     * Get the parent directory of a file or directory.
     *
     * @return the parent directory name
     */
    public abstract FilePath getParent();

    /**
     * Check if it is a file or a directory.
     *
     * @return true if it is a directory
     */
    public abstract boolean isDirectory();

    /**
     * Check if it is a regular file.
     *
     * @return true if it is a regular file
     */
    public abstract boolean isRegularFile();

    /**
     * Check if the file name includes a path.
     *
     * @return if the file name is absolute
     */
    public abstract boolean isAbsolute();

    /**
     * Get the last modified date of a file
     *
     * @return the last modified date
     */
    public abstract long lastModified();

    /**
     * Check if the file is writable.
     *
     * @return if the file is writable
     */
    public abstract boolean canWrite();

    /**
     * Create a directory (all required parent directories already exist).
     */
    public abstract void createDirectory();

    /**
     * Get the file or directory name (the last element of the path).
     *
     * @return the last element of the path
     */
    public String getName() {
        int idx = Math.max(name.indexOf(':'), name.lastIndexOf('/'));
        return idx < 0 ? name : name.substring(idx + 1);
    }

    /**
     * Create an output stream to write into the file.
     *
     * @param append if true, the file will grow, if false, the file will be
     *            truncated first
     * @return the output stream
     * @throws IOException If an I/O error occurs
     */
    public OutputStream newOutputStream(boolean append) throws IOException {
        return newFileChannelOutputStream(open("rw"), append);
    }

    /**
     * Create a new output stream from the channel.
     *
     * @param channel the file channel
     * @param append true for append mode, false for truncate and overwrite
     * @return the output stream
     * @throws IOException on I/O exception
     */
    public static OutputStream newFileChannelOutputStream(FileChannel channel, boolean append)
            throws IOException {
        if (append) {
            channel.position(channel.size());
        } else {
            channel.position(0);
            channel.truncate(0);
        }
        return Channels.newOutputStream(channel);
    }

    /**
     * Open a random access file object.
     *
     * @param mode the access mode. Supported are r, rw, rws, rwd
     * @return the file object
     * @throws IOException If an I/O error occurs
     */
    public abstract FileChannel open(String mode) throws IOException;

    /**
     * Create an input stream to read from the file.
     *
     * @return the input stream
     * @throws IOException If an I/O error occurs
     */
    public InputStream newInputStream() throws IOException {
        return Channels.newInputStream(open("r"));
    }

    /**
     * Disable the ability to write.
     *
     * @return true if the call was successful
     */
    public abstract boolean setReadOnly();

    /**
     * Create a new temporary file.
     *
     * @param suffix the suffix
     * @param inTempDir if the file should be stored in the temporary directory
     * @return the name of the created file
     * @throws IOException on failure
     */
    @SuppressWarnings("unused")
    public FilePath createTempFile(String suffix, boolean inTempDir) throws IOException {
        while (true) {
            FilePath p = getPath(name + getNextTempFileNamePart(false) + suffix);
            if (p.exists() || !p.createFile()) {
                // in theory, the random number could collide
                getNextTempFileNamePart(true);
                continue;
            }
            p.open("rw").close();
            return p;
        }
    }

    /**
     * Get the next temporary file name part (the part in the middle).
     *
     * @param newRandom if the random part of the filename should change
     * @return the file name part
     */
    private static synchronized String getNextTempFileNamePart(
            boolean newRandom) {
        if (newRandom || tempRandom == null) {
            tempRandom = MathUtils.randomInt(Integer.MAX_VALUE) + ".";
        }
        return tempRandom + tempSequence++;
    }

    /**
     * Get the string representation. The returned string can be used to
     * construct a new object.
     *
     * @return the path as a string
     */
    @Override
    public String toString() {
        return name;
    }

    /**
     * Get the scheme (prefix) for this file provider.
     * This is similar to
     * <code>java.nio.file.spi.FileSystemProvider.getScheme</code>.
     *
     * @return the scheme
     */
    public abstract String getScheme();

    /**
     * Convert a file to a path. This is similar to
     * <code>java.nio.file.spi.FileSystemProvider.getPath</code>, but may
     * return an object even if the scheme doesn't match in case of the
     * default file provider.
     *
     * @param path the path
     * @return the file path object
     */
    public abstract FilePath getPath(String path);

    /**
     * Get the unwrapped file name (without wrapper prefixes if wrapping /
     * delegating file systems are used).
     *
     * @return the unwrapped path
     */
    public FilePath unwrap() {
        return this;
    }

}
