package de.robv.android.xposed.callbacks;

import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;

/** Callbacks run in priority order, higher first; equal priorities keep their registration order. */
public class XCallbackPriorityTest {
    private static final class Named extends XCallback {
        final String name;

        Named(String name, int priority) {
            super(priority);
            this.name = name;
        }
    }

    private static String[] names(XCallback[] callbacks) {
        String[] out = new String[callbacks.length];
        for (int i = 0; i < callbacks.length; i++) out[i] = ((Named) callbacks[i]).name;
        return out;
    }

    @Test
    public void higherPriorityRunsFirst() {
        XCallback[] sorted = XCallback.byPriority(new XCallback[] {
            new Named("low", XCallback.PRIORITY_LOWEST), new Named("high", XCallback.PRIORITY_HIGHEST),
            new Named("default", XCallback.PRIORITY_DEFAULT)});
        assertArrayEquals(new String[] {"high", "default", "low"}, names(sorted));
    }

    @Test
    public void equalPrioritiesKeepRegistrationOrder() {
        XCallback[] sorted = XCallback.byPriority(new XCallback[] {
            new Named("a", 50), new Named("b", 50), new Named("first", 60), new Named("c", 50)});
        assertArrayEquals(new String[] {"first", "a", "b", "c"}, names(sorted));
    }
}
