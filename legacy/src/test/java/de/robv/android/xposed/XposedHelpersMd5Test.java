package de.robv.android.xposed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class XposedHelpersMd5Test {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void md5KeepsLeadingZeros() throws IOException {
        File f = tmp.newFile("data");
        // md5("vector-198") = 00232a9e2f56989d50a58765465a6b9d
        Files.write(f.toPath(), "vector-198".getBytes(StandardCharsets.US_ASCII));
        assertEquals("00232a9e2f56989d50a58765465a6b9d", XposedHelpers.getMD5Sum(f.getPath()));
    }

    @Test
    public void md5ClosesTheFileWhenReadingFails() throws IOException {
        File mem = new File("/proc/self/mem");
        File fds = new File("/proc/self/fd");
        if (!mem.canRead() || fds.list() == null) return; // Linux host only
        int before = fds.list().length;
        for (int i = 0; i < 64; i++) {
            try {
                XposedHelpers.getMD5Sum(mem.getPath()); // read at offset 0 fails with EIO
                fail("reading /proc/self/mem at offset 0 should fail");
            } catch (IOException expected) {
                // the descriptor must already be closed here
            }
        }
        int after = fds.list().length;
        assertEquals("descriptors left open by failed reads", before, after, 4);
    }
}
