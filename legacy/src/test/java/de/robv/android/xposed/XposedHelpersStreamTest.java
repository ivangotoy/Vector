package de.robv.android.xposed;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import org.junit.Test;

public class XposedHelpersStreamTest {
    private static final class Tracked extends InputStream {
        final InputStream data;
        final boolean failing;
        boolean closed;

        Tracked(byte[] bytes, boolean failing) {
            this.data = new ByteArrayInputStream(bytes);
            this.failing = failing;
        }

        @Override
        public int read() throws IOException {
            if (failing) throw new IOException("read failed");
            return data.read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (failing) throw new IOException("read failed");
            return data.read(b, off, len);
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    @Test
    public void streamIsReadAndClosed() throws IOException {
        Tracked in = new Tracked(new byte[] {1, 2, 3}, false);
        assertArrayEquals(new byte[] {1, 2, 3}, XposedHelpers.inputStreamToByteArray(in));
        assertTrue(in.closed);
    }

    @Test
    public void streamIsClosedWhenReadingFails() {
        Tracked in = new Tracked(new byte[0], true);
        try {
            XposedHelpers.inputStreamToByteArray(in);
            fail("the read error should propagate");
        } catch (IOException expected) {
            // closed before the exception left the method
        }
        assertTrue("stream left open after a failed read", in.closed);
    }
}
