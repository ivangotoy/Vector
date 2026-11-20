package de.robv.android.xposed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class PrefsWatchRegistryTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final List<WatchService> opened = new ArrayList<>();

    private WatchKey register(PrefsWatchRegistry<String> registry, Path file, String value) throws Exception {
        return registry.register(file, value, opened::add);
    }

    @Test
    public void twoFilesInOneDirectoryEachKeepTheirWatcher() throws Exception {
        PrefsWatchRegistry<String> registry = new PrefsWatchRegistry<>();
        Path dir = tmp.newFolder("shared_prefs").toPath();
        WatchKey a = register(registry, dir.resolve("a.xml"), "A");
        WatchKey b = register(registry, dir.resolve("b.xml"), "B");

        assertSame("one key per directory", a, b);
        assertEquals(Collections.singletonList("A"), registry.forEvent(a, "a.xml"));
        assertEquals(Collections.singletonList("B"), registry.forEvent(a, "b.xml"));
        assertEquals(Collections.singletonList("B"), registry.forEvent(a, "b.xml.bak"));
        assertEquals(Collections.emptyList(), registry.forEvent(a, "c.xml"));
    }

    @Test
    public void unregisteringOneFileKeepsTheOtherWatched() throws Exception {
        PrefsWatchRegistry<String> registry = new PrefsWatchRegistry<>();
        Path dir = tmp.newFolder("shared_prefs").toPath();
        WatchKey key = register(registry, dir.resolve("a.xml"), "A");
        register(registry, dir.resolve("b.xml"), "B");

        registry.unregister(key, "A");

        assertTrue("the key is still needed for b.xml", key.isValid());
        assertEquals(Collections.singletonList("B"), registry.forEvent(key, "b.xml"));

        // And the event really arrives for the file left.
        Files.write(dir.resolve("b.xml"), new byte[] {1});
        WatchKey signalled = opened.get(0).poll(10, TimeUnit.SECONDS);
        assertNotNull("no event for b.xml", signalled);
        List<String> notified = new ArrayList<>();
        for (WatchEvent<?> event : signalled.pollEvents()) {
            notified.addAll(registry.forEvent(signalled, event.context().toString()));
        }
        assertTrue(notified.contains("B"));
    }

    @Test
    public void registeringAfterTheLastUnregisterOpensANewService() throws Exception {
        PrefsWatchRegistry<String> registry = new PrefsWatchRegistry<>();
        Path dir = tmp.newFolder("shared_prefs").toPath();
        WatchKey first = register(registry, dir.resolve("a.xml"), "A");
        registry.unregister(first, "A");

        WatchKey second = register(registry, dir.resolve("a.xml"), "A");

        assertTrue(second.isValid());
        assertEquals(2, opened.size());
        assertEquals(Collections.singletonList("A"), registry.forEvent(second, "a.xml"));
    }
}
