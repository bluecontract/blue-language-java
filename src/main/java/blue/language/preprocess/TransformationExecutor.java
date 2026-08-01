package blue.language.preprocess;

import blue.language.model.Node;

import java.util.Objects;

/** Executes one already-frozen transformation plan exactly once in order. */
public final class TransformationExecutor {

    private final StandardPreprocessingPipeline standardPipeline;

    /** Creates an executor with the mandatory Language baseline pipeline. */
    public TransformationExecutor(
            StandardPreprocessingPipeline standardPipeline) {
        this.standardPipeline = Objects.requireNonNull(
                standardPipeline, "standardPipeline");
    }

    /**
     * Removes {@code blue}, executes the frozen plan, then runs the baseline.
     */
    Node execute(
            Node source,
            PreprocessingPlan plan,
            PreprocessingContext context) {
        Node working = source.clone();
        working.blue(null);
        for (TransformationSnapshot transformation
                : plan.transformations()) {
            working = transformation.apply(working, context);
            PreprocessingLimits.requireGraphWithinBounds(
                    working, "transformation output");
            standardPipeline.rejectBlueDirective(working);
        }
        Node preprocessed = standardPipeline.apply(
                working, plan.effectiveImports());
        PreprocessingLimits.requireGraphWithinBounds(
                preprocessed, "Preprocessed Document");
        return preprocessed;
    }
}
