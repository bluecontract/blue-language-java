package blue.lang;

import blue.lang.processor.*;
import blue.lang.model.BlueObject;
import blue.lang.model.Limits;
import blue.lang.utils.Base58Sha256Provider;
import blue.lang.utils.BlueIdCalculator;
import blue.lang.utils.BlueObjectToNode;
import blue.lang.utils.NodeToObject;

import java.util.Arrays;

import static blue.lang.model.Limits.NO_LIMITS;

public class Blue implements NodeProvider {

    private NodeProvider nodeProvider;
    private NodeProcessor nodeProcessor;

    public Blue() {
        this(node -> null);
    }

    public Blue(NodeProvider nodeProvider) {
        this.nodeProvider = nodeProvider;
        this.nodeProcessor = createDefaultNodeProcessor();
    }

    public Blue(NodeProvider nodeProvider, NodeProcessor nodeProcessor) {
        this.nodeProvider = nodeProvider;
        this.nodeProcessor = nodeProcessor;
    }

    public Node resolve(BlueObject object) {
        return resolve(object, NO_LIMITS);
    }

    public Node resolve(BlueObject object, Limits limits) {
        Merger merger = new Merger(nodeProvider, nodeProcessor);
        Node resultNode = new Node();
        Node sourceNode = BlueObjectToNode.convert(object);
        merger.merge(resultNode, sourceNode);
        return resultNode;
    }

    public Object resolveToObject(BlueObject object) {
        return resolveToObject(object, NO_LIMITS);
    }

    public Object resolveToObject(BlueObject object, Limits limits) {
        return NodeToObject.get(resolve(object, limits));
    }

    public String resolveToBlueId(BlueObject object) {
        return resolveToBlueId(object, NO_LIMITS);
    }

    public String resolveToBlueId(BlueObject object, Limits limits) {
        Object obj = resolveToObject(object, limits);
        BlueIdCalculator calculator = new BlueIdCalculator(new Base58Sha256Provider());
        return calculator.calculate(obj);
    }

    @Override
    public Node fetchByBlueId(String blueId) {
        Merger merger = new Merger(this, nodeProcessor);
        Node resultNode = new Node();
        Node sourceNode = nodeProvider.fetchByBlueId(blueId);
        merger.merge(resultNode, sourceNode);
        return resultNode;
    }

    private NodeProcessor createDefaultNodeProcessor() {
        return new SequentialNodeProcessor(
                Arrays.asList(
                        new BlueIdResolver(),
                        new ValuePropagator(),
                        new NamePropagator(),
                        new NameToNullOnTypeMatchTransformer()
                )
        );
    }

}