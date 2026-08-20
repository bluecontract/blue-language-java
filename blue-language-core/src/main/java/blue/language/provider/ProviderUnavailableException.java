package blue.language.provider;

import blue.language.api.NodeProviderOutcome;
import blue.language.identity.BlueIds;

import java.util.Objects;
import java.util.Optional;

/**
 * Signals that exact provider evidence may exist but cannot currently be
 * acquired.
 *
 * <p>This exception is used only when a legacy list-returning lookup must carry
 * the richer {@link NodeProviderOutcome#UNAVAILABLE} conclusion through a
 * resolution stack. Result-returning provider boundaries convert it back to
 * the corresponding transport-neutral outcome.</p>
 */
public final class ProviderUnavailableException
        extends IllegalStateException {

    /** Exact retry subject when known at the lookup boundary. */
    private final String requiredExactBlueId;

    /**
     * Creates a transient provider-evidence failure.
     *
     * @param diagnostic stable non-null failure description
     */
    public ProviderUnavailableException(String diagnostic) {
        this(null, diagnostic);
    }

    /**
     * Creates a transient failure for one exact requested identity.
     *
     * @param requiredExactBlueId exact unavailable provider identity
     * @param diagnostic stable non-null failure description
     */
    public ProviderUnavailableException(
            String requiredExactBlueId,
            String diagnostic) {
        super(Objects.requireNonNull(diagnostic, "diagnostic"));
        this.requiredExactBlueId = requiredExactBlueId == null
                ? null
                : BlueIds.requireBlueIdOrCyclicMember(
                        requiredExactBlueId, "requiredExactBlueId");
    }

    /**
     * Returns the exact identity whose acquisition may be retried.
     *
     * @return exact requested BlueId when the lookup boundary supplied it
     */
    public Optional<String> requiredExactBlueId() {
        return Optional.ofNullable(requiredExactBlueId);
    }
}
