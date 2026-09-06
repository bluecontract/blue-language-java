package blue.language.processor.closure;

import blue.language.model.Node;
import blue.language.provider.CyclicSetProof;
import java.util.Optional;

/** One explicit historical step; revision and representation causes stay distinct. */
interface ManagedHistoryStep {
    String causeIdentity();
    String targetOccurrenceIdentity();
    DocumentId childDocumentId();
    long fromEpoch();
    long toEpoch();
    String beforeBlueId();
    String afterBlueId();
    Node afterDocument();
    String originalSourceCauseIdentity();
    String sourceRevisionReceiptIdentity();
    Optional<ManagedDocumentTransitionReceipt> sourceTransitionReceipt();
    Optional<CyclicSetProof> afterCyclicProof();
}
