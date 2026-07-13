package blue.language.utils;

import blue.language.NodeProvider;
import blue.language.processor.registry.BlueRuntimeTypeRegistry;
import blue.language.provider.BootstrapProvider;
import blue.language.provider.SequentialNodeProvider;
import blue.language.provider.VerifyingNodeProvider;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class NodeProviderWrapper {
    public static NodeProvider wrap(NodeProvider originalProvider) {
        if (isAlreadyWrapped(originalProvider)) {
            return withRuntimeProvider(originalProvider);
        }
        if (originalProvider instanceof UnverifiedNodeProvider) {
            return new SequentialNodeProvider(
                    Arrays.asList(
                            BootstrapProvider.INSTANCE,
                            BlueRuntimeTypeRegistry.getDefault().asProcessorSnapshotProvider(),
                            originalProvider
                    )
            );
        }
        return new SequentialNodeProvider(
                Arrays.asList(
                        BootstrapProvider.INSTANCE,
                        BlueRuntimeTypeRegistry.getDefault().asProcessorSnapshotProvider(),
                        new VerifyingNodeProvider(originalProvider)
                )
        );
    }

    public static NodeProvider unverified(NodeProvider originalProvider) {
        return new UnverifiedNodeProvider(originalProvider);
    }

    /**
     * Identifies the existing explicit host-trust wrapper without extending
     * that trust to adjacent providers in a composite.
     */
    public static boolean isExplicitlyHostTrusted(NodeProvider provider) {
        return provider instanceof UnverifiedNodeProvider;
    }

    private static boolean isAlreadyWrapped(NodeProvider originalProvider) {
        if (!(originalProvider instanceof SequentialNodeProvider)) {
            return false;
        }
        return ((SequentialNodeProvider) originalProvider).getNodeProviders().stream()
                .anyMatch(provider -> provider == BootstrapProvider.INSTANCE
                        || provider instanceof VerifyingNodeProvider
                        || provider instanceof UnverifiedNodeProvider);
    }

    private static NodeProvider withRuntimeProvider(NodeProvider originalProvider) {
        if (!(originalProvider instanceof SequentialNodeProvider)) {
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

    private static class UnverifiedNodeProvider implements NodeProvider {
        private final NodeProvider delegate;

        private UnverifiedNodeProvider(NodeProvider delegate) {
            this.delegate = delegate;
        }

        @Override
        public java.util.List<blue.language.model.Node> fetchByBlueId(String blueId) {
            return delegate.fetchByBlueId(blueId);
        }
    }
}
