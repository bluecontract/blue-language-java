package blue.language.processor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Retains ordered evidence produced by committed patch batches. */
final class ProcessingCommittedEvidence {

    private final List<ManagedGeneralizationWrite> generalizationWrites =
            new ArrayList<ManagedGeneralizationWrite>();
    private int authoredPatchCount;

    void record(BatchPatchResult result) {
        BatchPatchResult exact = Objects.requireNonNull(result, "result");
        for (BatchPatchResult.GeneralizationMetadataWrite write
                : exact.generalizationMetadataWrites()) {
            generalizationWrites.add(new ManagedGeneralizationWrite(
                    write.path(),
                    write.value().blueId(),
                    authoredPatchCount + write.requiringPatchIndex()));
        }
        authoredPatchCount += exact.requestedPatches().size();
    }

    List<ManagedGeneralizationWrite> generalizationWrites() {
        return Collections.unmodifiableList(
                new ArrayList<ManagedGeneralizationWrite>(
                        generalizationWrites));
    }
}
