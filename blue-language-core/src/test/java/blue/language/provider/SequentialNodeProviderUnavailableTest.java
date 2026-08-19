package blue.language.provider;

import blue.language.model.Node;
import blue.language.runtime.BlueLanguage;
import blue.language.runtime.LanguageProcessing;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Exact-resource evidence for transient provider suspension. */
final class SequentialNodeProviderUnavailableTest {

    private static final String REQUESTED =
            "EVJk3e7MLRhtTfMBNyrWYz1pWFXsbDTkPczeTviUuB4e";

    @Test
    void retainsRequestedExactBlueIdAcrossLegacyLookupBoundary() {
        NodeProvider unavailable = unavailableProvider();
        SequentialNodeProvider provider = new SequentialNodeProvider(
                Collections.singletonList(unavailable));

        ProviderUnavailableException failure = assertThrows(
                ProviderUnavailableException.class,
                () -> provider.fetchByBlueId(REQUESTED));

        assertEquals(REQUESTED,
                failure.requiredExactBlueId().orElseThrow(AssertionError::new));
        assertEquals("temporarily offline", failure.getMessage());
    }

    @Test
    void referenceResolutionPreservesTypedProviderSuspension() {
        ProviderUnavailableException failure;
        try (BlueLanguage language = BlueLanguage.builder()
                .nodeProvider(unavailableProvider())
                .build();
             LanguageProcessing.Scope scope =
                     language.processing().openScope()) {
            failure = assertThrows(
                    ProviderUnavailableException.class,
                    () -> scope.resolve(new Node().type(
                            new Node().blueId(REQUESTED))));
        }

        assertEquals(REQUESTED,
                failure.requiredExactBlueId().orElseThrow(AssertionError::new));
    }

    @Test
    void verifyingProviderRetainsRequestedExactBlueId() {
        ProviderUnavailableException failure = assertThrows(
                ProviderUnavailableException.class,
                () -> new VerifyingNodeProvider(unavailableProvider())
                        .fetchByBlueId(REQUESTED));

        assertEquals(REQUESTED,
                failure.requiredExactBlueId().orElseThrow(AssertionError::new));
    }

    @Test
    void runtimeTypeMatchingRetainsRequestedExactBlueId() {
        ProviderUnavailableException failure;
        try (BlueLanguage language = BlueLanguage.builder().build();
             LanguageProcessing.Scope scope = language.processing()
                     .openScope(unavailableProvider())) {
            FrozenNode reference = FrozenNode.fromNode(
                    new Node().blueId(REQUESTED));
            failure = assertThrows(
                    ProviderUnavailableException.class,
                    () -> scope.runtimeAccess()
                            .materializeTypeReferenceForMatching(reference));
        }

        assertEquals(REQUESTED,
                failure.requiredExactBlueId().orElseThrow(AssertionError::new));
    }

    @Test
    void validatesTypedIdentityAndKeepsLegacyDiagnosticOnlyForm() {
        assertThrows(IllegalArgumentException.class,
                () -> new ProviderUnavailableException(
                        "not-a-blue-id", "temporarily offline"));
        assertFalse(new ProviderUnavailableException("temporarily offline")
                .requiredExactBlueId().isPresent());
    }

    private static NodeProvider unavailableProvider() {
        return new NodeProvider() {
            @Override
            public List<Node> fetchByBlueId(String blueId) {
                return Collections.emptyList();
            }

            @Override
            public NodeProviderResult fetchResultByBlueId(String blueId) {
                return NodeProviderResult.unavailable("temporarily offline");
            }
        };
    }
}
