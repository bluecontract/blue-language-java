package blue.language.processor.closure;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Contracts-owned identity construction and assertion proofs. */
final class ManagedOccurrenceBindingFactoryTest {
    private static final String POLICY =
            "sha256:c1e8d880499cbafc595e1fb213ee73acc6ddb8d1d9850c7ddff2224c88a03d35";
    private static final String TARGET_BLUE_ID =
            "4ZMfXZbSNVnEaqHVwYyYFHSfJ4JYs6VbR2oLZNqNkScr#1";
    private static final DocumentId SOURCE = new DocumentId("a");
    private static final DocumentId TARGET = new DocumentId("b");
    private static final ScopeAddress ADDRESS =
            ScopeAddress.embedded("/b", 1L);

    @Test
    void derivesTheReleasedOccurrenceAndBindingIdentities() {
        ManagedOccurrenceBinding binding = ManagedOccurrenceBinding.derived(
                POLICY,
                SOURCE,
                ADDRESS,
                TARGET,
                TARGET_BLUE_ID,
                true,
                null);

        assertEquals(
                "sha256:f5d1cd1ca17ac4fa6547d53f85dadb18f4b37e1bca42588f5cb4fb9090023eca",
                binding.occurrenceIdentity());
        assertEquals(
                "sha256:8e0adfdc7abea06d373ff4aa63d4b828da81abc01479d4a94cc7afdfe7b0e6e8",
                binding.bindingIdentity());
    }

    @Test
    void verifiesAssertionsAndRejectsEitherIdentityMismatch() {
        ManagedOccurrenceBinding binding = ManagedOccurrenceBinding.verified(
                "sha256:f5d1cd1ca17ac4fa6547d53f85dadb18f4b37e1bca42588f5cb4fb9090023eca",
                "sha256:8e0adfdc7abea06d373ff4aa63d4b828da81abc01479d4a94cc7afdfe7b0e6e8",
                POLICY,
                SOURCE,
                ADDRESS,
                TARGET,
                TARGET_BLUE_ID,
                true,
                null);
        assertEquals(SOURCE, binding.sourceDocumentId());

        String wrong =
                "sha256:0000000000000000000000000000000000000000000000000000000000000000";
        assertThrows(IllegalArgumentException.class,
                () -> ManagedOccurrenceBinding.verified(
                        wrong,
                        binding.bindingIdentity(),
                        POLICY,
                        SOURCE,
                        ADDRESS,
                        TARGET,
                        TARGET_BLUE_ID,
                        true,
                        null));
        assertThrows(IllegalArgumentException.class,
                () -> ManagedOccurrenceBinding.verified(
                        binding.occurrenceIdentity(),
                        wrong,
                        POLICY,
                        SOURCE,
                        ADDRESS,
                        TARGET,
                        TARGET_BLUE_ID,
                        true,
                        null));
    }

    @Test
    void factoriesRetainTheClosedActiveHistoricalInvariant() {
        assertThrows(IllegalArgumentException.class,
                () -> ManagedOccurrenceBinding.derived(
                        POLICY,
                        SOURCE,
                        ADDRESS,
                        TARGET,
                        TARGET_BLUE_ID,
                        true,
                        0L));
        assertThrows(IllegalArgumentException.class,
                () -> ManagedOccurrenceBinding.derived(
                        POLICY,
                        SOURCE,
                        ScopeAddress.root(),
                        TARGET,
                        TARGET_BLUE_ID,
                        false,
                        null));
    }
}
