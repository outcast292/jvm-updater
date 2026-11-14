package io.github.outcast292.jvmupdater.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoreTest {

    @TempDir
    Path root;

    @Test
    void emptyWhenNothingIsInstalled() throws IOException {
        assertEquals(Store.State.EMPTY, new Store(root).read());
    }

    @Test
    void keepsTheStateAcrossInstances() throws IOException {
        new Store(root).update(state -> new Store.State("1.0.0", "0.9.0", "1.1.0", Set.of("0.8.0")));

        assertEquals(new Store.State("1.0.0", "0.9.0", "1.1.0", Set.of("0.8.0")), new Store(root).read());
    }

    @Test
    void promotingKeepsTheOldCurrentForRollback() {
        Store.State state = new Store.State("1.0.0", "0.9.0", "1.1.0", Set.of());

        Store.State promoted = state.promote("1.1.0");

        assertEquals(new Store.State("1.1.0", "1.0.0", null, Set.of()), promoted);
        assertEquals(promoted, promoted.promote("1.1.0"));
    }

    @Test
    void promotingAnOlderVersionKeepsANewerPending() {
        Store.State state = new Store.State(null, null, "2.0.0", Set.of());
        assertEquals("2.0.0", state.promote("1.0.0").pending());
    }

    @Test
    void aBadVersionIsForgottenEverywhere() {
        Store.State state = new Store.State("1.1.0", "1.0.0", null, Set.of()).markBad("1.1.0");

        assertNull(state.current());
        assertEquals("1.0.0", state.previous());
        assertTrue(state.bad().contains("1.1.0"));
    }

    @Test
    void ignoresVersionsThatCouldEscapeTheStore() throws IOException {
        Files.writeString(root.resolve("state"), "current=../../etc\npending=1.2.0\nbad=1.0.0,..\n");

        Store.State state = new Store(root).read();

        assertNull(state.current());
        assertEquals("1.2.0", state.pending());
        assertEquals(Set.of("1.0.0"), state.bad());
    }

    @Test
    void pruneDeletesOnlyUnusedReleases() throws IOException {
        Store store = new Store(root);
        for (String name : new String[]{"0.9.0", "1.0.0", "1.1.0", "1.2.0.part"}) {
            Files.createDirectories(root.resolve("versions").resolve(name).resolve("files"));
        }
        store.update(state -> new Store.State("1.1.0", "1.0.0", null, Set.of()));

        store.prune();

        assertFalse(Files.exists(root.resolve("versions/0.9.0")));
        assertTrue(Files.exists(root.resolve("versions/1.0.0")));
        assertTrue(Files.exists(root.resolve("versions/1.1.0")));
        assertTrue(Files.exists(root.resolve("versions/1.2.0.part")));
    }

    @Test
    void lockedCanBeNested() throws IOException {
        Store store = new Store(root);
        String result = store.locked(() -> store.locked(() -> "inner"));
        assertEquals("inner", result);
    }
}
