package blue.language.merge.processor;

import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.provider.NodeProvider;
import blue.language.model.Node;
import blue.language.merge.MergingProcessor;
import blue.language.merge.NodeResolver;

import java.util.List;

/** Rejects a source node that attempts to carry both list and scalar payloads. */
public class ExclusiveItemsOrValueChecker implements MergingProcessor {

    /** Creates a stateless payload-exclusivity checker. */
    public ExclusiveItemsOrValueChecker() {
    }

    @Override
    public void process(
            Node target,
            Node source,
            NodeProvider nodeProvider,
            NodeResolver nodeResolver,
            CanonicalTypeIdentityLookup typeIdentities) {
        List<Node> items = source.getItems();
        Object value = source.getValue();
        if (items != null && value != null)
            throw new IllegalArgumentException("Node cannot have both 'items' and 'value' set at the same time.");
    }
}
