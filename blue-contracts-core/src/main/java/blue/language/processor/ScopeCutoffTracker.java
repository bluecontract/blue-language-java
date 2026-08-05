package blue.language.processor;

import blue.language.processor.model.JsonPatch;
import blue.language.identity.DirectBlueIdCalculator;

import java.util.Objects;

/** Applies monotonic cut-off when an active embedded occurrence is replaced. */
final class ScopeCutoffTracker {

    private final ProcessingCutoffTracker cutoff;
    private final DocumentProcessingRuntime runtime;

    ScopeCutoffTracker(ProcessorInvocationState execution) {
        ProcessorInvocationState checked = Objects.requireNonNull(
                execution, "execution");
        this.cutoff = new ProcessingCutoffTracker(checked);
        this.runtime = checked.runtime();
    }

    void recordEmbeddedReplacement(
            String scopePath,
            ContractBundle bundle,
            DocumentUpdateData update) {
        if (bundle == null || bundle.embeddedPaths().isEmpty()) {
            return;
        }
        String changedPath = ProcessorEngine.normalizePointer(
                update.path());
        for (String embeddedPointer : bundle.embeddedPaths()) {
            String childScope = ProcessorEngine.resolvePointer(
                    scopePath, embeddedPointer);
            if (!changedPath.equals(childScope)) {
                continue;
            }
            JsonPatch.Op operation = update.op();
            boolean replacesExistingOccurrence =
                    operation == JsonPatch.Op.REMOVE
                    || operation == JsonPatch.Op.REPLACE
                    || (operation == JsonPatch.Op.ADD
                            && update.beforePresent());
            if (replacesExistingOccurrence) {
                if (update.beforePresent()
                        && update.afterPresent()
                        && semanticallyEqual(
                        update.before(), update.after())) {
                    continue;
                }
                runtime.recordReplacedEmbeddedScope(childScope);
                cutoff.markCutOff(childScope);
            }
        }
    }

    boolean shouldStop(String scopePath) {
        return cutoff.shouldStop(scopePath);
    }

    private boolean semanticallyEqual(
            blue.language.model.Node left,
            blue.language.model.Node right) {
        if (left == null || right == null) {
            return left == right;
        }
        return DirectBlueIdCalculator.calculateUncheckedBlueId(left).equals(
                DirectBlueIdCalculator.calculateUncheckedBlueId(right));
    }
}
