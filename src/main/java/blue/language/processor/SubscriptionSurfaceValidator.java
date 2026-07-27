package blue.language.processor;

/**
 * Pre-commit validator for the changed external subscription surface.
 */
@FunctionalInterface
public interface SubscriptionSurfaceValidator {

    SubscriptionDelta validate(
            SubscriptionSurfaceValidationContext context);
}
