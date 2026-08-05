package blue.language.registry;

import blue.language.model.Node;
import blue.language.provider.NodeProvider;
import blue.language.provider.SequentialNodeProvider;

import java.io.IOException;
import java.util.List;

/**
 * Singleton provider for the canonical core registry and bundled preprocessing
 * transformation definitions.
 *
 * <p>The bundled transformations are loaded from an explicit, ordered
 * resource manifest. Bootstrap assembly therefore never scans the ambient
 * classpath and does not depend on optional mapping/discovery libraries.</p>
 */
public class BootstrapProvider implements NodeProvider {

    /** Shared immutable bootstrap provider. */
    public static final BootstrapProvider INSTANCE = new BootstrapProvider();

    private NodeProvider nodeProvider;

    private BootstrapProvider() {
        try {
            NodeProvider transformation =
                    new BundledTransformationProvider();
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
