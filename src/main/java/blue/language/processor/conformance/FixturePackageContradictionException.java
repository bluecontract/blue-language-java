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

    private final String fixtureId;
    private final String control;

    public FixturePackageContradictionException(String fixtureId,
                                                String control,
                                                String reason) {
        super(message(fixtureId, control, reason));
        this.fixtureId = require(fixtureId, "fixtureId");
        this.control = require(control, "control");
        require(reason, "reason");
    }

    public String fixtureId() {
        return fixtureId;
    }

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
