package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.registry.RuntimeBlueIds;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNull;

/** Explicit ownership matrix for mutable application-contract roles. */
final class ApplicationContractOwnershipTest {

    @Test
    void shouldAllowApplicationToAddReplaceAndRemoveApplicationContracts() {
        // given
        List<ContractCase> cases = Arrays.asList(
                application("workflow"),
                application("handler"),
                application("operation"),
                application("externalChannel"),
                application("internalChannel"),
                application("lifecycle"),
                application("actorPolicy"),
                new ContractCase(
                        "embedded",
                        processEmbedded("/peer"),
                        processEmbedded("/next")));
        List<OwnershipResult> results = new ArrayList<>();

        // when
        for (ContractCase contract : cases) {
            DirectProtectedStateMutationGuard empty = guard(new Node());
            DirectProtectedStateMutationGuard populated = guard(
                    new Node().contracts(new Node().properties(
                            contract.key, contract.before)));

            results.add(new OwnershipResult(
                    contract.key + " addition is application-owned",
                    FailureCapture.captureFailure(() -> empty.validate(
                            "/",
                            PatchInput.mutable(JsonPatch.add(
                                    "/contracts/" + contract.key,
                                    contract.before.clone())),
                            false))));
            results.add(new OwnershipResult(
                    contract.key + " replacement is application-owned",
                    FailureCapture.captureFailure(
                            () -> populated.validate(
                                    "/",
                                    PatchInput.mutable(JsonPatch.replace(
                                            "/contracts/" + contract.key,
                                            contract.after.clone())),
                                    false))));
            results.add(new OwnershipResult(
                    contract.key + " removal is application-owned",
                    FailureCapture.captureFailure(
                            () -> populated.validate(
                                    "/",
                                    PatchInput.mutable(JsonPatch.remove(
                                            "/contracts/" + contract.key)),
                                    false))));
        }

        // then
        for (OwnershipResult result : results) {
            assertNull(result.failure, result.description);
        }
    }

    private static DirectProtectedStateMutationGuard guard(Node document) {
        ProcessorInvocationState execution = new ProcessorInvocationState(
                new DocumentProcessor(), document);
        return new DirectProtectedStateMutationGuard(execution.runtime());
    }

    private static ContractCase application(String key) {
        return new ContractCase(
                key,
                new Node().properties("revision", new Node().value(1)),
                new Node().properties("revision", new Node().value(2)));
    }

    private static Node processEmbedded(String path) {
        return new Node()
                .type(new Node().blueId(RuntimeBlueIds.PROCESS_EMBEDDED))
                .properties(
                        "paths",
                        new Node().items(new Node().value(path)));
    }

    private static final class ContractCase {
        private final String key;
        private final Node before;
        private final Node after;

        private ContractCase(String key, Node before, Node after) {
            this.key = key;
            this.before = before;
            this.after = after;
        }
    }

    private static final class OwnershipResult {
        private final String description;
        private final Throwable failure;

        private OwnershipResult(String description, Throwable failure) {
            this.description = description;
            this.failure = failure;
        }
    }
}
