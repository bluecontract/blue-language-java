package blue.language.processor;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.processor.util.ProcessorContractConstants;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Verifies Process Embedded validation before an otherwise terminal no-match. */
final class EmbeddedSurfacePreflightTest {

    private static final String LESSONS_KEY = "lessons";
    private static final String LESSON_A_KEY = "lesson-a";
    private static final String VALUE_KEY = "x";
    private static final String EVENT_KIND_KEY = "kind";
    private static final String EVENT_KIND_UNMATCHED = "unmatched";
    private static final String EVENT_ORDER_TOKEN = "embedded-preflight";
    private static final String LESSONS_POINTER = "/lessons";
    private static final String LESSON_A_POINTER = "/lessons/lesson-a";
    private static final String WILDCARD_POINTER = "/lessons/*";
    private static final String CONTRACTS_POINTER = "/contracts";
    private static final String CYCLIC_MEMBER_BLUE_ID =
            "GX7CFU287wrZ7qw3LQG7gQi6UUoy1FFpM3tzupQJKi3N#0";

    @Test
    void shouldRejectListCollectionTargetBeforeNoMatch() {
        // given
        Node root = rootWithEmbedded(
                new Node().properties(
                        LESSONS_KEY,
                        new Node().items(objectMember())),
                Collections.<String>emptyList(),
                Collections.singletonList(LESSONS_POINTER));

        // when
        DocumentProcessingResult result = processNoMatch(root);

        // then
        assertSurfaceFailure(
                root,
                result,
                ProcessorErrorCategory.EmbeddedCollectionMustBeObject);
    }

    @Test
    void shouldRejectNonObjectCollectionMemberBeforeNoMatch() {
        // given
        Node root = rootWithEmbedded(
                new Node().properties(
                        LESSONS_KEY,
                        new Node().properties(
                                LESSON_A_KEY,
                                new Node().value(1))),
                Collections.<String>emptyList(),
                Collections.singletonList(LESSONS_POINTER));

        // when
        DocumentProcessingResult result = processNoMatch(root);

        // then
        assertSurfaceFailure(
                root,
                result,
                ProcessorErrorCategory
                        .EmbeddedCollectionMemberMustBeObject);
    }

    @Test
    void shouldRejectReservedCollectionPathBeforeNoMatch() {
        // given
        Node root = rootWithEmbedded(
                new Node(),
                Collections.<String>emptyList(),
                Collections.singletonList(CONTRACTS_POINTER));

        // when
        DocumentProcessingResult result = processNoMatch(root);

        // then
        assertSurfaceFailure(
                root,
                result,
                ProcessorErrorCategory.InvalidEmbeddedCollectionPath);
    }

    @Test
    void shouldRejectWildcardEmbeddedPathBeforeNoMatch() {
        // given
        Node root = rootWithEmbedded(
                new Node().properties(
                        LESSONS_KEY,
                        new Node().properties(
                                LESSON_A_KEY,
                                objectMember())),
                Collections.singletonList(WILDCARD_POINTER),
                Collections.<String>emptyList());

        // when
        DocumentProcessingResult result = processNoMatch(root);

        // then
        assertSurfaceFailure(
                root,
                result,
                ProcessorErrorCategory.EmbeddedPathSelectorUnsupported);
    }

    @Test
    void shouldRejectCyclicCollectionMemberBeforeNoMatch() {
        // given
        Node root = rootWithEmbedded(
                new Node().properties(
                        LESSONS_KEY,
                        new Node().properties(
                                LESSON_A_KEY,
                                new Node().blueId(
                                        CYCLIC_MEMBER_BLUE_ID))),
                Collections.<String>emptyList(),
                Collections.singletonList(LESSONS_POINTER));

        // when
        DocumentProcessingResult result = processNoMatch(root);

        // then
        assertSurfaceFailure(
                root,
                result,
                ProcessorErrorCategory
                        .CyclicSetEmbeddedBoundaryUnsupported);
    }

    @Test
    void shouldRejectOverlappingEmbeddedDeclarationsBeforeNoMatch() {
        // given
        Node root = rootWithEmbedded(
                new Node().properties(
                        LESSONS_KEY,
                        new Node().properties(
                                LESSON_A_KEY,
                                objectMember())),
                Collections.singletonList(LESSON_A_POINTER),
                Collections.singletonList(LESSONS_POINTER));

        // when
        DocumentProcessingResult result = processNoMatch(root);

        // then
        assertSurfaceFailure(
                root,
                result,
                ProcessorErrorCategory.OverlappingEmbeddedDeclaration);
    }

    private static DocumentProcessingResult processNoMatch(Node root) {
        Node event = new Node().properties(
                EVENT_KIND_KEY,
                new Node().value(EVENT_KIND_UNMATCHED));
        ExternalDeliveryPlan plan = ExternalDeliveryPlan.builder()
                .revisions(7L, 7L)
                .eventOrderKey(ExternalOrderKey.of(
                        Collections.<Object>singletonList(
                                EVENT_ORDER_TOKEN)))
                .activeSubscriptionIntervals(
                        Collections.<SubscriptionDelta.Entry>emptyList())
                .exactRuntimeState()
                .build();
        DocumentProcessor processor = DocumentProcessor.builder()
                .withExternalDeliveryPlanDeriver(
                        (ignoredRoot, ignoredEvent) -> plan)
                .build();
        VerifiedExecutionEvidence evidence = plan.bind(
                root,
                event,
                processor.runtimeRegistryIdentity());
        try {
            return processor.processDocumentForPlatformCommit(
                    root,
                    event,
                    evidence)
                    .processResult();
        } finally {
            processor.close();
        }
    }

    private static Node rootWithEmbedded(
            Node root,
            List<String> paths,
            List<String> collectionPaths) {
        Node embedded = new Node().type(new Node().blueId(
                RuntimeBlueIds.PROCESS_EMBEDDED));
        if (!paths.isEmpty()) {
            embedded.properties(
                    ProcessorContractConstants.KEY_PATHS,
                    textList(paths));
        }
        if (!collectionPaths.isEmpty()) {
            embedded.properties(
                    ProcessorContractConstants.KEY_COLLECTION_PATHS,
                    textList(collectionPaths));
        }
        return root.contracts(new Node().properties(
                ProcessorContractConstants.KEY_EMBEDDED,
                embedded));
    }

    private static Node textList(List<String> values) {
        Node[] items = new Node[values.size()];
        for (int index = 0; index < values.size(); index++) {
            items[index] = new Node().value(values.get(index));
        }
        return new Node().items(Arrays.asList(items));
    }

    private static Node objectMember() {
        return new Node().properties(
                VALUE_KEY,
                new Node().value(1));
    }

    private static void assertSurfaceFailure(
            Node input,
            DocumentProcessingResult result,
            ProcessorErrorCategory category) {
        assertEquals(
                ProcessorStatus.SUBSCRIPTION_SURFACE_INVALID,
                result.status(),
                result.diagnostic() != null
                        ? result.diagnostic().category()
                        + ": "
                        + result.diagnostic().message()
                        : "missing diagnostic");
        assertNotNull(result.diagnostic());
        assertEquals(category, result.diagnostic().category());
        assertEquals(
                DirectBlueIdCalculator.calculateBlueId(input),
                DirectBlueIdCalculator.calculateBlueId(
                        result.document()));
    }
}
