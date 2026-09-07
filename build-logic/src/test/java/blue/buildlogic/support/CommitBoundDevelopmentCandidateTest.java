package blue.buildlogic.support;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.gradle.api.GradleException;
import org.junit.jupiter.api.Test;

final class CommitBoundDevelopmentCandidateTest {
    private static final String HEAD = "0123456789abcdef0123456789abcdef01234567";
    private static final String OTHER = "89abcdef0123456789abcdef0123456789abcdef";

    @Test
    void acceptsOnlyCleanExactDevelopmentOrLocalRcSource() {
        for (String version : new String[] {"3.1.0-dev." + HEAD, "3.1.0-rc.24"}) {
            assertDoesNotThrow(() -> CommitBoundDevelopmentCandidate.verify(version, HEAD, HEAD, ""));
            assertThrows(GradleException.class,
                    () -> CommitBoundDevelopmentCandidate.verify(version, HEAD, OTHER, ""));
            assertThrows(GradleException.class,
                    () -> CommitBoundDevelopmentCandidate.verify(version, HEAD, HEAD, " M runtime.java"));
            assertThrows(GradleException.class,
                    () -> CommitBoundDevelopmentCandidate.verify(version, "not-a-commit", HEAD, ""));
        }
        for (String version : new String[] {"3.1.0", "3.1.0-rc.0", "3.1.0-rc.01",
                "3.2.0-rc.1", "3.1.0-rc.24-SNAPSHOT", "3.1.0-dev." + OTHER}) {
            assertThrows(GradleException.class,
                    () -> CommitBoundDevelopmentCandidate.verify(version, HEAD, HEAD, ""));
        }
    }
}
