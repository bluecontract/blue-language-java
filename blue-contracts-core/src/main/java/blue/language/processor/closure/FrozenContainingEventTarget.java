package blue.language.processor.closure;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * One exact containing-occurrence route frozen when an application event is
 * emitted.
 *
 * <p>The lineage is ordered from the receiving ancestor toward the event
 * source.  Keeping every edge allows the closure session to revalidate the
 * complete transitive route before delivery.</p>
 */
final class FrozenContainingEventTarget {

    private final DocumentId receivingDocumentId;
    private final String sourcePath;
    private final List<ManagedOccurrenceBinding> lineage;

    FrozenContainingEventTarget(
            DocumentId receivingDocumentId,
            String sourcePath,
            List<ManagedOccurrenceBinding> lineage) {
        this.receivingDocumentId = Objects.requireNonNull(
                receivingDocumentId, "receivingDocumentId");
        this.sourcePath = ClosureValueSupport.requireAbsolutePointer(
                sourcePath, "sourcePath");
        if ("/".equals(this.sourcePath)) {
            throw new IllegalArgumentException(
                    "A containing event target must have a non-Root path");
        }
        ArrayList<ManagedOccurrenceBinding> selected =
                new ArrayList<ManagedOccurrenceBinding>(
                        Objects.requireNonNull(lineage, "lineage"));
        if (selected.isEmpty()) {
            throw new IllegalArgumentException(
                    "A containing event target must have a binding lineage");
        }
        if (!this.receivingDocumentId.equals(
                selected.get(0).sourceDocumentId())) {
            throw new IllegalArgumentException(
                    "The outer binding must belong to the receiving document");
        }
        for (int index = 0; index + 1 < selected.size(); index++) {
            ManagedOccurrenceBinding outer = selected.get(index);
            ManagedOccurrenceBinding inner = selected.get(index + 1);
            if (!outer.targetDocumentId().equals(
                    inner.sourceDocumentId())) {
                throw new IllegalArgumentException(
                        "Containing event lineage is not contiguous");
            }
        }
        this.lineage = Collections.unmodifiableList(selected);
    }

    static FrozenContainingEventTarget direct(
            ManagedOccurrenceBinding binding) {
        ManagedOccurrenceBinding selected = Objects.requireNonNull(
                binding, "binding");
        return new FrozenContainingEventTarget(
                selected.sourceDocumentId(),
                selected.sourcePath(),
                Collections.singletonList(selected));
    }

    DocumentId receivingDocumentId() {
        return receivingDocumentId;
    }

    String sourcePath() {
        return sourcePath;
    }

    List<ManagedOccurrenceBinding> lineage() {
        return lineage;
    }

    ManagedOccurrenceBinding directBinding() {
        if (lineage.size() != 1) {
            throw new IllegalStateException(
                    "Imported managed events require one direct occurrence");
        }
        return lineage.get(0);
    }

    String attributionIdentity() {
        StringBuilder result = new StringBuilder();
        for (ManagedOccurrenceBinding binding : lineage) {
            if (result.length() > 0) {
                result.append('.');
            }
            result.append(binding.occurrenceIdentity());
        }
        return result.toString();
    }
}
