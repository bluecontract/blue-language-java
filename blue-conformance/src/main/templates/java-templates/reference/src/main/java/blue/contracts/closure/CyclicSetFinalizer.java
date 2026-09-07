package blue.contracts.closure;

import java.util.List;
import java.util.Map;
public interface CyclicSetFinalizer {
    /**
     * Returns only the unchanged Language semantic result. The Contracts
     * processor assigns invocation ordinals and interleaving boundaries in a
     * {@link TentativeFinalizationResult.ProcessorEvidence} wrapper.
     */
    TentativeFinalizationResult finalizeCompleteSet(
            List<DocumentId> canonicalMembers,
            Map<DocumentId, Object> exactTentativeDocuments);
    String implementationIdentity();
}
