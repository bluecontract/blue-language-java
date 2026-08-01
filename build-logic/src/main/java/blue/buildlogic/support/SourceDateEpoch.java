package blue.buildlogic.support;

import java.time.Instant;
import org.gradle.api.GradleException;

/** Normalizes the reproducible-build timestamp supplied through SOURCE_DATE_EPOCH. */
public final class SourceDateEpoch {

    private SourceDateEpoch() {}

    /**
     * Returns a canonical decimal epoch second. Missing and blank values deliberately use the
     * deterministic Unix-epoch fallback.
     */
    public static String normalize(String rawValue) {
        String candidate = rawValue == null ? "" : rawValue.trim();
        if (candidate.isEmpty()) {
            return "0";
        }
        try {
            long epochSecond = Long.parseLong(candidate);
            Instant.ofEpochSecond(epochSecond);
            return Long.toString(epochSecond);
        } catch (RuntimeException exception) {
            throw new GradleException(
                    "SOURCE_DATE_EPOCH must be a valid Unix epoch second: '" + candidate + "'",
                    exception);
        }
    }

    /** Returns the normalized timestamp as an {@link Instant}. */
    public static Instant instant(String rawValue) {
        return Instant.ofEpochSecond(Long.parseLong(normalize(rawValue)));
    }
}
