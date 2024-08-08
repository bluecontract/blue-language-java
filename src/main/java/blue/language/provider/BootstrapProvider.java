package blue.language.provider;

import blue.language.NodeProvider;
import blue.language.model.Node;

import java.io.IOException;
import java.util.List;

import static blue.language.provider.ClasspathBasedNodeProvider.NO_PREPROCESSING;

public class BootstrapProvider implements NodeProvider {

    public static final BootstrapProvider INSTANCE = new BootstrapProvider();

    private NodeProvider nodeProvider;

    private BootstrapProvider() {
        try {
            ClasspathBasedNodeProvider transformation = new ClasspathBasedNodeProvider(NO_PREPROCESSING, "transformation");
            //ClasspathBasedNodeProvider core = new ClasspathBasedNodeProvider("core");
            this.nodeProvider = new SequentialNodeProvider(transformation);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }

    @Override
    public List<Node> fetchByBlueId(String blueId) {
        return nodeProvider.fetchByBlueId(blueId);
    }

}