package blue.language.processor;

import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

final class ProtectedStateGuardTest {

    @Test
    void shouldVerifyOrdinaryApplicationStateMayChange() {
        // given
        FrozenNode before = frozen(
                new Node().properties("value", new Node().value(0)));
        // when
        FrozenNode after = frozen(
                new Node().properties("value", new Node().value(1)));
        Throwable failure = FailureCapture.captureFailure(
                () -> ProtectedStateGuard.verifyUnchanged(
                        before, before, after, after));

        // then
        assertNull(failure);
    }

    @Test
    void shouldVerifyDirectHistoryStateCannotChange() {
        // given
        String[] markerKeys = {
                "initialized", "terminated", "checkpoint"
        };

        // when
        Map<String, ProcessorFailureException> failures =
                new LinkedHashMap<>();
        for (String key : markerKeys) {
            Node beforeNode = new Node().contracts(new Node());
            Node afterNode = new Node().contracts(
                    new Node().properties(
                            key,
                            new Node().properties(
                                    "identity",
                                    new Node().value(key))));

            ProcessorFailureException failure =
                    FailureCapture.captureFailure(
                    () -> ProtectedStateGuard.verifyUnchanged(
                            frozen(beforeNode),
                            frozen(beforeNode),
                            frozen(afterNode),
                            frozen(afterNode)));
            failures.put(key, failure);
        }

        // then
        assertEquals(markerKeys.length, failures.size());
        for (Map.Entry<String, ProcessorFailureException> entry
                : failures.entrySet()) {
            assertNotNull(entry.getValue(), entry.getKey());
            assertEquals(
                    ProcessorErrorCategory
                            .ProtectedProcessorStateMutation,
                    entry.getValue().errorCategory(),
                    entry.getKey());
        }
    }

    @Test
    void shouldVerifyDirectHistoryComparesCanonicalIdentityNotResolvedValue() {
        // given
        String beforeIdentity = FrozenNode.fromNode(
                new Node().properties(
                        "subject",
                        new Node().value("before"))).blueId();
        String afterIdentity = FrozenNode.fromNode(
                new Node().properties(
                        "subject",
                        new Node().value("after"))).blueId();
        Node beforeNode = new Node().contracts(
                new Node().properties(
                        "checkpoint",
                        new Node().blueId(beforeIdentity)));
        Node afterNode = new Node().contracts(
                new Node().properties(
                        "checkpoint",
                        new Node().blueId(afterIdentity)));
        FrozenNode sameResolved = frozen(
                new Node().contracts(
                        new Node().properties(
                                "checkpoint",
                                new Node().properties(
                                        "subject",
                                        new Node().value("E1")))));

        // when
        ProcessorFailureException failure =
                FailureCapture.captureFailure(
                () -> ProtectedStateGuard.verifyUnchanged(
                        FrozenNode.fromNode(beforeNode),
                        sameResolved,
                        FrozenNode.fromNode(afterNode),
                        sameResolved));

        // then
        assertNotNull(failure);
        assertEquals(
                ProcessorErrorCategory.ProtectedProcessorStateMutation,
                failure.errorCategory());
    }

    @Test
    void shouldVerifyResolvedOnlyHistoryStateIsNotProtectedBecauseMarkersAreDirect() {
        // given
        FrozenNode canonical = frozen(
                new Node().type(new Node().blueId(
                        "11111111111111111111111111111111")));
        FrozenNode resolvedBefore = frozen(new Node());
        // when
        FrozenNode resolvedAfter = frozen(
                new Node().contracts(
                        new Node().properties(
                                "terminated",
                                new Node().properties(
                                "reason",
                                new Node().value("done")))));
        Throwable failure = FailureCapture.captureFailure(
                () -> ProtectedStateGuard.verifyUnchanged(
                        canonical,
                        resolvedBefore,
                        canonical,
                        resolvedAfter));

        // then
        assertNull(failure);
    }

    @Test
    void shouldVerifyExactProcessEmbeddedPathsExceptionPreservesOtherFields() {
        // given
        FrozenNode before = frozen(rootWithEmbedded(
                new Node().items(new Node().value("/one")),
                new Node().value(7)));
        // when
        FrozenNode after = frozen(rootWithEmbedded(
                new Node().items(new Node().value("/two")),
                new Node().value(7)));
        Throwable failure = FailureCapture.captureFailure(
                () -> ProtectedStateGuard.verifyUnchanged(
                        before, before, after, after));

        // then
        assertNull(failure);
    }

    @Test
    void shouldVerifyExactProcessEmbeddedCollectionPathsExceptionPreservesOtherFields() {
        // given
        Node beforeNode = rootWithEmbedded(
                new Node().items(new Node().value("/one")),
                new Node().value(7));
        beforeNode.getContracts().getProperties().get("embedded").properties(
                "collectionPaths",
                new Node().items(new Node().value("/collections-one")));
        Node afterNode = beforeNode.clone();
        afterNode.getContracts().getProperties().get("embedded").properties(
                "collectionPaths",
                new Node().items(new Node().value("/collections-two")));

        // when
        Throwable failure = FailureCapture.captureFailure(
                () -> ProtectedStateGuard.verifyUnchanged(
                        frozen(beforeNode),
                        frozen(beforeNode),
                        frozen(afterNode),
                        frozen(afterNode)));

        // then
        assertNull(failure);
    }

    @Test
    void shouldVerifyProcessEmbeddedNonPathFieldCannotChange() {
        // given
        FrozenNode before = frozen(rootWithEmbedded(
                new Node().items(new Node().value("/one")),
                new Node().value(7)));
        FrozenNode after = frozen(rootWithEmbedded(
                new Node().items(new Node().value("/two")),
                new Node().value(8)));

        // when
        ProcessorFailureException failure =
                FailureCapture.captureFailure(
                () -> ProtectedStateGuard.verifyUnchanged(
                        before, before, after, after));

        // then
        assertNotNull(failure);
        assertEquals(
                ProcessorErrorCategory.ProtectedProcessorStateMutation,
                failure.errorCategory());
    }

    @Test
    void shouldVerifyProcessEmbeddedEffectiveTypeCannotChange() {
        // given
        Node beforeNode = rootWithEmbedded(
                new Node().items(new Node().value("/one")),
                new Node().value(7));
        Node afterNode = rootWithEmbedded(
                new Node().items(new Node().value("/one")),
                new Node().value(7));
        afterNode.getContracts()
                .getProperties()
                .get("embedded")
                .type(new Node().blueId(
                        "22222222222222222222222222222222"));

        // when
        ProcessorFailureException failure =
                FailureCapture.captureFailure(
                () -> ProtectedStateGuard.verifyUnchanged(
                        frozen(beforeNode),
                        frozen(beforeNode),
                        frozen(afterNode),
                        frozen(afterNode)));

        // then
        assertNotNull(failure);
        assertEquals(
                ProcessorErrorCategory.ProtectedProcessorStateMutation,
                failure.errorCategory());
    }

    @Test
    void shouldVerifyUnrelatedNestedBusinessObjectContractsAreNotScopeState() {
        // given
        Node beforeNode = new Node().properties(
                "business",
                new Node().properties(
                        "nested",
                        new Node().contracts(
                                new Node().properties(
                                        "checkpoint",
                                        new Node().properties(
                                                "subject",
                                                new Node().value("before"))))));
        Node afterNode = beforeNode.clone();
        // when
        afterNode.getProperties()
                .get("business")
                .getProperties()
                .get("nested")
                .getContracts()
                .properties(
                        "checkpoint",
                        new Node().properties(
                                "subject",
                                new Node().value("after")));
        Throwable failure = FailureCapture.captureFailure(
                () -> ProtectedStateGuard.verifyUnchanged(
                        frozen(beforeNode),
                        frozen(beforeNode),
                        frozen(afterNode),
                        frozen(afterNode)));

        // then
        assertNull(failure);
    }

    @Test
    void shouldVerifyContractsInsideBusinessListItemsAreNotScopeState() {
        // given
        Node beforeNode = new Node().properties(
                "rows",
                new Node().items(
                        new Node().contracts(
                                new Node().properties(
                                        "initialized",
                                        new Node().properties(
                                                "documentId",
                                                new Node().value("before"))))));
        Node afterNode = beforeNode.clone();
        // when
        afterNode.getProperties()
                .get("rows")
                .getItems()
                .get(0)
                .getContracts()
                .properties(
                        "initialized",
                        new Node().properties(
                                "documentId",
                                new Node().value("after")));
        Throwable failure = FailureCapture.captureFailure(
                () -> ProtectedStateGuard.verifyUnchanged(
                        frozen(beforeNode),
                        frozen(beforeNode),
                        frozen(afterNode),
                        frozen(afterNode)));

        // then
        assertNull(failure);
    }

    @Test
    void shouldVerifyMalformedEmbeddedListRouteDoesNotTurnListItemIntoScope() {
        // given
        Node beforeNode = rootWithEmbedded(
                new Node().items(new Node().value("/rows/0")),
                new Node().value(7))
                .properties(
                        "rows",
                        new Node().items(
                                childWithMarker(
                                        "checkpoint", "before")));
        Node afterNode = beforeNode.clone();
        // when
        afterNode.getProperties()
                .get("rows")
                .getItems()
                .get(0)
                .getContracts()
                .properties(
                        "checkpoint",
                        new Node().properties(
                                "value",
                                new Node().value("after")));
        Throwable failure = FailureCapture.captureFailure(
                () -> ProtectedStateGuard.verifyUnchanged(
                        frozen(beforeNode),
                        frozen(beforeNode),
                        frozen(afterNode),
                        frozen(afterNode)));

        // then
        assertNull(failure);
    }

    @Test
    void shouldVerifyDirectHistoryAtDeclaredEmbeddedScopeCannotChange() {
        // given
        Node beforeNode = rootWithEmbeddedChild(
                childWithMarker("checkpoint", "before"));
        Node afterNode = rootWithEmbeddedChild(
                childWithMarker("checkpoint", "after"));

        // when
        ProcessorFailureException failure =
                FailureCapture.captureFailure(
                () -> ProtectedStateGuard.verifyUnchanged(
                        frozen(beforeNode),
                        frozen(beforeNode),
                        frozen(afterNode),
                        frozen(afterNode)));

        // then
        assertNotNull(failure);
        assertEquals(
                ProcessorErrorCategory.ProtectedProcessorStateMutation,
                failure.errorCategory());
    }

    @Test
    void shouldVerifyWholeEmbeddedChildRemovalMayDropItsDirectHistory() {
        // given
        Node beforeNode = rootWithEmbeddedChild(
                childWithMarker("initialized", "before"));
        // when
        Node afterNode = rootWithEmbedded(
                new Node().items(new Node().value("/child")),
                new Node().value(7));
        Throwable failure = FailureCapture.captureFailure(
                () -> ProtectedStateGuard.verifyUnchanged(
                        frozen(beforeNode),
                        frozen(beforeNode),
                        frozen(afterNode),
                        frozen(afterNode),
                        Collections.singleton("/child")));

        // then
        assertNull(failure);
    }

    @Test
    void shouldVerifyWholeEmbeddedChildReplacementCannotForgeDirectHistory() {
        // given
        Node beforeNode = rootWithEmbeddedChild(
                childWithMarker("initialized", "before"));
        Node afterNode = rootWithEmbeddedChild(
                childWithMarker("initialized", "after"));

        // when
        ProcessorFailureException failure =
                FailureCapture.captureFailure(
                () -> ProtectedStateGuard.verifyUnchanged(
                        frozen(beforeNode),
                        frozen(beforeNode),
                        frozen(afterNode),
                        frozen(afterNode),
                        Collections.singleton("/child")));

        // then
        assertNotNull(failure);
        assertEquals(
                ProcessorErrorCategory.ProtectedProcessorStateMutation,
                failure.errorCategory());
    }

    @Test
    void shouldVerifyDirectHistoryAtTransitivelyDeclaredScopeCannotChange() {
        // given
        Node beforeNode = rootWithEmbeddedChild(
                childDeclaringGrandchild(
                        childWithMarker("terminated", "before")));
        Node afterNode = rootWithEmbeddedChild(
                childDeclaringGrandchild(
                        childWithMarker("terminated", "after")));

        // when
        ProcessorFailureException failure =
                FailureCapture.captureFailure(
                () -> ProtectedStateGuard.verifyUnchanged(
                        frozen(beforeNode),
                        frozen(beforeNode),
                        frozen(afterNode),
                        frozen(afterNode)));

        // then
        assertNotNull(failure);
        assertEquals(
                ProcessorErrorCategory.ProtectedProcessorStateMutation,
                failure.errorCategory());
    }

    @Test
    void shouldVerifyEffectiveGeneralizationAtDeclaredScopeCannotChange() {
        // given
        Node canonical = rootWithEmbeddedChild(new Node());
        Node resolvedBefore = rootWithEmbeddedChild(
                childWithGeneralization("reject"));
        Node resolvedAfter = rootWithEmbeddedChild(
                childWithGeneralization("nearest-valid-ancestor"));

        // when
        ProcessorFailureException failure =
                FailureCapture.captureFailure(
                () -> ProtectedStateGuard.verifyUnchanged(
                        frozen(canonical),
                        frozen(resolvedBefore),
                        frozen(canonical),
                        frozen(resolvedAfter)));

        // then
        assertNotNull(failure);
        assertEquals(
                ProcessorErrorCategory.ProtectedProcessorStateMutation,
                failure.errorCategory());
    }

    @Test
    void shouldVerifyEffectiveProcessEmbeddedStateHasInlineReferenceBackedTypeParity() {
        // given
        Node beforeNode = rootWithEmbedded(
                new Node().items(new Node().value("/child")),
                new Node().value(7));
        Node afterNode = beforeNode.clone();
        // when
        afterNode.getContracts()
                .getProperties()
                .get("embedded")
                .blueId("11111111111111111111111111111111");
        Throwable failure = FailureCapture.captureFailure(
                () -> ProtectedStateGuard.verifyUnchanged(
                        frozen(beforeNode),
                        frozen(beforeNode),
                        frozen(beforeNode),
                        frozen(afterNode)));

        // then
        assertNull(failure);
    }

    @Test
    void shouldVerifyDirectMarkerInlineAndReferenceFormsUseExactIdentity() {
        // given
        Node marker = new Node().properties(
                "subject", new Node().value("E1"));
        String markerId = FrozenNode.fromNode(marker).blueId();
        Node beforeNode = new Node().contracts(
                new Node().properties("checkpoint", marker));
        // when
        Node afterNode = new Node().contracts(
                new Node().properties(
                        "checkpoint",
                        new Node().blueId(markerId)));
        Throwable failure = FailureCapture.captureFailure(
                () -> ProtectedStateGuard.verifyUnchanged(
                        FrozenNode.fromNode(beforeNode),
                        frozen(beforeNode),
                        FrozenNode.fromNode(afterNode),
                        frozen(beforeNode)));

        // then
        assertNull(failure);
    }

    private static Node rootWithEmbedded(Node paths, Node policy) {
        Node embedded = new Node()
                .type(new Node().blueId(
                        "11111111111111111111111111111111"))
                .properties(
                        "paths", paths,
                        "policy", policy);
        return new Node().contracts(
                new Node().properties("embedded", embedded));
    }

    private static Node rootWithEmbeddedChild(Node child) {
        return rootWithEmbedded(
                new Node().items(new Node().value("/child")),
                new Node().value(7))
                .properties("child", child);
    }

    private static Node childDeclaringGrandchild(Node grandchild) {
        return rootWithEmbedded(
                new Node().items(new Node().value("/grandchild")),
                new Node().value(7))
                .properties("grandchild", grandchild);
    }

    private static Node childWithMarker(String key, String value) {
        return new Node().contracts(
                new Node().properties(
                        key,
                        new Node().properties(
                                "value",
                                new Node().value(value))));
    }

    private static Node childWithGeneralization(String defaultMode) {
        return new Node().contracts(
                new Node().properties(
                        "generalization",
                        new Node()
                                .type(new Node().blueId(
                                        "22222222222222222222222222222222"))
                                .properties(
                                        "defaultMode",
                                        new Node().value(defaultMode))));
    }

    private static FrozenNode frozen(Node node) {
        return FrozenNode.fromResolvedNode(node);
    }
}
