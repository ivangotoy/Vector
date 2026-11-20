package de.robv.android.xposed;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.Consumer;

/**
 * The directory watches behind {@link XSharedPreferences} listeners.
 *
 * <p>A {@link WatchService} hands out one {@link WatchKey} per directory, so every preferences file
 * in a directory shares it. Each key therefore carries a list of watchers, each with the name of the
 * file it stands for, and the key is cancelled only when the last of them leaves. The service itself
 * is closed when no valid key is left, and forgotten at the same moment, so the next registration
 * opens a fresh one instead of failing on the closed one.
 *
 * <p>All methods lock this object.
 */
final class PrefsWatchRegistry<T> {
    private static final class Entry<T> {
        final String fileName;
        final T value;

        Entry(String fileName, T value) {
            this.fileName = fileName;
            this.value = value;
        }
    }

    private final HashMap<WatchKey, ArrayList<Entry<T>>> mEntries = new HashMap<>();
    private WatchService mWatcher;

    /**
     * Watches the directory of {@code file} for {@code value}.
     *
     * @param onNewWatcher called, under this lock, with a service this call had to open; whoever
     *                     takes events from it is started there.
     * @return the key of the directory, shared with every other file registered in it.
     */
    synchronized WatchKey register(Path file, T value, Consumer<WatchService> onNewWatcher) throws IOException {
        if (mWatcher == null) {
            mWatcher = file.getFileSystem().newWatchService();
            onNewWatcher.accept(mWatcher);
        }
        WatchKey key = file.getParent().register(mWatcher, StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE);
        mEntries.computeIfAbsent(key, k -> new ArrayList<>())
                .add(new Entry<>(file.getFileName().toString(), value));
        return key;
    }

    /**
     * Drops {@code value} from {@code key}. The key is cancelled once nothing else is registered on
     * it, and the service closed once no valid key is left.
     */
    synchronized void unregister(WatchKey key, T value) {
        ArrayList<Entry<T>> list = mEntries.get(key);
        if (list != null) {
            list.removeIf(e -> e.value == value);
            if (list.isEmpty()) {
                mEntries.remove(key);
                key.cancel();
            }
        }
        for (WatchKey k : mEntries.keySet()) {
            if (k.isValid()) return;
        }
        if (mWatcher != null) {
            try {
                mWatcher.close();
            } catch (IOException ignored) {
            }
            mWatcher = null;
        }
    }

    /**
     * The watchers on {@code key} that an event about {@code fileName} concerns: the one for that
     * file, or for the file whose backup it is.
     */
    synchronized List<T> forEvent(WatchKey key, String fileName) {
        List<T> result = new ArrayList<>();
        ArrayList<Entry<T>> list = mEntries.get(key);
        if (list == null) return result;
        for (Entry<T> e : list) {
            if (fileName.equals(e.fileName) || fileName.equals(e.fileName + ".bak")) {
                result.add(e.value);
            }
        }
        return result;
    }
}
