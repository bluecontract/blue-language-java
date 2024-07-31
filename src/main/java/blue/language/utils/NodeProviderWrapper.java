package blue.language.utils;

import blue.language.NodeProvider;
import blue.language.provider.BootstrapProvider;
import blue.language.provider.SequentialNodeProvider;

import java.util.Arrays;

public class NodeProviderWrapper {
    public static NodeProvider wrap(NodeProvider originalProvider) {
        return new SequentialNodeProvider(
                Arrays.asList(
                        BootstrapProvider.INSTANCE,
                        originalProvider
                )
        );
    }
}