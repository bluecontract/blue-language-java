package blue.language.provider;

import blue.language.provider.NodeProvider;

/**
 * Final Language-owned capability proving that provider results cross the
 * standard identity-verification boundary.
 *
 * <p>The class is final by design. {@link blue.language.provider.NodeProviderWrapper}
 * may therefore recognize its exact runtime type without allowing a caller to
 * inherit the capability and override the verified lookup behavior.</p>
 */
public final class VerifiedNodeProvider extends VerifyingNodeProvider {

    /**
     * Creates a verification boundary over an arbitrary provider transport.
     *
     * @param delegate provider whose ordinary and cyclic evidence must verify
     */
    public VerifiedNodeProvider(NodeProvider delegate) {
        super(delegate);
    }
}
