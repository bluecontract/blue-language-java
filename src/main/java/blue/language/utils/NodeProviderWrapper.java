package blue.language.utils;

import blue.language.NodeProvider;
import blue.language.provider.BootstrapProvider;
import blue.language.provider.PotentialBlueIdNodeProvider;
import blue.language.provider.SequentialNodeProvider;
import blue.language.provider.VerifiedNodeProvider;
import blue.language.provider.VerifyingNodeProvider;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Builds the verified provider graph used by Language operations.
 *
 * <p>The Language bootstrap provider is inserted ahead of caller providers,
 * and every external result-producing leaf is independently evidence-verified.
 * Runtime-specific providers must be composed explicitly by the owning
 * runtime before this Language boundary is applied.</p>
 */
public class NodeProviderWrapper {

    /**
     * Creates a provider-graph wrapper helper.
     */
    public NodeProviderWrapper() {
    }

    /**
     * Returns a provider graph with bootstrap and verification boundaries.
     *
     * @param originalProvider caller-supplied provider graph
     * @return secured provider graph
     */
    public static NodeProvider wrap(NodeProvider originalProvider) {
        NodeProvider verifiedProvider =
                verifyProviderGraph(originalProvider);
        if (hasBootstrapAtTopLevel(verifiedProvider)) {
            return verifiedProvider;
        }
        return new SequentialNodeProvider(
                Arrays.asList(
                        BootstrapProvider.INSTANCE,
                        verifiedProvider
                )
        );
    }

    /**
     * Binary-compatibility entry point for callers compiled against the
     * legacy method name.
     *
     * <p>Language 1.0 has no host-trusted provider bypass. Despite the legacy
     * name, this method applies the same strict direct-node verification as
     * {@link #wrap(NodeProvider)}.</p>
     *
     * @param originalProvider caller-supplied provider graph
     * @return secured provider graph
     */
    public static NodeProvider unverified(
            NodeProvider originalProvider) {
        return wrap(originalProvider);
    }

    /**
     * Reports the Language 1.0 trust rule to released callers that still
     * probe the former host-trust marker.
     *
     * @param provider provider being probed
     * @return always {@code false}
     */
    public static boolean isExplicitlyHostTrusted(
            NodeProvider provider) {
        return false;
    }

    /**
     * Secures every result-producing leaf independently. This preserves
     * cyclic-set-aware verification while preventing one verified sibling
     * from conferring trust on an unrelated plain sibling.
     */
    private static NodeProvider verifyProviderGraph(
            NodeProvider provider) {
        if (provider == null) {
            throw new NullPointerException("provider");
        }
        if (provider == BootstrapProvider.INSTANCE
                || provider.getClass()
                == VerifyingNodeProvider.class
                || provider.getClass()
                == VerifiedNodeProvider.class) {
            return provider;
        }
        if (provider.getClass()
                == PotentialBlueIdNodeProvider.class) {
            PotentialBlueIdNodeProvider filtered =
                    (PotentialBlueIdNodeProvider) provider;
            NodeProvider verifiedDelegate =
                    verifyProviderGraph(filtered.delegate());
            return verifiedDelegate == filtered.delegate()
                    ? filtered
                    : new PotentialBlueIdNodeProvider(
                    verifiedDelegate);
        }
        if (provider.getClass()
                == SequentialNodeProvider.class) {
            List<NodeProvider> providers =
                    ((SequentialNodeProvider) provider)
                            .getNodeProviders();
            List<NodeProvider> verified =
                    new ArrayList<>(providers.size());
            boolean changed = false;
            for (NodeProvider member : providers) {
                NodeProvider secured =
                        verifyProviderGraph(member);
                verified.add(secured);
                changed |= secured != member;
            }
            return changed
                    ? new SequentialNodeProvider(verified)
                    : provider;
        }
        return new VerifyingNodeProvider(provider);
    }

    private static boolean hasBootstrapAtTopLevel(
            NodeProvider provider) {
        return provider instanceof SequentialNodeProvider
                && provider.getClass()
                == SequentialNodeProvider.class
                && ((SequentialNodeProvider) provider)
                .getNodeProviders().stream()
                .anyMatch(member ->
                        member == BootstrapProvider.INSTANCE);
    }

}
