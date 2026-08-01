package blue.language.merge.processor;

import blue.language.model.wire.BlueLanguageConstants;

import blue.language.*;
import blue.language.merge.MergingProcessor;
import blue.language.merge.NodeResolver;
import blue.language.model.Node;
import blue.language.provider.NodeProvider;
import blue.language.model.NodeWireForm;
import blue.language.provider.Types;

import static blue.language.provider.Types.isSubtype;
import static blue.language.model.wire.BlueLanguageConstants.LIST_MERGE_POLICY_APPEND_ONLY;
import static blue.language.model.wire.BlueLanguageConstants.LIST_MERGE_POLICY_POSITIONAL;

/**
 * Merges List item-type metadata and merge policy while enforcing subtype
 * compatibility for contributed items.
 */
public class ListProcessor implements MergingProcessor {

    /**
     * Creates a stateless list merge processor.
     */
    public ListProcessor() {
    }

    @Override
    public void process(Node target, Node source, NodeProvider nodeProvider, NodeResolver nodeResolver) {
        processMergePolicy(target, source);

        if (source.getItemType() != null && !Types.isListType(source.getType(), nodeProvider)) {
            throw new IllegalArgumentException("Source node with itemType must have a List type");
        }

        Node targetItemType = target.getItemType();
        Node sourceItemType = source.getItemType();

        if (targetItemType == null) {
            if (sourceItemType != null) {
                target.itemType(sourceItemType);
            }
        } else if (sourceItemType != null) {
            boolean isSubtype = isSubtype(sourceItemType, targetItemType, nodeProvider);
            if (!isSubtype) {
                String errorMessage = String.format("The source item type '%s' is not a subtype of the target item type '%s'.",
                        NodeWireForm.get(sourceItemType), NodeWireForm.get(targetItemType));
                throw new IllegalArgumentException(errorMessage);
            }
            target.itemType(sourceItemType);
        }

        if (target.getItemType() != null && source.getItems() != null) {
            for (Node item : source.getItems()) {
                if (item.getType() != null && !isSubtype(item.getType(), target.getItemType(), nodeProvider)) {
                    String errorMessage = String.format("Item of type '%s' is not a subtype of the list's item type '%s'.",
                            NodeWireForm.get(item.getType()), NodeWireForm.get(target.getItemType()));
                    throw new IllegalArgumentException(errorMessage);
                }
            }
        }
    }

    private void processMergePolicy(Node target, Node source) {
        String sourceMergePolicy = source.getMergePolicy();
        String targetMergePolicy = target.getMergePolicy();
        validateMergePolicy(sourceMergePolicy);
        validateMergePolicy(targetMergePolicy);

        if (targetMergePolicy == null) {
            target.mergePolicy(sourceMergePolicy);
        } else if (sourceMergePolicy != null && !targetMergePolicy.equals(sourceMergePolicy)) {
            throw new IllegalArgumentException("Conflicting list mergePolicy values: target is \""
                    + targetMergePolicy + "\" but source is \"" + sourceMergePolicy + "\".");
        }
    }

    private void validateMergePolicy(String mergePolicy) {
        if (mergePolicy != null
                && !LIST_MERGE_POLICY_POSITIONAL.equals(mergePolicy)
                && !LIST_MERGE_POLICY_APPEND_ONLY.equals(mergePolicy)) {
            throw new IllegalArgumentException("mergePolicy must be either \"positional\" or \"append-only\".");
        }
    }

}
