package blue.language.processor;

import blue.language.MergingProcessor;
import blue.language.NodeProvider;
import blue.language.NodeResolver;
import blue.language.model.Node;
import blue.language.utils.Types;

import static blue.language.utils.Types.findBasicTypeName;

public class BasicTypesVerifier implements MergingProcessor {
    @Override
    public void process(Node target, Node source, NodeProvider nodeProvider, NodeResolver nodeResolver) {
        // do nothing
    }

    @Override
    public void postProcess(Node target, Node source, NodeProvider nodeProvider, NodeResolver nodeResolver) {
        if (target.getType() != null && Types.isSubtypeOfBasicType(target.getType(), nodeProvider)) {
            if ((target.getItems() != null && !target.getItems().isEmpty()) ||
                (target.getProperties() != null && !target.getProperties().isEmpty())) {
                String basicTypeName = findBasicTypeName(target.getType(), nodeProvider);
                throw new IllegalArgumentException("Node of type \"" + target.getType().getName() +
                                                   "\" (which extends basic type \"" + basicTypeName +
                                                   "\") must not have items or properties.");
            }
        }
    }
}
