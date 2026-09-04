package blue.language.processor;

import blue.language.model.Node;
import blue.language.model.Nodes;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.model.ProcessEmbedded;
import blue.language.processor.registry.RuntimeBlueIds;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        ProcessorInvocationState execution = execution(Nodes.emptyObject());
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
        ProcessorInvocationState execution = execution(Nodes.emptyObject());
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
        ProcessorInvocationState execution = execution(Nodes.emptyObject());
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
    void shouldPreflightProcessEmbeddedRoleSeparatelyFromOwnership() {
        // given
        DocumentProcessor processor = new DocumentProcessor();
        DirectContractMutationPreflight preflight =
                new DirectContractMutationPreflight(
                        processor.contractLoader());
        PatchInput valid = PatchInput.mutable(JsonPatch.add(
                "/contracts/embedded",
                processEmbedded("/child")));
        PatchInput invalid = PatchInput.mutable(JsonPatch.add(
                "/contracts/embedded",
                new Node().type(new Node().blueId(
                        RuntimeBlueIds.LIFECYCLE_EVENT_CHANNEL))));

        // when
        Throwable validFailure = FailureCapture.captureFailure(
                () -> preflight.validate("/", valid));
        Throwable invalidFailure = FailureCapture.captureFailure(
                () -> preflight.validate("/", invalid));

        // then
        assertNull(validFailure);
        MustUnderstandFailureException failure = assertInstanceOf(
                MustUnderstandFailureException.class,
                invalidFailure);
        assertEquals(
                ProcessorErrorCategory.InvalidContractKey,
                failure.errorCategory());
    }

    @Test
    void shouldRejectDirectGeneralizationPolicyMutation() {
        // given
        DirectProtectedStateMutationGuard guard =
                new DirectProtectedStateMutationGuard(
                        execution(Nodes.emptyObject()).runtime());
        PatchInput patch = PatchInput.mutable(JsonPatch.add(
                "/contracts/generalization",
                protectedValue("forged")));

        // when
        Throwable captured = FailureCapture.captureFailure(
                () -> guard.validate("/", patch, false));

        // then
        assertProtectedFailure(captured, "generalization direct write");
    }

    @Test
    void shouldAllowMutableAndFrozenWholeContractsReplacementWhenProtectedStateIsExact() {
        // given
        Node protectedContracts = allProtectedContracts("same");
        Node document = new Node().contracts(protectedContracts.clone());
        Node replacement = allProtectedContracts("same").properties(
                "workflow", new Node().properties(
                        "revision", new Node().value(2)),
                "embedded", processEmbedded("/child"));
        DirectProtectedStateMutationGuard mutableGuard =
                new DirectProtectedStateMutationGuard(
                        execution(document.clone()).runtime());
        DirectProtectedStateMutationGuard frozenGuard =
                new DirectProtectedStateMutationGuard(
                        execution(document.clone()).runtime());
        PatchInput mutable = PatchInput.mutable(JsonPatch.replace(
                "/contracts", replacement.clone()));
        PatchInput frozen = PatchInput.frozen(FrozenJsonPatch.from(
                JsonPatch.replace("/contracts", replacement.clone())));

        // when
        Throwable mutableFailure = FailureCapture.captureFailure(
                () -> mutableGuard.validate("/", mutable, false));
        Throwable frozenFailure = FailureCapture.captureFailure(
                () -> frozenGuard.validate("/", frozen, false));

        // then
        assertNull(mutableFailure);
        assertNull(frozenFailure);
    }

    @Test
    void shouldRejectWholeContractsReplacementThatChangesAnyProtectedKey() {
        // given
        List<String> protectedKeys = Arrays.asList(
                "initialized",
                "terminated",
                "checkpoint",
                "generalization");
        Map<String, Throwable> failures = new LinkedHashMap<>();

        // when
        for (String key : protectedKeys) {
            Node document = new Node().contracts(new Node().properties(
                    key, protectedValue("before"),
                    "workflow", Nodes.emptyObject()));
            DirectProtectedStateMutationGuard mutableGuard =
                    new DirectProtectedStateMutationGuard(
                            execution(document.clone()).runtime());
            DirectProtectedStateMutationGuard frozenGuard =
                    new DirectProtectedStateMutationGuard(
                            execution(document.clone()).runtime());
            Node replacement = new Node().properties(
                    key, protectedValue("after"),
                    "workflow", Nodes.emptyObject());

            Throwable mutableFailure = FailureCapture.captureFailure(
                    () -> mutableGuard.validate(
                            "/",
                            PatchInput.mutable(JsonPatch.replace(
                                    "/contracts", replacement.clone())),
                            false));
            Throwable frozenFailure = FailureCapture.captureFailure(
                    () -> frozenGuard.validate(
                            "/",
                            PatchInput.frozen(FrozenJsonPatch.from(
                                    JsonPatch.replace(
                                            "/contracts",
                                            replacement.clone()))),
                            false));

            failures.put(key + " mutable replacement", mutableFailure);
            failures.put(key + " frozen replacement", frozenFailure);
        }

        // then
        for (Map.Entry<String, Throwable> failure : failures.entrySet()) {
            assertProtectedFailure(failure.getValue(), failure.getKey());
        }
    }

    @Test
    void shouldRejectWholeContractsReplacementThatDropsOrIntroducesAnyProtectedKey() {
        // given
        List<String> protectedKeys = Arrays.asList(
                "initialized",
                "terminated",
                "checkpoint",
                "generalization");
        Map<String, Throwable> failures = new LinkedHashMap<>();

        // when
        for (String key : protectedKeys) {
            DirectProtectedStateMutationGuard populated =
                    new DirectProtectedStateMutationGuard(
                            execution(new Node().contracts(
                                    new Node().properties(
                                            key,
                                            protectedValue("before"))))
                                    .runtime());
            DirectProtectedStateMutationGuard empty =
                    new DirectProtectedStateMutationGuard(
                            execution(Nodes.emptyObject()).runtime());

            Throwable dropFailure = FailureCapture.captureFailure(
                    () -> populated.validate(
                            "/",
                            PatchInput.mutable(JsonPatch.replace(
                                    "/contracts",
                                    new Node().properties(
                                            "workflow", Nodes.emptyObject()))),
                            false));
            Throwable introductionFailure = FailureCapture.captureFailure(
                    () -> empty.validate(
                            "/",
                            PatchInput.mutable(JsonPatch.add(
                                    "/contracts",
                                    new Node().properties(
                                            key,
                                            protectedValue("forged")))),
                            false));

            failures.put(key + " drop", dropFailure);
            failures.put(key + " introduction", introductionFailure);
        }

        // then
        for (Map.Entry<String, Throwable> failure : failures.entrySet()) {
            assertProtectedFailure(failure.getValue(), failure.getKey());
        }
    }

    @Test
    void shouldRejectInlineTypeThatContributesProtectedState() {
        // given
        ProcessorInvocationState execution = execution(Nodes.emptyObject());
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

    @Test
    void shouldAllowInlineTypeThatContributesProcessEmbedded() {
        // given
        DirectProtectedStateMutationGuard guard =
                new DirectProtectedStateMutationGuard(
                        execution(Nodes.emptyObject()).runtime());
        Node applicationType = new Node().contracts(
                new Node().properties(
                        "embedded", processEmbedded("/child")));
        PatchInput patch = PatchInput.mutable(JsonPatch.add(
                "/type", applicationType));

        // when
        Throwable failure = FailureCapture.captureFailure(
                () -> guard.validate("/", patch, false));

        // then
        assertNull(failure);
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

    private static Node processEmbedded(String path) {
        return new Node()
                .type(new Node().blueId(
                        RuntimeBlueIds.PROCESS_EMBEDDED))
                .properties(
                        "paths",
                        new Node().items(new Node().value(path)));
    }

    private static Node protectedValue(String value) {
        return new Node().properties(
                "identity", new Node().value(value));
    }

    private static Node allProtectedContracts(String value) {
        return new Node().properties(
                "initialized", protectedValue(value),
                "terminated", protectedValue(value),
                "checkpoint", protectedValue(value),
                "generalization", protectedValue(value));
    }

    private static void assertProtectedFailure(
            Throwable captured,
            String description) {
        ProcessorFailureException failure = assertInstanceOf(
                ProcessorFailureException.class,
                captured,
                description);
        assertEquals(
                ProcessorErrorCategory.ProtectedProcessorStateMutation,
                failure.errorCategory(),
                description);
    }
}
