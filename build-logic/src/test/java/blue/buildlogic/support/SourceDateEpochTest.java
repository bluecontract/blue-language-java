package blue.buildlogic.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.gradle.api.GradleException;
import org.junit.jupiter.api.Test;

final class SourceDateEpochTest {

    @Test
    void shouldUseTheUnixEpochForMissingOrBlankValues() {
        // given / when / then
        assertEquals("0", SourceDateEpoch.normalize(null));
        assertEquals("0", SourceDateEpoch.normalize(""));
        assertEquals("0", SourceDateEpoch.normalize("  \t"));
    }

    @Test
    void shouldCanonicalizeEquivalentDecimalEpochValues() {
        // given / when
        String normalized = SourceDateEpoch.normalize(" 00000123 ");

        // then
        assertEquals("123", normalized);
        assertEquals(123L, SourceDateEpoch.instant(normalized).getEpochSecond());
    }

    @Test
    void shouldRejectAnInvalidEpochValue() {
        // given / when / then
        assertThrows(GradleException.class, () -> SourceDateEpoch.normalize("tomorrow"));
    }
}
