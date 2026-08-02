package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.model.ProcessEmbedded;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

final class ScopeMutationServicesTest {

    @Test
    void shouldRejectPatchThatEntersEmbeddedScope() {
        // given
        ContractBundle bundle = embeddedChildBundle();
        PatchInput patch = PatchInput.mutable(JsonPatch.add(
                "/parent/child/value",
                new Node().value("forbidden")));

        // when
        Throwable captured = FailureCapture.captureFailure(
                () -> PatchBoundaryValidator.validate(
                        "/parent", bundle, patch));

        // then
        ProcessorEngine.BoundaryViolationException failure =
                assertInstanceOf(
                        ProcessorEngine.BoundaryViolationException.class,
                        captured);
        assertEquals(
                "Boundary violation: patch /parent/child/value "
                        + "enters embedded scope /parent/child",
                failure.getMessage());
    }

    @Test
    void shouldRejectPatchThatReplacesAncestorOfEmbeddedScope() {
        // given
        ContractBundle bundle = embeddedBundle("/parent/child");
        PatchInput patch = PatchInput.mutable(JsonPatch.replace(
                "/parent",
                new Node().properties(
                        "replacement", new Node().value(true))));

        // when
        Throwable captured = FailureCapture.captureFailure(
                () -> PatchBoundaryValidator.validate(
                        "/", bundle, patch));

        // then
        ProcessorEngine.BoundaryViolationException failure =
                assertInstanceOf(
                        ProcessorEngine.BoundaryViolationException.class,
                        captured);
        assertEquals(
                "Boundary violation: patch /parent is a strict ancestor "
                        + "of embedded scope /parent/child",
                failure.getMessage());
    }

    @Test
    void shouldAllowPatchThatReplacesWholeEmbeddedOccurrence() {
        // given
        ContractBundle bundle = embeddedChildBundle();
        PatchInput patch = PatchInput.mutable(JsonPatch.replace(
                "/parent/child",
                new Node().properties(
                        "replacement", new Node().value(true))));

        // when
        Throwable failure = FailureCapture.captureFailure(
                () -> PatchBoundaryValidator.validate(
                        "/parent", bundle, patch));

        // then
        assertNull(failure);
    }

    @Test
    void shouldRejectDirectReservedContractMutation() {
        // given
        ProcessorInvocationState execution = execution(new Node());
        DirectProtectedStateMutationGuard guard =
                new DirectProtectedStateMutationGuard(execution.runtime());
        PatchInput patch = PatchInput.mutable(JsonPatch.add(
                "/contracts/checkpoint",
                new Node().properties(
                        "subject", new Node().value("forged"))));

        // when
        Throwable captured = FailureCapture.captureFailure(
                () -> guard.validate("/", patch, false));

        // then
        ProcessorFailureException failure = assertInstanceOf(
                ProcessorFailureException.class,
                captured);
        assertEquals(
                ProcessorErrorCategory.ProtectedProcessorStateMutation,
                failure.errorCategory());
        assertEquals(
                "Reserved key 'checkpoint' is write-protected at "
                        + "/contracts/checkpoint",
                failure.getMessage());
    }

    @Test
    void shouldAllowApplicationToChangeEmbeddedPathList() {
        // given
        ProcessorInvocationState execution = execution(new Node());
        DirectProtectedStateMutationGuard guard =
                new DirectProtectedStateMutationGuard(execution.runtime());
        PatchInput patch = PatchInput.mutable(JsonPatch.add(
                "/contracts/embedded/paths/-",
                new Node().value("/child")));

        // when
        Throwable failure = FailureCapture.captureFailure(
                () -> guard.validate("/", patch, false));

        // then
        assertNull(failure);
    }

    @Test
    void shouldAllowApplicationToChangeEmbeddedCollectionPathList() {
        // given
        ProcessorInvocationState execution = execution(new Node());
        DirectProtectedStateMutationGuard guard =
                new DirectProtectedStateMutationGuard(execution.runtime());
        PatchInput patch = PatchInput.mutable(JsonPatch.add(
                "/contracts/embedded/collectionPaths/-",
                new Node().value("/children")));

        // when
        Throwable failure = FailureCapture.captureFailure(
                () -> guard.validate("/", patch, false));

        // then
        assertNull(failure);
    }

    @Test
    void shouldRejectInlineTypeThatContributesProtectedState() {
        // given
        ProcessorInvocationState execution = execution(new Node());
        DirectProtectedStateMutationGuard guard =
                new DirectProtectedStateMutationGuard(execution.runtime());
        Node applicationType = new Node().contracts(
                new Node().properties(
                        "initialized",
                        new Node().properties(
                                "documentId",
                                new Node().value("forged"))));
        PatchInput patch = PatchInput.mutable(JsonPatch.add(
                "/type", applicationType));

        // when
        Throwable captured = FailureCapture.captureFailure(
                () -> guard.validate("/", patch, false));

        // then
        ProcessorFailureException failure = assertInstanceOf(
                ProcessorFailureException.class,
                captured);
        assertEquals(
                ProcessorErrorCategory.ProtectedProcessorStateMutation,
                failure.errorCategory());
        assertEquals(
                "Application type patch contributes protected processor "
                        + "state at /type/contracts/initialized",
                failure.getMessage());
    }

    private static ContractBundle embeddedChildBundle() {
        return embeddedBundle("/child");
    }

    private static ContractBundle embeddedBundle(String path) {
        return ContractBundle.builder()
                .setEmbedded(new ProcessEmbedded().addPath(path))
                .build();
    }

    private static ProcessorInvocationState execution(Node document) {
        return new ProcessorInvocationState(
                new DocumentProcessor(), document);
    }
}
