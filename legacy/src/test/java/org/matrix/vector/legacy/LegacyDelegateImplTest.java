package org.matrix.vector.legacy;

import static org.junit.Assert.assertFalse;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class LegacyDelegateImplTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File apk(String entryName, byte[] content) throws Exception {
        File file = tmp.newFile("module.apk");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file))) {
            zip.putNextEntry(new ZipEntry(entryName));
            zip.write(content);
            zip.closeEntry();
        }
        return file;
    }

    @Test
    public void anApkWithoutAManifestAsksForNothing() throws Exception {
        File apk = apk("classes.dex", new byte[] {0});

        assertFalse(LegacyDelegateImpl.usesNewXSharedPreferences(apk));
    }

    @Test
    public void aMalformedManifestAsksForNothing() throws Exception {
        File apk = apk("AndroidManifest.xml", "not binary xml".getBytes(StandardCharsets.US_ASCII));

        assertFalse(LegacyDelegateImpl.usesNewXSharedPreferences(apk));
    }
}
