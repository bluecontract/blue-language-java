package blue.language.processor;

import blue.language.merge.ResolvedSnapshot;
import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.model.Node;
import blue.language.model.Nodes;
import blue.language.model.wire.BlueLanguageConstants;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ProcessingSnapshotBootstrapTest {

    private static final String EXECUTABLE_BODY_PATH =
            "/lessons/lesson-a/contracts/handler/event";

    @Test
    void shouldValidateUncheckedCanonicalIdentityBeforeExecution() {
        // given
        Node source = new Node().properties("value", new Node().value(7));
        FrozenNode unchecked = FrozenNode.fromUncheckedCanonicalNode(source);
        ResolvedSnapshot input = new ResolvedSnapshot(unchecked,
                FrozenNode.fromResolvedNode(source), unchecked.blueId());
        RecordingProcessingObserver observer = new RecordingProcessingObserver();

        // when
        ResolvedSnapshot prepared = ProcessingSnapshotBootstrap.prepare(
                input, Collections.emptyMap(), observer);

        // then
        assertTrue(prepared.frozenCanonicalRoot().isStrictBlueIdValidation());
        assertTrue(prepared.frozenSourceRoot().isStrictBlueIdValidation());
        assertFalse(input.frozenCanonicalRoot().isStrictBlueIdValidation());
        assertEquals(input.blueId(), prepared.blueId());
        assertEquals(1L, observer.snapshot().counter("processorInputUncheckedCanonical"));
        assertEquals(0L, observer.snapshot().counter("processorInputStrictCanonical"));
    }

    @Test
    void shouldRejectMalformedUncheckedReferenceBeforeExecution() {
        // given
        Node source = new Node().properties("child", new Node().blueId("not-a-blue-id"));
        FrozenNode unchecked = FrozenNode.fromUncheckedCanonicalNode(source);
        ResolvedSnapshot input = new ResolvedSnapshot(unchecked,
                FrozenNode.fromResolvedNode(source), unchecked.blueId());

        // when
        IllegalArgumentException failure = FailureCapture.captureFailure(
                () -> ProcessingSnapshotBootstrap.prepare(
                        input, Collections.emptyMap(), NoOpProcessingObserver.INSTANCE));

        // then
        assertNotNull(failure);
        assertFalse(input.frozenCanonicalRoot().isStrictBlueIdValidation());
        assertEquals("not-a-blue-id", input.canonicalAt("/child").getReferenceBlueId());
    }

    @Test
    void shouldPreserveColdExecutableBodyInCollectionGeneratedScope() {
        // given
        Node body = new Node().properties(
                "kind", new Node().value("selected-event"));
        String bodyBlueId = FrozenNode.fromNode(body).blueId();
        Node canonical = rootWithCollectionMemberBody(
                new Node().blueId(bodyBlueId));
        Node resolved = rootWithCollectionMemberBody(body);
        ResolvedSnapshot snapshot = new ResolvedSnapshot(
                FrozenNode.fromNode(canonical),
                FrozenNode.fromResolvedNode(resolved));
        Map<String, List<String>> executableFields =
                Collections.singletonMap(
                        RuntimeBlueIds.SCRIPTED_HANDLER,
                        Collections.singletonList("event"));

        // when
        ResolvedSnapshot prepared = ProcessingSnapshotBootstrap.prepare(
                snapshot,
                executableFields,
                NoOpProcessingObserver.INSTANCE);

        // then
        assertFalse(snapshot.resolvedAt(EXECUTABLE_BODY_PATH)
                .isReferenceOnly());
        assertTrue(prepared.resolvedAt(EXECUTABLE_BODY_PATH)
                .isReferenceOnly());
        assertEquals(
                bodyBlueId,
                prepared.resolvedAt(EXECUTABLE_BODY_PATH)
                        .getReferenceBlueId());
        assertFalse(prepared.isResolutionComplete());
    }

    @Test
    void shouldAcceptExactPathsWithInheritedCollectionPathsDefinition() {
        // given
        Node embedded = processEmbedded(
                declarations("/payment"),
                declarationDefinition());
        FrozenNode effectiveScope = effectiveScope(
                embedded,
                Collections.singletonMap(
                        "payment", Nodes.emptyObject()));

        // when
        EmbeddedScopePlan plan = ProcessingSnapshotBootstrap
                .embeddedScopePlan(
                        effectiveScope,
                        "/",
                        null,
                        CanonicalTypeIdentityLookup.incomplete());

        // then
        assertEquals(
                Collections.singletonList("/payment"),
                plan.explicitDeclarationPaths());
        assertEquals(
                Collections.emptyList(),
                plan.collectionDeclarationPaths());
        assertEquals(
                Collections.singletonList("/payment"),
                plan.concreteChildPaths());
    }

    @Test
    void shouldAcceptCollectionPathsWithInheritedPathsDefinition() {
        // given
        Node embedded = processEmbedded(
                declarationDefinition(),
                declarations("/lessons"));
        Node lessons = new Node().properties(
                "lesson-a", Nodes.emptyObject());
        FrozenNode effectiveScope = effectiveScope(
                embedded,
                Collections.singletonMap("lessons", lessons));

        // when
        EmbeddedScopePlan plan = ProcessingSnapshotBootstrap
                .embeddedScopePlan(
                        effectiveScope,
                        "/",
                        null,
                        CanonicalTypeIdentityLookup.incomplete());

        // then
        assertEquals(
                Collections.emptyList(),
                plan.explicitDeclarationPaths());
        assertEquals(
                Collections.singletonList("/lessons"),
                plan.collectionDeclarationPaths());
        assertEquals(
                Collections.singletonList("/lessons/lesson-a"),
                plan.concreteChildPaths());
    }

    @Test
    void shouldRejectWhenBothDeclarationFieldsAreInheritedDefinitions() {
        // given
        Node embedded = processEmbedded(
                declarationDefinition(),
                declarationDefinition());
        FrozenNode effectiveScope = effectiveScope(
                embedded,
                Collections.emptyMap());

        // when
        SubscriptionSurfaceInvalidException failure =
                FailureCapture.captureFailure(
                () -> ProcessingSnapshotBootstrap.embeddedScopePlan(
                        effectiveScope,
                        "/",
                        null,
                        CanonicalTypeIdentityLookup.incomplete()));

        // then
        assertNotNull(failure);
        assertEquals(
                ProcessorErrorCategory.SubscriptionSurfaceInvalid,
                failure.diagnostic().category());
    }

    @Test
    void shouldRejectScalarPathsWithRuntimePointerDiagnostic() {
        // given
        Node embedded = processEmbedded(
                new Node().value("/payment"),
                declarationDefinition());
        FrozenNode effectiveScope = effectiveScope(
                embedded,
                Collections.emptyMap());

        // when
        SubscriptionSurfaceInvalidException failure =
                FailureCapture.captureFailure(
                () -> ProcessingSnapshotBootstrap.embeddedScopePlan(
                        effectiveScope,
                        "/",
                        null,
                        CanonicalTypeIdentityLookup.incomplete()));

        // then
        assertNotNull(failure);
        assertEquals(
                ProcessorErrorCategory.InvalidRuntimePointer,
                failure.diagnostic().category());
    }

    @Test
    void shouldRejectObjectCollectionPathsWithCollectionPathDiagnostic() {
        // given
        Node embedded = processEmbedded(
                declarationDefinition(),
                new Node().properties(
                        "unexpected", Nodes.emptyObject()));
        FrozenNode effectiveScope = effectiveScope(
                embedded,
                Collections.emptyMap());

        // when
        SubscriptionSurfaceInvalidException failure =
                FailureCapture.captureFailure(
                () -> ProcessingSnapshotBootstrap.embeddedScopePlan(
                        effectiveScope,
                        "/",
                        null,
                        CanonicalTypeIdentityLookup.incomplete()));

        // then
        assertNotNull(failure);
        assertEquals(
                ProcessorErrorCategory.InvalidEmbeddedCollectionPath,
                failure.diagnostic().category());
    }

    @Test
    void shouldRejectReferencePathsWithRuntimePointerDiagnostic() {
        // given
        Node embedded = processEmbedded(
                reference(BlueLanguageConstants.LIST_TYPE_BLUE_ID),
                declarationDefinition());
        FrozenNode effectiveScope = effectiveScope(
                embedded,
                Collections.emptyMap());

        // when
        SubscriptionSurfaceInvalidException failure =
                FailureCapture.captureFailure(
                () -> ProcessingSnapshotBootstrap.embeddedScopePlan(
                        effectiveScope,
                        "/",
                        null,
                        CanonicalTypeIdentityLookup.incomplete()));

        // then
        assertNotNull(failure);
        assertEquals(
                ProcessorErrorCategory.InvalidRuntimePointer,
                failure.diagnostic().category());
    }

    @Test
    void shouldRejectReferenceCollectionPathsWithCollectionPathDiagnostic() {
        // given
        Node embedded = processEmbedded(
                declarationDefinition(),
                reference(BlueLanguageConstants.LIST_TYPE_BLUE_ID));
        FrozenNode effectiveScope = effectiveScope(
                embedded,
                Collections.emptyMap());

        // when
        SubscriptionSurfaceInvalidException failure =
                FailureCapture.captureFailure(
                () -> ProcessingSnapshotBootstrap.embeddedScopePlan(
                        effectiveScope,
                        "/",
                        null,
                        CanonicalTypeIdentityLookup.incomplete()));

        // then
        assertNotNull(failure);
        assertEquals(
                ProcessorErrorCategory.InvalidEmbeddedCollectionPath,
                failure.diagnostic().category());
    }

    private static Node rootWithCollectionMemberBody(Node body) {
        Node embedded = new Node()
                .type(reference(RuntimeBlueIds.PROCESS_EMBEDDED))
                .properties(
                        "collectionPaths",
                        new Node().items(
                                new Node().value("/lessons")));
        Node handler = new Node()
                .type(reference(RuntimeBlueIds.SCRIPTED_HANDLER))
                .properties("event", body);
        Node lesson = new Node().contracts(
                new Node().properties("handler", handler));
        return new Node()
                .contracts(new Node().properties(
                        ProcessorContractConstants.KEY_EMBEDDED,
                        embedded))
                .properties(
                        "lessons",
                        new Node().properties(
                                "lesson-a", lesson));
    }

    private static Node processEmbedded(
            Node paths,
            Node collectionPaths) {
        Map<String, Node> declarations = new LinkedHashMap<>();
        declarations.put("paths", paths);
        declarations.put("collectionPaths", collectionPaths);
        return new Node()
                .type(reference(RuntimeBlueIds.PROCESS_EMBEDDED))
                .properties(declarations);
    }

    private static Node declarations(String path) {
        return new Node().items(new Node().value(path));
    }

    private static Node declarationDefinition() {
        return new Node()
                .type(reference(BlueLanguageConstants.LIST_TYPE_BLUE_ID))
                .itemType(reference(BlueLanguageConstants.TEXT_TYPE_BLUE_ID))
                .description("Optional Process Embedded declaration");
    }

    private static FrozenNode effectiveScope(
            Node embedded,
            Map<String, Node> properties) {
        return FrozenNode.fromResolvedNode(new Node()
                .contracts(new Node().properties(
                        ProcessorContractConstants.KEY_EMBEDDED,
                        embedded))
                .properties(properties));
    }

    private static Node reference(String blueId) {
        return new Node().blueId(blueId);
    }
}
