package blue.lang.samples.serialization;

import blue.lang.Blue;
import blue.lang.Node;
import blue.lang.TestUtils;
import blue.lang.model.limits.Limits;
import blue.lang.utils.BlueIdCalculator;
import blue.lang.utils.DirectoryBasedNodeProvider;
import blue.lang.utils.Flattener;
import blue.lang.utils.NodeToObject;

import java.io.File;
import java.io.IOException;

import static blue.lang.utils.NodeToObject.Strategy.SINGLE_VALUE;
import static blue.lang.utils.UncheckedObjectMapper.YAML_MAPPER;

public class Sample {

    public static void main(String[] args) throws IOException {
        DirectoryBasedNodeProvider nodeProvider = TestUtils.samplesDirectoryNodeProvider();
        Blue blue = new Blue(nodeProvider);

        String filename = "src/test/java/blue/lang/samples/serialization/alice.blue";
        Node node = YAML_MAPPER.readValue(new File(filename), Node.class);
        String originalBLueId = BlueIdCalculator.calculateBlueId(node);

        Node result = blue.resolve(node);
        System.out.println(YAML_MAPPER.writeValueAsString(NodeToObject.get(result)));

        Flattener.flatten(result);
        Object obj = NodeToObject.get(result, SINGLE_VALUE);
        System.out.println(YAML_MAPPER.writeValueAsString(obj));

        Person alice = YAML_MAPPER.convertValue(obj, Person.class);

        alice.setName("Alice 2");
        String doc = YAML_MAPPER.writeValueAsString(alice);
        System.out.println(doc);

        Node newNode = YAML_MAPPER.readValue(doc, Node.class);
        System.out.println(YAML_MAPPER.writeValueAsString(newNode));

//        result = blue.resolve(node);
//        result = blue.resolve(node, Limits.path("pets/0/age/*"));
//        result = blue.resolve(node, Limits.depth(3));
        result = blue.resolve(node, Limits.depth(3)
                .and(Limits.path("pets/0/age/*")));
        System.out.println(YAML_MAPPER.writeValueAsString(NodeToObject.get(result)));

//        printBlueIds();
    }

}
