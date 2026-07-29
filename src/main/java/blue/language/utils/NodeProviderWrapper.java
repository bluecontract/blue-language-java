package blue.language.utils;

import blue.language.NodeProvider;
import blue.language.processor.registry.BlueRuntimeTypeRegistry;
import blue.language.provider.BootstrapProvider;
import blue.language.provider.PotentialBlueIdNodeProvider;
import blue.language.provider.SequentialNodeProvider;
import blue.language.provider.VerifyingNodeProvider;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Builds the verified provider graph used by Language operations.
 *
 * <p>Bootstrap and runtime-type providers are inserted ahead of caller
 * providers, and every external result-producing leaf is independently
 * evidence-verified. Existing equivalent wrappers are retained.</p>
 */
public class NodeProviderWrapper {

    /**
     * Creates a provider-graph wrapper helper.
     */
    public NodeProviderWrapper() {
    }

    /**
     * Returns a provider graph with bootstrap, runtime, and verification boundaries.
     *
     * @param originalProvider caller-supplied provider graph
     * @return secured provider graph
     */
    public static NodeProvider wrap(NodeProvider originalProvider) {
        NodeProvider verifiedProvider =
                verifyProviderGraph(originalProvider);
        if (hasBootstrapAtTopLevel(verifiedProvider)) {
            return withRuntimeProvider(verifiedProvider);
        }
        return new SequentialNodeProvider(
                Arrays.asList(
                        BootstrapProvider.INSTANCE,
                        BlueRuntimeTypeRegistry.getDefault().asProcessorSnapshotProvider(),
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
        NodeProvider runtimeProvider =
                BlueRuntimeTypeRegistry.getDefault()
                        .asProcessorSnapshotProvider();
        if (provider == BootstrapProvider.INSTANCE
                || provider == runtimeProvider
                || provider.getClass()
                == VerifyingNodeProvider.class) {
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

    private static NodeProvider withRuntimeProvider(NodeProvider originalProvider) {
        if (originalProvider.getClass()
                != SequentialNodeProvider.class) {
            return originalProvider;
        }
        NodeProvider runtimeProvider = BlueRuntimeTypeRegistry.getDefault().asProcessorSnapshotProvider();
        List<NodeProvider> providers = ((SequentialNodeProvider) originalProvider).getNodeProviders();
        if (providers.stream().anyMatch(provider -> provider == runtimeProvider)) {
            return originalProvider;
        }
        List<NodeProvider> wrapped = new ArrayList<>(providers.size() + 1);
        boolean inserted = false;
        for (NodeProvider provider : providers) {
            wrapped.add(provider);
            if (!inserted && provider == BootstrapProvider.INSTANCE) {
                wrapped.add(runtimeProvider);
                inserted = true;
            }
        }
        if (!inserted) {
            wrapped.add(0, runtimeProvider);
        }
        return new SequentialNodeProvider(wrapped);
    }

}
