package blue.language.processor;

import blue.language.model.Node;

import java.util.Set;

/**
 * Pre-commit validator for the changed external subscription surface.
 */
@FunctionalInterface
public interface SubscriptionSurfaceValidator {

    SubscriptionDelta validate(Node inputRoot,
                               Node tentativeRoot,
                               Set<String> changedPaths,
                               GasSchedule schedule);

    /**
     * Production validation seam carrying immutable resolution and interval
     * evidence. Existing custom validators remain source-compatible and
     * receive the original four semantic arguments by default.
     */
    default SubscriptionDelta validate(
            SubscriptionSurfaceValidationContext context) {
        return validate(
                context.inputRoot(),
                context.tentativeRoot(),
                context.changedPaths(),
                context.gasSchedule());
    }
}
