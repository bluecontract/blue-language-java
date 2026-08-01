package blue.language.merge.processor;

import blue.language.provider.NodeProvider;
import blue.language.model.Node;
import blue.language.merge.MergingProcessor;
import blue.language.merge.IncrementalMergingProcessorCapability;
import blue.language.merge.NodeResolver;

import java.util.List;

/**
 * Applies an ordered set of stateless merge stages and forwards completed-value
 * validation to every interested stage.
 */
public class SequentialMergingProcessor implements MergingProcessor, IncrementalMergingProcessorCapability {

    private final List<MergingProcessor> mergingProcessors;

    /**
     * Creates a sequence in the exact supplied order. The list must remain
     * stable for the lifetime of this processor.
     *
     * @param mergingProcessors processors to invoke in deterministic order
     */
    public SequentialMergingProcessor(List<MergingProcessor> mergingProcessors) {
        this.mergingProcessors = mergingProcessors;
    }

    @Override
    public void process(Node target, Node source, NodeProvider nodeProvider, NodeResolver nodeResolver) {
        mergingProcessors.forEach(e -> e.process(target, source, nodeProvider, nodeResolver));
    }

    @Override
    public void postProcess(Node target, Node source, NodeProvider nodeProvider, NodeResolver nodeResolver) {
        mergingProcessors.forEach(e -> e.postProcess(target, source, nodeProvider, nodeResolver));
    }

    @Override
    public boolean hasCompletedValidation(Node node) {
        for (MergingProcessor processor : mergingProcessors) {
            if (processor.hasCompletedValidation(node)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean requiresReferenceMaterialization(Node node) {
        for (MergingProcessor processor : mergingProcessors) {
            if (processor.requiresReferenceMaterialization(node)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void validateCompleted(Node node, boolean semanticallyPresent, String path) {
        for (MergingProcessor processor : mergingProcessors) {
            if (processor.hasCompletedValidation(node)) {
                processor.validateCompleted(node, semanticallyPresent, path);
            }
        }
    }

    /**
     * The built-in processor sequence has path-local behavior that the
     * patch-impact analyzer understands. A subclass or a sequence with any
     * additional/reordered processor is treated as custom and falls back.
     */
    @Override
    public boolean supportsIncrementalValueResolution() {
        return getClass() == SequentialMergingProcessor.class
                && mergingProcessors.size() == 7
                && mergingProcessors.get(0).getClass() == ValuePropagator.class
                && mergingProcessors.get(1).getClass() == TypeAssigner.class
                && mergingProcessors.get(2).getClass() == ListProcessor.class
                && mergingProcessors.get(3).getClass() == DictionaryProcessor.class
                && mergingProcessors.get(4).getClass() == SchemaPropagator.class
                && mergingProcessors.get(5).getClass() == SchemaVerifier.class
                && mergingProcessors.get(6).getClass() == BasicTypesVerifier.class;
    }
}
