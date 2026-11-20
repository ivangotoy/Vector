package de.robv.android.xposed.services;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class DirectAccessServiceTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final DirectAccessService service = new DirectAccessService();

    /** A stream that hands out one byte per read, as a read is allowed to. */
    private static InputStream trickling(byte[] data) {
        return new ByteArrayInputStream(data) {
            @Override
            public synchronized int read(byte[] b, int off, int len) {
                return super.read(b, off, Math.min(len, 1));
            }
        };
    }

    @Test
    public void readFullyKeepsReadingAfterAShortRead() throws IOException {
        byte[] data = {1, 2, 3, 4, 5};
        byte[] buffer = new byte[5];

        assertEquals(5, DirectAccessService.readFully(trickling(data), buffer));
        assertArrayEquals(data, buffer);
    }

    @Test
    public void readFullyStopsAtTheEndOfTheStream() throws IOException {
        byte[] buffer = new byte[8];

        assertEquals(3, DirectAccessService.readFully(trickling(new byte[] {7, 8, 9}), buffer));
    }

    @Test
    public void readFileWithOffsetReadsTheRange() throws IOException {
        File file = tmp.newFile("prefs.xml");
        Files.write(file.toPath(), new byte[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9});

        FileResult result = service.readFile(file.getPath(), 3, 4, -1, -1);

        assertArrayEquals(new byte[] {3, 4, 5, 6}, result.content);
        assertArrayEquals(new byte[] {7, 8, 9}, service.readFile(file.getPath(), 7, 0, -1, -1).content);
    }

    @Test
    public void aRangeWhoseEndOverflowsIsOutOfRange() throws IOException {
        File file = tmp.newFile("prefs.xml");
        Files.write(file.toPath(), new byte[10]);

        assertThrows(IllegalArgumentException.class,
                () -> service.readFile(file.getPath(), 5, Integer.MAX_VALUE, -1, -1));
    }

    @Test
    public void aFileLargerThanAnArrayIsAnIOException() throws IOException {
        File file = tmp.newFile("huge.bin");
        // Sparse: takes no space on disk.
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            raf.setLength(3L << 30);
        }

        assertThrows(IOException.class, () -> service.readFile(file.getPath()));
        assertThrows(IOException.class, () -> service.readFile(file.getPath(), 1, 0, -1, -1));
    }
}
