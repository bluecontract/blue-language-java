package blue.language.processor.conformance;

/**
 * Signals that a closed conformance-package control cannot be exercised by
 * the fixture content that declares it.
 *
 * <p>This is distinct from an implementation failure and from malformed
 * fixture syntax. It lets a conformance report identify a deterministic
 * package defect instead of silently passing a vacuous control or inventing
 * document topology that the published fixture did not declare.</p>
 */
public final class FixturePackageContradictionException
        extends IllegalArgumentException {

    /** Published fixture identifier serialized with the contradiction. */
    private final String fixtureId;
    /** Closed-package control that could not be exercised. */
    private final String control;

    /**
     * Creates a contradiction for one published fixture control.
     *
     * @param fixtureId non-empty fixture identifier
     * @param control non-empty control name
     * @param reason non-empty contradiction reason
     * @throws IllegalArgumentException if any argument is blank
     */
    public FixturePackageContradictionException(String fixtureId,
                                                String control,
                                                String reason) {
        super(message(fixtureId, control, reason));
        this.fixtureId = require(fixtureId, "fixtureId");
        this.control = require(control, "control");
        require(reason, "reason");
    }

    /**
     * Returns the contradictory fixture identifier.
     *
     * @return non-empty fixture identifier
     */
    public String fixtureId() {
        return fixtureId;
    }

    /**
     * Returns the control that could not be exercised.
     *
     * @return non-empty control name
     */
    public String control() {
        return control;
    }

    private static String message(String fixtureId,
                                  String control,
                                  String reason) {
        return "Published fixture " + require(fixtureId, "fixtureId")
                + " cannot execute " + require(control, "control")
                + ": " + require(reason, "reason");
    }

    private static String require(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }
}
