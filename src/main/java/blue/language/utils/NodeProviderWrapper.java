package blue.language.utils;

import blue.language.NodeProvider;
import blue.language.provider.BootstrapProvider;
import blue.language.provider.SequentialNodeProvider;
import blue.language.provider.VerifyingNodeProvider;

import java.util.Arrays;

public class NodeProviderWrapper {
    public static NodeProvider wrap(NodeProvider originalProvider) {
        if (isAlreadyWrapped(originalProvider)) {
            return originalProvider;
        }
        if (originalProvider instanceof UnverifiedNodeProvider) {
            return new SequentialNodeProvider(
                    Arrays.asList(
                            BootstrapProvider.INSTANCE,
                            originalProvider
                    )
            );
        }
        return new SequentialNodeProvider(
                Arrays.asList(
                        BootstrapProvider.INSTANCE,
                        new VerifyingNodeProvider(originalProvider)
                )
        );
    }

    public static NodeProvider unverified(NodeProvider originalProvider) {
        return new UnverifiedNodeProvider(originalProvider);
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
