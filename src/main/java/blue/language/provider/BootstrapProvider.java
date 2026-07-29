package blue.language.provider;

import blue.language.NodeProvider;
import blue.language.model.Node;
import blue.language.registry.BlueCoreTypeRegistry;

import java.io.IOException;
import java.util.List;

import static blue.language.provider.ClasspathBasedNodeProvider.NO_PREPROCESSING;

/**
 * Singleton provider for the canonical core registry and bundled preprocessing
 * transformation definitions.
 */
public class BootstrapProvider implements NodeProvider {

    /** Shared immutable bootstrap provider. */
    public static final BootstrapProvider INSTANCE = new BootstrapProvider();

    private NodeProvider nodeProvider;

    private BootstrapProvider() {
        try {
            ClasspathBasedNodeProvider transformation = new ClasspathBasedNodeProvider(NO_PREPROCESSING, "transformation");
            NodeProvider core = BlueCoreTypeRegistry.INSTANCE.verifiedProvider();
            this.nodeProvider = new SequentialNodeProvider(core, transformation);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }

    @Override
    public List<Node> fetchByBlueId(String blueId) {
        return nodeProvider.fetchByBlueId(blueId);
    }

}
