package blue.lang;

import blue.lang.graph.Merger;
import blue.lang.graph.Node;
import blue.lang.graph.NodeManager;
import blue.lang.graph.NodeProcessor;
import blue.lang.graph.processor.*;
import blue.lang.model.BlueObject;
import blue.lang.utils.*;

import java.util.ArrayList;
import java.util.Arrays;

import static blue.lang.utils.UncheckedObjectMapper.YAML_MAPPER;

public class Sample2 {

    public static void main(String[] args) {
        String doc = "abc:\n" +
                "  blueId: FqJGnvANURarXp5k4Y6D4b8f9GR6ZnwDr5n3dGTK1111\n" +
                "def:\n" +
                "  value: abc\n" +
                "  name: xyz";
        BlueObject object = YAML_MAPPER.readValue(doc, BlueObject.class);
        System.out.println(YAML_MAPPER.writeValueAsString(object));

        NodeProcessor nodeProcessor = new SequentialNodeProcessor(
                Arrays.asList(
                        new BlueIdResolver(),
                        new ValuePropagator(),
                        new NamePropagator(),
                        new NameToNullOnTypeMatchTransformer()
                )
        );
        NodeManager manager = new NodeManager(new ArrayList<>(), nodeProcessor);

        Merger merger = new Merger(manager);
        Node resultNode = new Node();
        Node sourceNode = BlueObjectToNode.convert(object);
        merger.merge(resultNode, sourceNode);

        Object obj = NodeToObject.get(resultNode);
        System.out.println(YAML_MAPPER.writeValueAsString(obj));
        System.out.println(JsonCanonicalizer.canonicalize(obj));

        BlueIdCalculator calculator = new BlueIdCalculator(new Base58Sha256Provider());
        System.out.println(calculator.calculate(obj));
    }

}
