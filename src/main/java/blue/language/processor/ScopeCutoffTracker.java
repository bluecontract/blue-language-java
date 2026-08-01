package blue.language.processor;

import blue.language.processor.model.JsonPatch;
import blue.language.utils.BlueIdCalculator;

import java.util.Objects;

/** Applies monotonic cut-off when an active embedded occurrence is replaced. */
final class ScopeCutoffTracker {

    private final ProcessingCutoffTracker cutoff;

    ScopeCutoffTracker(ProcessorInvocationState execution) {
        this.cutoff = new ProcessingCutoffTracker(
                Objects.requireNonNull(execution, "execution"));
    }

    void recordEmbeddedReplacement(
            String scopePath,
            ContractBundle bundle,
            DocumentProcessingRuntime.DocumentUpdateData update) {
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
            if (operation == JsonPatch.Op.REMOVE
                    || operation == JsonPatch.Op.REPLACE) {
                if (operation == JsonPatch.Op.REPLACE
                        && update.beforePresent()
                        && update.afterPresent()
                        && semanticallyEqual(
                        update.before(), update.after())) {
                    continue;
                }
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
        return BlueIdCalculator.calculateUncheckedBlueId(left).equals(
                BlueIdCalculator.calculateUncheckedBlueId(right));
    }
}
