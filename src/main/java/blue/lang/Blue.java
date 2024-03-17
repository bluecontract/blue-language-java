package blue.lang;

import blue.lang.processor.*;
import blue.lang.model.BlueObject;
import blue.lang.model.Limits;
import blue.lang.utils.Base58Sha256Provider;
import blue.lang.utils.BlueIdCalculator;
import blue.lang.utils.BlueObjectToNode;
import blue.lang.utils.NodeToObject;

import java.util.Arrays;

public class Blue implements Resolver {

    private NodeProvider nodeProvider;
    private NodeProcessor nodeProcessor;

    public Blue(NodeProvider nodeProvider) {
        this.nodeProvider = nodeProvider;
        this.nodeProcessor = createDefaultNodeProcessor();
    }

    public Blue(NodeProvider nodeProvider, NodeProcessor nodeProcessor) {
        this.nodeProvider = nodeProvider;
        this.nodeProcessor = nodeProcessor;
    }

    @Override
    public Node resolve(BlueObject object, Limits limits) {
        Merger merger = new Merger(nodeProvider, nodeProcessor, this);
        Node resultNode = new Node();
        Node sourceNode = BlueObjectToNode.convert(object);
        merger.merge(resultNode, sourceNode);
        return resultNode;
    }

    public Object resolveToObject(BlueObject object, Limits limits) {
        return NodeToObject.get(resolve(object, limits));
    }

    public String resolveToBlueId(BlueObject object, Limits limits) {
        Object obj = resolveToObject(object, limits);
        BlueIdCalculator calculator = new BlueIdCalculator(new Base58Sha256Provider());
        return calculator.calculate(obj);
    }

    private NodeProcessor createDefaultNodeProcessor() {
        SequentialNodeProcessor sequentialNodeProcessor = new SequentialNodeProcessor(
                Arrays.asList(
                        new BlueIdResolver(),
                        new ValuePropagator(),
                        new NamePropagator(),
                        new NameToNullOnTypeMatchTransformer()
                )
        );
        return sequentialNodeProcessor;
    }

}