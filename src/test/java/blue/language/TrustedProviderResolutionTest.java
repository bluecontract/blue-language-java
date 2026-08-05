package blue.language;

import blue.language.api.BlueCachePolicy;
import blue.language.api.BlueCacheStats;
import blue.language.api.BlueLanguageErrorCategory;
import blue.language.api.BlueLanguageErrorClassifier;
import blue.language.api.BlueOperationLimits;
import blue.language.api.BlueOperationOutcome;
import blue.language.api.BlueOperationResult;
import blue.language.api.BlueViewPath;
import blue.language.runtime.LanguageRuntimeAccess;
import blue.language.provider.NodeProvider;

import blue.language.model.Node;
import blue.language.api.NodeProviderOutcome;
import blue.language.provider.NodeProviderResult;
import blue.language.provider.ProviderEvidenceVerifier;
import blue.language.provider.ProviderMode;
import blue.language.provider.SequentialNodeProvider;
import blue.language.provider.SourceProviderEnvironment;
import blue.language.registry.BlueCoreTypeRegistry;
import blue.language.registry.NodeProviderWrapper;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import static blue.language.processor.FailureCapture.captureFailure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void shouldRejectNonDirectContentThroughDeprecatedUnverifiedWrapper() {
        // given
        Fixture fixture = new Fixture();
        AtomicInteger fetches = new AtomicInteger();
        Blue blue = new Blue(NodeProviderWrapper.wrap(blueId -> {
            fetches.incrementAndGet();
            return fixture.requestedBlueId.equals(blueId)
                    ? Collections.singletonList(fixture.mismatchedType.clone())
                    : null;
        }));

        // when
        Throwable failure = captureFailure(
                () -> blue.resolve(fixture.instance()));

        // then
        assertInstanceOf(RuntimeException.class, failure);
        assertEquals(BlueLanguageErrorCategory.ProviderBlueIdMismatch,
                BlueLanguageErrorClassifier.classify(failure));
        assertTrue(messageChain(failure).contains(fixture.requestedBlueId));
        assertEquals(1, fetches.get());
    }

    @Test
    void shouldResolveExactDirectProviderContentNormally() {
        // given
        Fixture fixture = new Fixture();
        Blue blue = new Blue(blueId -> fixture.requestedBlueId.equals(blueId)
                ? Collections.singletonList(fixture.requestedType.clone())
                : null);

        // when
        Node resolved = blue.resolve(fixture.instance());

        // then
        assertEquals("verified", resolved.getAsText("/fixed"));
    }

    @Test
    void shouldFallThroughToExactFallbackAfterNullMiss() {
        // given
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

        // when
        Node resolved = blue.resolve(fixture.instance());

        // then
        assertEquals("verified", resolved.getAsText("/fixed"));
        assertEquals(1, fallbackFetches.get());
    }

    @Test
    void shouldTreatEmptyLegacyResultAsNotFoundAndFallThrough() {
        // given
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

        // when
        Node resolved = blue.resolve(fixture.instance());

        // then
        assertEquals("verified", resolved.getAsText("/fixed"));
        assertEquals(1, fallbackFetches.get());
    }

    @Test
    void shouldStopBeforeFallbackWhenEvidenceIsInvalid() {
        // given
        Fixture fixture = new Fixture();
        AtomicInteger fallbackFetches = new AtomicInteger();
        Blue blue = new Blue(new SequentialNodeProvider(
                blueId -> Collections.singletonList(fixture.mismatchedType.clone()),
                blueId -> {
                    fallbackFetches.incrementAndGet();
                    return Collections.singletonList(fixture.requestedType.clone());
                }));

        // when
        Throwable failure = captureFailure(
                () -> blue.resolve(fixture.instance()));

        // then
        assertInstanceOf(RuntimeException.class, failure);
        assertEquals(BlueLanguageErrorCategory.ProviderBlueIdMismatch,
                BlueLanguageErrorClassifier.classify(failure));
        assertEquals(0, fallbackFetches.get());
    }

    @Test
    void shouldTreatUnavailableOutcomeAsTerminalAndDistinctFromNotFound() {
        // given
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

        // when
        Throwable failure = captureFailure(
                () -> blue.resolve(fixture.instance()));
        NodeProviderOutcome outcome = blue.getNodeProvider()
                .fetchResultByBlueId(fixture.requestedBlueId)
                .outcome();

        // then
        assertEquals(NodeProviderOutcome.UNAVAILABLE, outcome);
        assertInstanceOf(RuntimeException.class, failure);
        assertTrue(messageChain(failure).contains("temporary source outage"));
        assertEquals(0, fallbackFetches.get());
    }

    @Test
    void shouldRequireExactEnvironmentBindingForSourceDocumentContent() {
        // given
        Blue blue = new Blue();
        Node source = new Node()
                .blue(new Node().properties("imports", new Node()))
                .properties("payload", new Node().value("source document"));
        String requestedBlueId = blue.calculateSourceDocumentBlueId(source);
        SourceProviderEnvironment exact = new SourceProviderEnvironment(
                blue.languageVersion(),
                SourceProviderEnvironment.LANGUAGE_1_0_RELEASE_IDENTITY,
                ProviderEvidenceVerifier.preprocessingEnvironmentIdentity(blue),
                BlueCoreTypeRegistry.INSTANCE.packageIdentity(),
                ProviderEvidenceVerifier.sourceEvidenceIdentity(source));
        SourceProviderEnvironment mismatched = new SourceProviderEnvironment(
                blue.languageVersion(),
                SourceProviderEnvironment.LANGUAGE_1_0_RELEASE_IDENTITY,
                ProviderEvidenceVerifier.preprocessingEnvironmentIdentity(blue),
                BlueCoreTypeRegistry.INSTANCE.packageIdentity(),
                ProviderEvidenceVerifier.sourceEvidenceIdentity(source)
                        + "-different");

        // when
        Throwable directInputFailure = captureFailure(
                () -> ProviderEvidenceVerifier.verify(
                        requestedBlueId, source, ProviderMode.BLUE_ID_INPUT,
                        blue, null));
        Throwable sourceDocumentFailure = captureFailure(
                () -> ProviderEvidenceVerifier.verify(
                        requestedBlueId, source, ProviderMode.SOURCE_DOCUMENT,
                        blue, exact));
        Throwable mismatchedEnvironmentFailure = captureFailure(
                () -> ProviderEvidenceVerifier.verify(
                        requestedBlueId, source, ProviderMode.SOURCE_DOCUMENT,
                        blue, mismatched));

        // then
        assertInstanceOf(IllegalArgumentException.class, directInputFailure);
        assertNull(sourceDocumentFailure);
        assertInstanceOf(
                IllegalArgumentException.class,
                mismatchedEnvironmentFailure);
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
