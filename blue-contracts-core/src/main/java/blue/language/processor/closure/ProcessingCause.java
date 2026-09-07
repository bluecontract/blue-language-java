package blue.language.processor.closure;

/** Closed Java-8 cause family for one affected-closure invocation. */
public abstract class ProcessingCause {

    /** Stable cause discriminator. */
    public enum Kind {
        /** External event cause. */
        EXTERNAL("external"),
        /** One contiguous managed-document revision. */
        MANAGED_REVISION("managed-revision"),
        /** One authenticated historical same-epoch representation step. */
        MANAGED_REPRESENTATION("managed-representation"),
        /** Closure admission cause. */
        ADMISSION("admission");

        private final String wireValue;

        Kind(String wireValue) {
            this.wireValue = wireValue;
        }

        /**
         * Returns the stable serialization value.
         *
         * @return cause-kind wire value
         */
        public String wireValue() {
            return wireValue;
        }
    }

    private final String causeIdentity;

    ProcessingCause(String causeIdentity) {
        this.causeIdentity = ClosureValueSupport.requireSha256Identity(
                causeIdentity, "causeIdentity");
    }

    /**
     * Returns the documented value.
     *
     * @return exact cause identity
     */
    public final String causeIdentity() {
        return causeIdentity;
    }

    /**
     * Returns the documented value.
     *
     * @return closed cause discriminator
     */
    public abstract Kind kind();
}
