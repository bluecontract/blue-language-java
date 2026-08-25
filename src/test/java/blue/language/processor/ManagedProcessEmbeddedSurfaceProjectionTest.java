package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Focused proofs for the managed Process Embedded surface projection. */
final class ManagedProcessEmbeddedSurfaceProjectionTest {

    private static final String CYCLIC_MASTER =
            "GX7CFU287wrZ7qw3LQG7gQi6UUoy1FFpM3tzupQJKi3N";

    @Test
    void shouldProjectSortedConcretePathsWithExactFieldContributionsWithoutGas() {
        // given
        Node explicitPaths = textItems("/single");
        Node collectionPaths = textItems("/children");
        Node document = document(
                explicitPaths,
                collectionPaths,
                new Node().name("single child"),
                new Node()
                        .properties("z", new Node().name("child z"))
                        .properties("a", new Node().name("child a")));
        try (DocumentProcessor owner = DocumentProcessor.builder().build();
             ManagedDocumentStepRuntime runtime =
                     new ManagedDocumentStepRuntime(owner)) {
            ContractBundle bundle = owner.contractLoader().load(
                    FrozenNode.fromResolvedNode(document), "/");
            EffectiveContractSnapshot embedded = processEmbedded(bundle);
            String explicitBlueId = embedded.headerFields().get(
                    ProcessorContractConstants.KEY_PATHS).blueId();
            String collectionBlueId = embedded.headerFields().get(
                    ProcessorContractConstants.KEY_COLLECTION_PATHS).blueId();
            long gasBefore = runtime.totalGas();

            // when
            List<ManagedProcessEmbeddedPath> projected =
                    runtime.projectManagedProcessEmbeddedSurface(document);
            List<ManagedProcessEmbeddedPath> absent =
                    runtime.projectManagedProcessEmbeddedSurface(
                            new Node().name("no declaration"));
            long gasAfter = runtime.totalGas();

            // then
            assertEquals(Arrays.asList(
                            new ManagedProcessEmbeddedPath(
                                    "/children/a", collectionBlueId),
                            new ManagedProcessEmbeddedPath(
                                    "/children/z", collectionBlueId),
                            new ManagedProcessEmbeddedPath(
                                    "/single", explicitBlueId)),
                    projected);
            assertTrue(absent.isEmpty());
            assertEquals(gasBefore, gasAfter);
            assertThrows(UnsupportedOperationException.class,
                    () -> projected.add(new ManagedProcessEmbeddedPath(
                            "/later", explicitBlueId)));
        }
    }

    @Test
    void shouldKeepDirectAndCollectionMemberCyclicReferencesOpaque() {
        // given
        Node document = document(
                textItems("/single"),
                textItems("/children"),
                new Node().blueId(CYCLIC_MASTER + "#0"),
                new Node().properties(
                        "member",
                        new Node().blueId(CYCLIC_MASTER + "#1")));
        try (DocumentProcessor owner = DocumentProcessor.builder().build();
             ManagedDocumentStepRuntime runtime =
                     new ManagedDocumentStepRuntime(owner)) {

            // when
            List<ManagedProcessEmbeddedPath> projected =
                    runtime.projectManagedProcessEmbeddedSurface(document);

            // then
            assertEquals(Arrays.asList(
                            "/children/member", "/single"),
                    absolutePaths(projected));
        }
    }

    @Test
    void shouldRejectAnOpaqueCollectionContainerWhoseKeysCannotBeEnumerated() {
        // given
        Node document = document(
                null,
                textItems("/children"),
                null,
                new Node().blueId(CYCLIC_MASTER + "#0"));
        try (DocumentProcessor owner = DocumentProcessor.builder().build();
             ManagedDocumentStepRuntime runtime =
                     new ManagedDocumentStepRuntime(owner)) {

            // when
            Runnable projection = () ->
                    runtime.projectManagedProcessEmbeddedSurface(document);

            // then
            SubscriptionSurfaceInvalidException failure = assertThrows(
                    SubscriptionSurfaceInvalidException.class,
                    projection::run);
            assertEquals(
                    ProcessorErrorCategory
                            .CyclicSetEmbeddedBoundaryUnsupported,
                    failure.diagnostic().category());
        }
    }

    private static Node document(
            Node explicitPaths,
            Node collectionPaths,
            Node single,
            Node children) {
        Node embedded = new Node().type(
                new Node().blueId(RuntimeBlueIds.PROCESS_EMBEDDED));
        if (explicitPaths != null) {
            embedded.properties(
                    ProcessorContractConstants.KEY_PATHS,
                    explicitPaths);
        }
        if (collectionPaths != null) {
            embedded.properties(
                    ProcessorContractConstants.KEY_COLLECTION_PATHS,
                    collectionPaths);
        }
        Node document = new Node()
                .name("managed surface")
                .contracts(new Node().properties(
                        ProcessorContractConstants.KEY_EMBEDDED,
                        embedded));
        if (single != null) {
            document.properties("single", single);
        }
        if (children != null) {
            document.properties("children", children);
        }
        return document;
    }

    private static Node textItems(String... values) {
        Node[] items = new Node[values.length];
        for (int index = 0; index < values.length; index++) {
            items[index] = new Node().value(values[index]);
        }
        return new Node().items(Arrays.asList(items));
    }

    private static List<String> absolutePaths(
            List<ManagedProcessEmbeddedPath> projected) {
        List<String> paths = new ArrayList<String>(projected.size());
        for (ManagedProcessEmbeddedPath path : projected) {
            paths.add(path.absolutePath());
        }
        return Collections.unmodifiableList(paths);
    }

    private static EffectiveContractSnapshot processEmbedded(
            ContractBundle bundle) {
        EffectiveContractSnapshot result = null;
        for (EffectiveContractSnapshot snapshot
                : bundle.effectiveContractSnapshots()) {
            if (!EffectiveContractSnapshotConstants.Role.PROCESS_EMBEDDED
                    .equals(snapshot.role())) {
                continue;
            }
            if (result != null) {
                throw new AssertionError(
                        "Expected one Process Embedded snapshot");
            }
            result = snapshot;
        }
        if (result == null) {
            throw new AssertionError(
                    "Expected one Process Embedded snapshot");
        }
        return result;
    }
}
