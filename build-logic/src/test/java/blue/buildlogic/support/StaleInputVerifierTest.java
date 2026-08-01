package blue.buildlogic.support;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.gradle.api.GradleException;
import org.junit.jupiter.api.Test;

final class StaleInputVerifierTest {

    private static final String FIRST = "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String SECOND = "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    @Test
    void shouldAcceptEvidenceThatMatchesCurrentInputs() {
        // given / when / then
        assertDoesNotThrow(() -> StaleInputVerifier.assertCurrent(FIRST, FIRST));
    }

    @Test
    void shouldRejectEvidenceAfterAnInputChanges() {
        // given / when / then
        assertThrows(GradleException.class, () -> StaleInputVerifier.assertCurrent(FIRST, SECOND));
    }

    @Test
    void shouldReadTheRecordedIdentityFromCanonicalEvidence() {
        // given
        String evidence = "{\"schema\":\"x\",\"sourceInputIdentity\":\"" + FIRST + "\"}\n";

        // when
        String identity = StaleInputVerifier.sourceIdentityFrom(evidence);

        // then
        assertEquals(FIRST, identity);
    }
}
