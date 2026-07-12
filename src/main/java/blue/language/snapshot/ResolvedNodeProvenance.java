package blue.language.snapshot;

/** Persisted origin of an effective node in a resolved snapshot. */
public enum ResolvedNodeProvenance {
    SOURCE_REFERENCE("source-reference"),
    INSTANCE_SUPPLIED("instance-supplied"),
    PROVIDER_MATERIALIZED("provider-materialized");

    private final String fixtureName;

    ResolvedNodeProvenance(String fixtureName) {
        this.fixtureName = fixtureName;
    }

    @Override
    public String toString() {
        return fixtureName;
    }
}
