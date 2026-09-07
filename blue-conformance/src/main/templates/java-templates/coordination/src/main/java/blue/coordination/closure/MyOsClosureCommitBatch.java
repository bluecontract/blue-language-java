package blue.coordination.closure;

import blue.contracts.closure.DocumentId;
import blue.contracts.closure.ResultingDocument;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Storage-neutral MyOS shape: finalized per-document results, one fenced
 * commit. Local step bodies are not commit-ready until the Contracts
 * orchestrator supplies their exact after identities.
 */
public final class MyOsClosureCommitBatch {
    private final String inputClosureIdentity;
    private final String invocationIdentity;
    private final Map<DocumentId, String> expectedHeads;
    private final List<ResultingDocument> finalizedDocuments;

    public MyOsClosureCommitBatch(
            String inputClosureIdentity,
            String invocationIdentity,
            Map<DocumentId, String> expectedHeads,
            List<ResultingDocument> finalizedDocuments) {
        this.inputClosureIdentity = Objects.requireNonNull(
                inputClosureIdentity, "inputClosureIdentity");
        this.invocationIdentity = Objects.requireNonNull(
                invocationIdentity, "invocationIdentity");
        LinkedHashMap<DocumentId, String> heads =
                new LinkedHashMap<DocumentId, String>();
        for (Map.Entry<DocumentId, String> entry
                : Objects.requireNonNull(expectedHeads, "expectedHeads").entrySet()) {
            heads.put(
                    Objects.requireNonNull(entry.getKey(), "documentId"),
                    Objects.requireNonNull(entry.getValue(), "expectedHead"));
        }
        this.expectedHeads = Collections.unmodifiableMap(heads);
        ArrayList<ResultingDocument> results =
                new ArrayList<ResultingDocument>();
        DocumentId previous = null;
        for (ResultingDocument result
                : Objects.requireNonNull(
                        finalizedDocuments, "finalizedDocuments")) {
            ResultingDocument exact = Objects.requireNonNull(
                    result, "finalizedDocument");
            if (previous != null
                    && previous.compareTo(exact.documentId()) >= 0) {
                throw new IllegalArgumentException(
                        "finalizedDocuments not in canonical order");
            }
            String expectedHead = this.expectedHeads.get(exact.documentId());
            if (expectedHead == null
                    || !expectedHead.equals(exact.beforeBlueId())) {
                throw new IllegalArgumentException(
                        "finalized document head mismatch");
            }
            results.add(exact);
            previous = exact.documentId();
        }
        this.finalizedDocuments = Collections.unmodifiableList(results);
    }

    public String inputClosureIdentity() { return inputClosureIdentity; }
    public String invocationIdentity() { return invocationIdentity; }
    public Map<DocumentId, String> expectedHeads() { return expectedHeads; }
    public List<ResultingDocument> finalizedDocuments() {
        return finalizedDocuments;
    }
}
