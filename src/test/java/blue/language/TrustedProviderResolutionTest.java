package blue.language;

import blue.language.model.Node;
import blue.language.provider.NodeProviderOutcome;
import blue.language.provider.NodeProviderResult;
import blue.language.provider.ProviderEvidenceVerifier;
import blue.language.provider.ProviderMode;
import blue.language.provider.SequentialNodeProvider;
import blue.language.provider.SourceProviderEnvironment;
import blue.language.registry.BlueCoreTypeRegistry;
import blue.language.utils.NodeProviderWrapper;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compatibility coverage for the pre-1.0 "trusted provider" entry point.
 *
 * <p>Language 1.0 has no ambient trust bit: ordinary providers are verified
 * as direct BlueId Input, while Source Documents require an explicitly bound
 * provider mode.</p>
 */
class TrustedProviderResolutionTest {

    @Test
    void deprecatedUnverifiedWrapperStillRejectsNonDirectContent() {
        Fixture fixture = new Fixture();
        AtomicInteger fetches = new AtomicInteger();
        Blue blue = new Blue(NodeProviderWrapper.unverified(blueId -> {
            fetches.incrementAndGet();
            return fixture.requestedBlueId.equals(blueId)
                    ? Collections.singletonList(fixture.mismatchedType.clone())
                    : null;
        }));

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> blue.resolve(fixture.instance()));

        assertEquals(BlueLanguageErrorCategory.ProviderBlueIdMismatch,
                BlueLanguageErrorClassifier.classify(failure));
        assertTrue(messageChain(failure).contains(fixture.requestedBlueId));
        assertEquals(1, fetches.get());
    }

    @Test
    void exactDirectProviderContentResolvesNormally() {
        Fixture fixture = new Fixture();
        Blue blue = new Blue(blueId -> fixture.requestedBlueId.equals(blueId)
                ? Collections.singletonList(fixture.requestedType.clone())
                : null);

        Node resolved = blue.resolve(fixture.instance());

        assertEquals("verified", resolved.getAsText("/fixed"));
    }

    @Test
    void nullMissFallsThroughToExactFallback() {
        Fixture fixture = new Fixture();
        AtomicInteger fallbackFetches = new AtomicInteger();
        Blue blue = new Blue(new SequentialNodeProvider(
                blueId -> null,
                blueId -> {
                    fallbackFetches.incrementAndGet();
                    return fixture.requestedBlueId.equals(blueId)
                            ? Collections.singletonList(fixture.requestedType.clone())
                            : null;
                }));

        Node resolved = blue.resolve(fixture.instance());

        assertEquals("verified", resolved.getAsText("/fixed"));
        assertEquals(1, fallbackFetches.get());
    }

    @Test
    void emptyLegacyResultIsNotFoundAndFallsThrough() {
        Fixture fixture = new Fixture();
        AtomicInteger fallbackFetches = new AtomicInteger();
        Blue blue = new Blue(new SequentialNodeProvider(
                blueId -> Collections.emptyList(),
                blueId -> {
                    fallbackFetches.incrementAndGet();
                    return fixture.requestedBlueId.equals(blueId)
                            ? Collections.singletonList(fixture.requestedType.clone())
                            : null;
                }));

        Node resolved = blue.resolve(fixture.instance());

        assertEquals("verified", resolved.getAsText("/fixed"));
        assertEquals(1, fallbackFetches.get());
    }

    @Test
    void invalidEvidenceIsTerminalAndCannotReachFallback() {
        Fixture fixture = new Fixture();
        AtomicInteger fallbackFetches = new AtomicInteger();
        Blue blue = new Blue(new SequentialNodeProvider(
                blueId -> Collections.singletonList(fixture.mismatchedType.clone()),
                blueId -> {
                    fallbackFetches.incrementAndGet();
                    return Collections.singletonList(fixture.requestedType.clone());
                }));

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> blue.resolve(fixture.instance()));

        assertEquals(BlueLanguageErrorCategory.ProviderBlueIdMismatch,
                BlueLanguageErrorClassifier.classify(failure));
        assertEquals(0, fallbackFetches.get());
    }

    @Test
    void unavailableOutcomeIsTerminalAndDistinctFromNotFound() {
        Fixture fixture = new Fixture();
        AtomicInteger fallbackFetches = new AtomicInteger();
        NodeProvider unavailable = new NodeProvider() {
            @Override
            public java.util.List<Node> fetchByBlueId(String blueId) {
                throw new AssertionError("structured provider outcome must be used");
            }

            @Override
            public NodeProviderResult fetchResultByBlueId(String blueId) {
                return NodeProviderResult.unavailable("temporary source outage");
            }
        };
        Blue blue = new Blue(new SequentialNodeProvider(
                unavailable,
                blueId -> {
                    fallbackFetches.incrementAndGet();
                    return Collections.singletonList(fixture.requestedType.clone());
                }));

        assertEquals(NodeProviderOutcome.UNAVAILABLE,
                blue.getNodeProvider()
                        .fetchResultByBlueId(
                                fixture.requestedBlueId)
                        .outcome());
        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> blue.resolve(fixture.instance()));

        assertTrue(messageChain(failure).contains("temporary source outage"));
        assertEquals(0, fallbackFetches.get());
    }

    @Test
    void sourceDocumentContentRequiresExactEnvironmentBinding() {
        Blue blue = new Blue();
        Node source = new Node()
                .blue(new Node().properties("imports", new Node()))
                .properties("payload", new Node().value("source document"));
        String requestedBlueId = blue.calculateSemanticBlueId(source);
        SourceProviderEnvironment exact = new SourceProviderEnvironment(
                blue.languageVersion(),
                SourceProviderEnvironment.LANGUAGE_1_0_RELEASE_IDENTITY,
                ProviderEvidenceVerifier.preprocessingEnvironmentIdentity(blue),
                BlueCoreTypeRegistry.INSTANCE.packageIdentity(),
                ProviderEvidenceVerifier.sourceEvidenceIdentity(source));

        assertThrows(IllegalArgumentException.class,
                () -> ProviderEvidenceVerifier.verify(
                        requestedBlueId, source, ProviderMode.BLUE_ID_INPUT,
                        blue, null));
        assertDoesNotThrow(() -> ProviderEvidenceVerifier.verify(
                requestedBlueId, source, ProviderMode.SOURCE_DOCUMENT,
                blue, exact));
        assertThrows(IllegalArgumentException.class,
                () -> ProviderEvidenceVerifier.verify(
                        requestedBlueId, source, ProviderMode.SOURCE_DOCUMENT,
                        blue, new SourceProviderEnvironment(
                                blue.languageVersion(),
                                SourceProviderEnvironment.LANGUAGE_1_0_RELEASE_IDENTITY,
                                ProviderEvidenceVerifier.preprocessingEnvironmentIdentity(blue),
                                BlueCoreTypeRegistry.INSTANCE.packageIdentity(),
                                ProviderEvidenceVerifier.sourceEvidenceIdentity(source)
                                        + "-different")));
    }

    private static String messageChain(Throwable failure) {
        StringBuilder result = new StringBuilder();
        Throwable current = failure;
        while (current != null) {
            if (current.getMessage() != null) {
                result.append(current.getMessage()).append('\n');
            }
            current = current.getCause();
        }
        return result.toString();
    }

    private static Node reference(String blueId) {
        return new Node().blueId(blueId);
    }

    private static final class Fixture {
        private final Node requestedType = new Node().name("Requested Type")
                .properties("fixed", new Node().value("verified"));
        private final Node mismatchedType = new Node().name("Different Type")
                .properties("fixed", new Node().value("unverified"));
        private final String requestedBlueId =
                new Blue().calculateBlueId(requestedType);

        private Node instance() {
            return new Node().type(reference(requestedBlueId));
        }
    }
}
