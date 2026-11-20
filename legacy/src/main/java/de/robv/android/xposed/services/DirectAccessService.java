package de.robv.android.xposed.services;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/**
 * @hide
 */
public final class DirectAccessService extends BaseService {
    @Override
    public boolean hasDirectFileAccess() {
        return true;
    }

    @SuppressWarnings("RedundantIfStatement")
    @Override
    public boolean checkFileAccess(String filename, int mode) {
        File file = new File(filename);
        if (mode == F_OK && !file.exists()) return false;
        if ((mode & R_OK) != 0 && !file.canRead()) return false;
        if ((mode & W_OK) != 0 && !file.canWrite()) return false;
        if ((mode & X_OK) != 0 && !file.canExecute()) return false;
        return true;
    }

    @Override
    public boolean checkFileExists(String filename) {
        return new File(filename).exists();
    }

    @Override
    public FileResult statFile(String filename) throws IOException {
        File file = new File(filename);
        return new FileResult(file.length(), file.lastModified());
    }

    @Override
    public byte[] readFile(String filename) throws IOException {
        File file = new File(filename);
        byte content[] = new byte[arrayLength(file.length(), filename)];
        int read;
        try (FileInputStream fis = new FileInputStream(file)) {
            read = readFully(fis, content);
        }
        // Shorter than its length said: the file shrank in between.
        return read == content.length ? content : Arrays.copyOf(content, read);
    }

    @Override
    public FileResult readFile(String filename, long previousSize, long previousTime) throws IOException {
        File file = new File(filename);
        long size = file.length();
        long time = file.lastModified();
        if (previousSize == size && previousTime == time)
            return new FileResult(size, time);
        return new FileResult(readFile(filename), size, time);
    }

    @Override
    public FileResult readFile(String filename, int offset, int length, long previousSize, long previousTime) throws IOException {
        File file = new File(filename);
        long size = file.length();
        long time = file.lastModified();
        if (previousSize == size && previousTime == time)
            return new FileResult(size, time);

        // Shortcut for the simple case
        if (offset <= 0 && length <= 0)
            return new FileResult(readFile(filename), size, time);

        // Check range
        if (offset > 0 && offset >= size) {
            throw new IllegalArgumentException("Offset " + offset + " is out of range for " + filename);
        } else if (offset < 0) {
            offset = 0;
        }

        if (length > 0 && ((long) offset + length) > size) {
            throw new IllegalArgumentException("Length " + length + " is out of range for " + filename);
        } else if (length <= 0) {
            length = arrayLength(size - offset, filename);
        }

        byte content[] = new byte[length];
        int read;
        try (FileInputStream fis = new FileInputStream(file)) {
            fis.getChannel().position(offset);
            read = readFully(fis, content);
        }
        if (read != length) content = Arrays.copyOf(content, read);
        return new FileResult(content, size, time);
    }

    /** {@code length} as an array size, or an IOException for a file no array can hold. */
    private static int arrayLength(long length, String filename) throws IOException {
        if (length > Integer.MAX_VALUE - 8)
            throw new IOException("File " + filename + " is too large to read into memory (" + length + " bytes)");
        return (int) length;
    }

    /**
     * Reads until {@code buffer} is full or the stream ends: a single read may return fewer bytes.
     *
     * @return the number of bytes read.
     */
    static int readFully(InputStream in, byte[] buffer) throws IOException {
        int total = 0;
        while (total < buffer.length) {
            int n = in.read(buffer, total, buffer.length - total);
            if (n < 0) break;
            total += n;
        }
        return total;
    }

    /**
     * {@inheritDoc}
     * <p>This implementation returns a BufferedInputStream instead of loading the file into memory.
     */
    @Override
    public InputStream getFileInputStream(String filename) throws IOException {
        return new BufferedInputStream(new FileInputStream(filename), 16 * 1024);
    }

    /**
     * {@inheritDoc}
     * <p>This implementation returns a BufferedInputStream instead of loading the file into memory.
     */
    @Override
    public FileResult getFileInputStream(String filename, long previousSize, long previousTime) throws IOException {
        File file = new File(filename);
        long size = file.length();
        long time = file.lastModified();
        if (previousSize == size && previousTime == time)
            return new FileResult(size, time);
        return new FileResult(new BufferedInputStream(new FileInputStream(filename), 16 * 1024), size, time);
    }
}
