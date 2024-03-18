package blue.lang;

import blue.lang.processor.*;
import blue.lang.model.Limits;
import blue.lang.utils.Base58Sha256Provider;
import blue.lang.utils.BlueIdCalculator;
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

    public Node resolve(Node node) {
        return resolve(node, NO_LIMITS);
    }

    public Node resolve(Node node, Limits limits) {
        Merger merger = new Merger(nodeProvider, nodeProcessor);
        Node resultNode = new Node();
        merger.merge(resultNode, node);
        return resultNode;
    }

    public Object resolveToObject(Node node) {
        return resolveToObject(node, NO_LIMITS);
    }

    public Object resolveToObject(Node node, Limits limits) {
        return NodeToObject.get(resolve(node, limits));
    }

    public String resolveToBlueId(Node node) {
        return resolveToBlueId(node, NO_LIMITS);
    }

    public String resolveToBlueId(Node node, Limits limits) {
        Object obj = resolveToObject(node, limits);
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