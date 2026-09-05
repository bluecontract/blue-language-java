package blue.coordination.closure;

import blue.contracts.closure.DocumentId;
import blue.contracts.closure.DocumentStepEvidence;
import blue.contracts.closure.DocumentStepInput;
import blue.contracts.closure.DocumentStepProcessor;
import blue.contracts.closure.LocalDocumentStepResult;
import blue.contracts.closure.ResultingDocument;
import blue.contracts.closure.SharedGasMeter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Demonstrates separate document evaluation with closure-wide staging.
 */
public final class MyOsDocumentStepCoordinator {
    private final DocumentStepProcessor processor;

    public MyOsDocumentStepCoordinator(DocumentStepProcessor processor) {
        this.processor = Objects.requireNonNull(processor, "processor");
    }

    public Result execute(
            String inputClosureIdentity,
            String invocationIdentity,
            List<DocumentStepInput> inputs,
            SharedGasMeter sharedMeter) {
        ArrayList<LocalDocumentStepResult> staged =
                new ArrayList<LocalDocumentStepResult>();
        ArrayList<DocumentStepEvidence> evidence =
                new ArrayList<DocumentStepEvidence>();
        LinkedHashMap<DocumentId, String> expectedHeads =
                new LinkedHashMap<DocumentId, String>();
        for (DocumentStepInput input
                : Objects.requireNonNull(inputs, "inputs")) {
            if (!invocationIdentity.equals(
                    input.resolutionContext().invocationIdentity())) {
                throw new IllegalArgumentException(
                        "step resolution invocation mismatch");
            }
            expectedHeads.putIfAbsent(
                    input.targetDocumentId(), input.exactDocumentBlueId());
            staged.add(processor.process(input, sharedMeter));
            evidence.add(new DocumentStepEvidence(input));
        }
        return new Result(
                inputClosureIdentity,
                invocationIdentity,
                expectedHeads,
                staged,
                evidence);
    }

    public static final class Result {
        private final String inputClosureIdentity;
        private final String invocationIdentity;
        private final Map<DocumentId, String> expectedHeads;
        private final List<LocalDocumentStepResult> localSteps;
        private final List<DocumentStepEvidence> evidence;

        private Result(
                String inputClosureIdentity,
                String invocationIdentity,
                Map<DocumentId, String> expectedHeads,
                List<LocalDocumentStepResult> localSteps,
                List<DocumentStepEvidence> evidence) {
            this.inputClosureIdentity = inputClosureIdentity;
            this.invocationIdentity = invocationIdentity;
            this.expectedHeads = Collections.unmodifiableMap(
                    new LinkedHashMap<DocumentId, String>(expectedHeads));
            this.localSteps = Collections.unmodifiableList(
                    new ArrayList<LocalDocumentStepResult>(localSteps));
            this.evidence = Collections.unmodifiableList(
                    new ArrayList<DocumentStepEvidence>(evidence));
        }

        public Map<DocumentId, String> expectedHeads() { return expectedHeads; }
        public List<LocalDocumentStepResult> localSteps() { return localSteps; }
        public List<DocumentStepEvidence> evidence() { return evidence; }

        /**
         * Crosses the orchestrator boundary only after all local bodies have
         * received their exact ordinary or complete-set cyclic identities.
         */
        public MyOsClosureCommitBatch finalizeDocuments(
                List<ResultingDocument> finalizedDocuments) {
            return new MyOsClosureCommitBatch(
                    inputClosureIdentity,
                    invocationIdentity,
                    expectedHeads,
                    finalizedDocuments);
        }
    }
}
