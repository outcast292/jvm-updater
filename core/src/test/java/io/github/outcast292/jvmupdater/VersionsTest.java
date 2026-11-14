package io.github.outcast292.jvmupdater;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionsTest {

    @ParameterizedTest
    @ValueSource(strings = {"1", "1.4.0", "10.0.12", "2.0.0-beta.1", "1.0.0-rc1"})
    void acceptsVersions(String version) {
        assertTrue(Versions.isValid(version));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "v1.0", "1..0", "1.0/..", "../1", "1.0-", "1.0 ", "1.0.0+build"})
    void refusesAnythingThatIsNotASafeVersion(String version) {
        assertFalse(Versions.isValid(version));
    }

    @Test
    void comparesNumbersAsNumbers() {
        assertTrue(Versions.compare("1.10.0", "1.9.0") > 0);
        assertTrue(Versions.compare("2", "1.99") > 0);
        assertEquals(0, Versions.compare("1.2", "1.2.0"));
    }

    @Test
    void preReleaseComesBeforeRelease() {
        assertTrue(Versions.compare("1.0.0-rc.1", "1.0.0") < 0);
        assertTrue(Versions.compare("1.0.0-beta.2", "1.0.0-beta.11") < 0);
        assertTrue(Versions.compare("1.0.0-1", "1.0.0-alpha") < 0);
        assertTrue(Versions.compare("1.0.0-alpha", "1.0.0-alpha.1") < 0);
    }

    @Test
    void anythingIsNewerThanNothing() {
        assertTrue(Versions.isNewer("0.0.1", null));
        assertFalse(Versions.isNewer("1.0.0", "1.0.0"));
    }

    @Test
    void compareRefusesInvalidVersions() {
        assertThrows(IllegalArgumentException.class, () -> Versions.compare("1.0", "../2"));
    }
}
