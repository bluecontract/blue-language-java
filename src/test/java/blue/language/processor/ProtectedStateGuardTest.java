package blue.language.processor;

import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class ProtectedStateGuardTest {

    @Test
    void ordinaryApplicationStateMayChange() {
        FrozenNode before = frozen(
                new Node().properties("value", new Node().value(0)));
        FrozenNode after = frozen(
                new Node().properties("value", new Node().value(1)));

        assertDoesNotThrow(() -> ProtectedStateGuard.verifyUnchanged(
                before, before, after, after));
    }

    @Test
    void directHistoryStateCannotChange() {
        for (String key : new String[]{
                "initialized", "terminated", "checkpoint"
        }) {
            Node beforeNode = new Node().contracts(new Node());
            Node afterNode = new Node().contracts(
                    new Node().properties(
                            key,
                            new Node().properties(
                                    "identity",
                                    new Node().value(key))));

            ProcessorFailureException failure = assertThrows(
                    ProcessorFailureException.class,
                    () -> ProtectedStateGuard.verifyUnchanged(
                            frozen(beforeNode),
                            frozen(beforeNode),
                            frozen(afterNode),
                            frozen(afterNode)),
                    key);

            assertEquals(
                    ProcessorErrorCategory
                            .ProtectedProcessorStateMutation,
                    failure.errorCategory(),
                    key);
        }
    }

    @Test
    void directHistoryComparesCanonicalIdentityNotResolvedValue() {
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

        ProcessorFailureException failure = assertThrows(
                ProcessorFailureException.class,
                () -> ProtectedStateGuard.verifyUnchanged(
                        FrozenNode.fromNode(beforeNode),
                        sameResolved,
                        FrozenNode.fromNode(afterNode),
                        sameResolved));

        assertEquals(
                ProcessorErrorCategory.ProtectedProcessorStateMutation,
                failure.errorCategory());
    }

    @Test
    void resolvedOnlyHistoryStateIsNotProtectedBecauseMarkersAreDirect() {
        FrozenNode canonical = frozen(
                new Node().type(new Node().blueId(
                        "11111111111111111111111111111111")));
        FrozenNode resolvedBefore = frozen(new Node());
        FrozenNode resolvedAfter = frozen(
                new Node().contracts(
                        new Node().properties(
                                "terminated",
                                new Node().properties(
                                "reason",
                                new Node().value("done")))));

        assertDoesNotThrow(() -> ProtectedStateGuard.verifyUnchanged(
                canonical,
                resolvedBefore,
                canonical,
                resolvedAfter));
    }

    @Test
    void exactProcessEmbeddedPathsExceptionPreservesOtherFields() {
        FrozenNode before = frozen(rootWithEmbedded(
                new Node().items(new Node().value("/one")),
                new Node().value(7)));
        FrozenNode after = frozen(rootWithEmbedded(
                new Node().items(new Node().value("/two")),
                new Node().value(7)));

        assertDoesNotThrow(() -> ProtectedStateGuard.verifyUnchanged(
                before, before, after, after));
    }

    @Test
    void processEmbeddedNonPathFieldCannotChange() {
        FrozenNode before = frozen(rootWithEmbedded(
                new Node().items(new Node().value("/one")),
                new Node().value(7)));
        FrozenNode after = frozen(rootWithEmbedded(
                new Node().items(new Node().value("/two")),
                new Node().value(8)));

        ProcessorFailureException failure = assertThrows(
                ProcessorFailureException.class,
                () -> ProtectedStateGuard.verifyUnchanged(
                        before, before, after, after));

        assertEquals(
                ProcessorErrorCategory.ProtectedProcessorStateMutation,
                failure.errorCategory());
    }

    @Test
    void processEmbeddedEffectiveTypeCannotChange() {
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

        ProcessorFailureException failure = assertThrows(
                ProcessorFailureException.class,
                () -> ProtectedStateGuard.verifyUnchanged(
                        frozen(beforeNode),
                        frozen(beforeNode),
                        frozen(afterNode),
                        frozen(afterNode)));

        assertEquals(
                ProcessorErrorCategory.ProtectedProcessorStateMutation,
                failure.errorCategory());
    }

    @Test
    void unrelatedNestedBusinessObjectContractsAreNotScopeState() {
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

        assertDoesNotThrow(() -> ProtectedStateGuard.verifyUnchanged(
                frozen(beforeNode),
                frozen(beforeNode),
                frozen(afterNode),
                frozen(afterNode)));
    }

    @Test
    void contractsInsideBusinessListItemsAreNotScopeState() {
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

        assertDoesNotThrow(() -> ProtectedStateGuard.verifyUnchanged(
                frozen(beforeNode),
                frozen(beforeNode),
                frozen(afterNode),
                frozen(afterNode)));
    }

    @Test
    void malformedEmbeddedListRouteDoesNotTurnListItemIntoScope() {
        Node beforeNode = rootWithEmbedded(
                new Node().items(new Node().value("/rows/0")),
                new Node().value(7))
                .properties(
                        "rows",
                        new Node().items(
                                childWithMarker(
                                        "checkpoint", "before")));
        Node afterNode = beforeNode.clone();
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

        assertDoesNotThrow(() -> ProtectedStateGuard.verifyUnchanged(
                frozen(beforeNode),
                frozen(beforeNode),
                frozen(afterNode),
                frozen(afterNode)));
    }

    @Test
    void directHistoryAtDeclaredEmbeddedScopeCannotChange() {
        Node beforeNode = rootWithEmbeddedChild(
                childWithMarker("checkpoint", "before"));
        Node afterNode = rootWithEmbeddedChild(
                childWithMarker("checkpoint", "after"));

        ProcessorFailureException failure = assertThrows(
                ProcessorFailureException.class,
                () -> ProtectedStateGuard.verifyUnchanged(
                        frozen(beforeNode),
                        frozen(beforeNode),
                        frozen(afterNode),
                        frozen(afterNode)));

        assertEquals(
                ProcessorErrorCategory.ProtectedProcessorStateMutation,
                failure.errorCategory());
    }

    @Test
    void wholeEmbeddedChildRemovalMayDropItsDirectHistory() {
        Node beforeNode = rootWithEmbeddedChild(
                childWithMarker("initialized", "before"));
        Node afterNode = rootWithEmbedded(
                new Node().items(new Node().value("/child")),
                new Node().value(7));

        assertDoesNotThrow(() -> ProtectedStateGuard.verifyUnchanged(
                frozen(beforeNode),
                frozen(beforeNode),
                frozen(afterNode),
                frozen(afterNode),
                Collections.singleton("/child")));
    }

    @Test
    void wholeEmbeddedChildReplacementCannotForgeDirectHistory() {
        Node beforeNode = rootWithEmbeddedChild(
                childWithMarker("initialized", "before"));
        Node afterNode = rootWithEmbeddedChild(
                childWithMarker("initialized", "after"));

        ProcessorFailureException failure = assertThrows(
                ProcessorFailureException.class,
                () -> ProtectedStateGuard.verifyUnchanged(
                        frozen(beforeNode),
                        frozen(beforeNode),
                        frozen(afterNode),
                        frozen(afterNode),
                        Collections.singleton("/child")));

        assertEquals(
                ProcessorErrorCategory.ProtectedProcessorStateMutation,
                failure.errorCategory());
    }

    @Test
    void directHistoryAtTransitivelyDeclaredScopeCannotChange() {
        Node beforeNode = rootWithEmbeddedChild(
                childDeclaringGrandchild(
                        childWithMarker("terminated", "before")));
        Node afterNode = rootWithEmbeddedChild(
                childDeclaringGrandchild(
                        childWithMarker("terminated", "after")));

        ProcessorFailureException failure = assertThrows(
                ProcessorFailureException.class,
                () -> ProtectedStateGuard.verifyUnchanged(
                        frozen(beforeNode),
                        frozen(beforeNode),
                        frozen(afterNode),
                        frozen(afterNode)));

        assertEquals(
                ProcessorErrorCategory.ProtectedProcessorStateMutation,
                failure.errorCategory());
    }

    @Test
    void effectiveGeneralizationAtDeclaredScopeCannotChange() {
        Node canonical = rootWithEmbeddedChild(new Node());
        Node resolvedBefore = rootWithEmbeddedChild(
                childWithGeneralization("reject"));
        Node resolvedAfter = rootWithEmbeddedChild(
                childWithGeneralization("nearest-valid-ancestor"));

        ProcessorFailureException failure = assertThrows(
                ProcessorFailureException.class,
                () -> ProtectedStateGuard.verifyUnchanged(
                        frozen(canonical),
                        frozen(resolvedBefore),
                        frozen(canonical),
                        frozen(resolvedAfter)));

        assertEquals(
                ProcessorErrorCategory.ProtectedProcessorStateMutation,
                failure.errorCategory());
    }

    @Test
    void effectiveProcessEmbeddedStateHasInlineReferenceBackedTypeParity() {
        Node beforeNode = rootWithEmbedded(
                new Node().items(new Node().value("/child")),
                new Node().value(7));
        Node afterNode = beforeNode.clone();
        afterNode.getContracts()
                .getProperties()
                .get("embedded")
                .blueId("11111111111111111111111111111111");

        assertDoesNotThrow(() -> ProtectedStateGuard.verifyUnchanged(
                frozen(beforeNode),
                frozen(beforeNode),
                frozen(beforeNode),
                frozen(afterNode)));
    }

    @Test
    void directMarkerInlineAndReferenceFormsUseExactIdentity() {
        Node marker = new Node().properties(
                "subject", new Node().value("E1"));
        String markerId = FrozenNode.fromNode(marker).blueId();
        Node beforeNode = new Node().contracts(
                new Node().properties("checkpoint", marker));
        Node afterNode = new Node().contracts(
                new Node().properties(
                        "checkpoint",
                        new Node().blueId(markerId)));

        assertDoesNotThrow(() -> ProtectedStateGuard.verifyUnchanged(
                FrozenNode.fromNode(beforeNode),
                frozen(beforeNode),
                FrozenNode.fromNode(afterNode),
                frozen(beforeNode)));
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
