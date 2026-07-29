package blue.language.processor;

/**
 * Pre-commit validator for a changed external subscription surface.
 *
 * <p>The validator receives an immutable, revision-bound context and returns
 * the exact validated delta. Failure prevents the candidate document and its
 * checkpoint effects from committing.</p>
 */
@FunctionalInterface
public interface SubscriptionSurfaceValidator {

    /**
     * Validates one immutable candidate surface before commit.
     *
     * @param context revision-bound validation context
     * @return exact immutable subscription delta
     * @throws SubscriptionSurfaceInvalidException when commit must be rejected
     */
    SubscriptionDelta validate(
            SubscriptionSurfaceValidationContext context);
}
