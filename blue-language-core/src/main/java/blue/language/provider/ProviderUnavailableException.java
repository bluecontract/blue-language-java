package blue.language.provider;

import blue.language.api.NodeProviderOutcome;

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

    /**
     * Creates a transient provider-evidence failure.
     *
     * @param diagnostic stable non-null failure description
     */
    public ProviderUnavailableException(String diagnostic) {
        super(diagnostic);
    }
}
