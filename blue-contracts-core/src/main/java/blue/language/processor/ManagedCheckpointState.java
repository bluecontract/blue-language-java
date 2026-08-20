package blue.language.processor;

import java.util.Objects;

/** Exact nullable checkpoint-entry side used by comparison and settlement. */
public final class ManagedCheckpointState {

    private static final ManagedCheckpointState ABSENT =
            new ManagedCheckpointState(null, null);

    private final ManagedCheckpointDomain domain;
    private final String subjectBlueId;

    ManagedCheckpointState(
            ManagedCheckpointDomain domain,
            String subjectBlueId) {
        if ((domain == null) != (subjectBlueId == null)) {
            throw new IllegalArgumentException(
                    "Checkpoint domain and subject must be present together");
        }
        if (subjectBlueId != null && subjectBlueId.isEmpty()) {
            throw new IllegalArgumentException(
                    "subjectBlueId must be non-empty");
        }
        this.domain = domain;
        this.subjectBlueId = subjectBlueId;
    }

    static ManagedCheckpointState absent() { return ABSENT; }

    /**
     * Reports whether the raw-key entry exists.
     *
     * @return whether this is a present checkpoint side
     */
    public boolean present() { return domain != null; }

    /**
     * Returns the complete exact domain when the entry exists.
     *
     * @return immutable domain DTO, or {@code null}
     */
    public ManagedCheckpointDomain domain() { return domain; }

    /**
     * Returns the domain BlueId when the entry exists.
     *
     * @return exact domain BlueId, or {@code null}
     */
    public String domainBlueId() {
        return domain != null ? domain.blueId() : null;
    }

    /**
     * Returns the exact checkpoint subject identity when present.
     *
     * @return exact subject BlueId, or {@code null}
     */
    public String subjectBlueId() { return subjectBlueId; }

    boolean sameValue(ManagedCheckpointState other) {
        return other != null
                && present() == other.present()
                && Objects.equals(domainBlueId(), other.domainBlueId())
                && Objects.equals(subjectBlueId, other.subjectBlueId);
    }
}
