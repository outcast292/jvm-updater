package io.github.outcast292.jvmupdater;

import java.math.BigInteger;
import java.util.regex.Pattern;

/**
 * Release versions: dot-separated numbers with an optional pre-release part, such as {@code 1.4.0}
 * or {@code 2.0.0-beta.1}. A version is also used as a directory name, so nothing else is allowed.
 */
public final class Versions {

    private static final Pattern VALID =
            Pattern.compile("\\d{1,9}(\\.\\d{1,9}){0,5}(-[0-9A-Za-z]+(\\.[0-9A-Za-z]+)*)?");

    private Versions() {
    }

    public static boolean isValid(String version) {
        return version != null && version.length() <= 64 && VALID.matcher(version).matches();
    }

    public static String requireValid(String version) {
        if (!isValid(version)) {
            throw new IllegalArgumentException("Invalid version: " + version);
        }
        return version;
    }

    /** True when {@code candidate} is newer than {@code other}; anything is newer than null. */
    public static boolean isNewer(String candidate, String other) {
        return other == null || compare(candidate, other) > 0;
    }

    /**
     * Compares two valid versions. Missing numbers count as zero ({@code 1.2 == 1.2.0}) and a
     * pre-release comes before its release ({@code 1.0.0-rc.1 < 1.0.0}).
     */
    public static int compare(String a, String b) {
        requireValid(a);
        requireValid(b);
        String[] aParts = a.split("-", 2);
        String[] bParts = b.split("-", 2);

        int result = compareNumbers(aParts[0].split("\\."), bParts[0].split("\\."));
        if (result != 0) {
            return result;
        }
        boolean aPre = aParts.length > 1;
        boolean bPre = bParts.length > 1;
        if (aPre != bPre) {
            return aPre ? -1 : 1;
        }
        return aPre ? comparePreRelease(aParts[1].split("\\."), bParts[1].split("\\.")) : 0;
    }

    private static int compareNumbers(String[] a, String[] b) {
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? Integer.parseInt(a[i]) : 0;
            int y = i < b.length ? Integer.parseInt(b[i]) : 0;
            if (x != y) {
                return Integer.compare(x, y);
            }
        }
        return 0;
    }

    // Same rules as semver: numbers compare as numbers and sort before words.
    private static int comparePreRelease(String[] a, String[] b) {
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            boolean aNumber = a[i].chars().allMatch(Character::isDigit);
            boolean bNumber = b[i].chars().allMatch(Character::isDigit);
            int result;
            if (aNumber && bNumber) {
                result = new BigInteger(a[i]).compareTo(new BigInteger(b[i]));
            } else if (aNumber != bNumber) {
                result = aNumber ? -1 : 1;
            } else {
                result = a[i].compareTo(b[i]);
            }
            if (result != 0) {
                return result;
            }
        }
        return Integer.compare(a.length, b.length);
    }
}
