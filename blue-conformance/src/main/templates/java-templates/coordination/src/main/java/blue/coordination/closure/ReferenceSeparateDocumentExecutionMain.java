package blue.coordination.closure;

import blue.contracts.closure.DocumentId;
import blue.contracts.closure.DocumentStepEvidence;
import blue.contracts.closure.DocumentStepInput;
import blue.contracts.closure.DocumentStepProcessor;
import blue.contracts.closure.ExecutionPolicy;
import blue.contracts.closure.GasCharge;
import blue.contracts.closure.LocalDocumentStepResult;
import blue.contracts.closure.ResultingDocument;
import blue.contracts.closure.SharedGasMeter;
import blue.contracts.closure.TentativeResolutionContext;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Dependency-free shape smoke for the MyOS separate-document decomposition. */
public final class ReferenceSeparateDocumentExecutionMain {
    private ReferenceSeparateDocumentExecutionMain() { }

    public static void main(String[] args) {
        final DocumentId a = new DocumentId("a");
        final DocumentId b = new DocumentId("b");
        ExecutionPolicy policy = new ExecutionPolicy(
                "sha256:policy",
                100L,
                new HashMap<DocumentId, Long>(),
                "reference");
        SharedGasMeter meter = new SharedGasMeter(
                policy,
                (namespace, counter, quantity, weight, subtotal,
                        applicableCap, remainingBeforeCharge, owner) ->
                        "sha256:rejected");
        DocumentStepProcessor processor = new DocumentStepProcessor() {
            @Override
            public LocalDocumentStepResult process(
                    DocumentStepInput input,
                    SharedGasMeter sharedMeter) {
                long before = sharedMeter.used();
                sharedMeter.charge(
                        new GasCharge.Context(
                                input.targetDocumentId(),
                                "/",
                                Long.valueOf(0L),
                                Long.valueOf(1L),
                                "reference",
                                null,
                                input.workOccurrenceIdentity(),
                                "one isolated document step"),
                        GasCharge.Namespace.PROCESSOR,
                        "handlerCall",
                        1L,
                        10L,
                        new SharedGasMeter.WorkOwner(
                                input.workOccurrenceIdentity()));
                return new LocalDocumentStepResult(
                        input.targetDocumentId(),
                        input.workOccurrenceIdentity(),
                        input.exactDocumentBlueId(),
                        "local-body-" + input.stepOrdinal(),
                        before,
                        sharedMeter.used(),
                        true);
            }
        };
        List<DocumentStepInput> inputs = Arrays.asList(
                step(0, a, "A0", "B0", "work-0"),
                step(1, a, "A1", "B0", "work-1"),
                step(2, b, "A2", "B0", "work-2"),
                step(3, a, "A2", "B1", "work-3"));
        MyOsDocumentStepCoordinator.Result result =
                new MyOsDocumentStepCoordinator(processor).execute(
                        "sha256:closure",
                        "sha256:invocation",
                        inputs,
                        meter);
        require(result.localSteps().size() == 4, "local step count");
        require(meter.used() == 40L, "shared gas");
        for (int i = 0; i < result.evidence().size(); i++) {
            DocumentStepEvidence evidence = result.evidence().get(i);
            require(evidence.stepOrdinal() == i, "step ordinal");
            require(evidence.targetDocumentId().equals(
                    evidence.executionRootDocumentId()), "execution Root");
            require("/".equals(evidence.scopePath()), "scope");
            require(evidence.ambientContainingDocumentIds().isEmpty(),
                    "ambient containers");
        }
        require(result.expectedHeads().size() == 2, "two document heads");

        MyOsClosureCommitBatch commitBatch = result.finalizeDocuments(
                Arrays.asList(
                        finalized(a, "A0", "MASTER#0", 0L, true),
                        finalized(b, "B0", "MASTER#1", 1L, false)));
        require(commitBatch.finalizedDocuments().size() == 2,
                "two finalized documents");
        System.out.println(
                "BLUE_SEPARATE_DOCUMENT_EXECUTION_TEMPLATE_SHAPE_SMOKE_OK");
    }

    private static DocumentStepInput step(
            long ordinal,
            DocumentId targetDocumentId,
            String currentA,
            String currentB,
            String workId) {
        DocumentId a = new DocumentId("a");
        DocumentId b = new DocumentId("b");
        Map<DocumentId, String> currentBlueIds =
                new LinkedHashMap<DocumentId, String>();
        currentBlueIds.put(a, currentA);
        currentBlueIds.put(b, currentB);
        String targetBlueId = targetDocumentId.equals(a) ? currentA : currentB;
        TentativeResolutionContext context =
                TentativeResolutionContext.fromBoundEvidence(
                        ordinal == 0L
                                ? "sha256:closure"
                                : "sha256:tentative-closure-" + ordinal,
                        "sha256:invocation",
                        0L,
                        targetDocumentId,
                        targetBlueId,
                        "sha256:component",
                        "sha256:component-state-" + ordinal,
                        "sha256:proof-" + ordinal,
                        currentBlueIds,
                        "sha256:provider",
                        "sha256:bindings");
        return new DocumentStepInput(
                ordinal,
                ordinal,
                targetDocumentId,
                targetBlueId,
                workId,
                "payload-" + ordinal,
                context,
                Collections.<DocumentId>emptyList());
    }

    private static ResultingDocument finalized(
            DocumentId documentId,
            String beforeBlueId,
            String afterBlueId,
            long memberIndex,
            boolean publicRoot) {
        return new ResultingDocument(
                documentId,
                beforeBlueId,
                afterBlueId,
                new Object(),
                true,
                false,
                publicRoot,
                1L,
                1L,
                "sha256:component",
                "sha256:final-component-state",
                Long.valueOf(memberIndex));
    }

    private static void require(boolean condition, String label) {
        if (!condition) {
            throw new IllegalStateException(label);
        }
    }
}
