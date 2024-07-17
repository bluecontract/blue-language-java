package blue.language.samples.serialization;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.TestUtils;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.DirectoryBasedNodeProvider;
import blue.language.utils.NodeToObject;
import blue.language.utils.limits.PathLimits;

import java.io.File;
import java.io.IOException;

import static blue.language.utils.UncheckedObjectMapper.YAML_MAPPER;

public class Sample {

    public static void main(String[] args) throws IOException {
        DirectoryBasedNodeProvider nodeProvider = TestUtils.samplesDirectoryNodeProvider();
        Blue blue = new Blue(nodeProvider);

        String filename = "src/test/java/blue/lang/samples/serialization/alice.blue";
        Node node = YAML_MAPPER.readValue(new File(filename), Node.class);
        String originalBLueId = BlueIdCalculator.calculateBlueId(node);

        Node result = blue.resolve(node, PathLimits.withSinglePath("pets/*"));
        System.out.println(YAML_MAPPER.writeValueAsString(NodeToObject.get(result)));

//        Flattener.flatten(result);
//        Object obj = NodeToObject.get(result, SINGLE_VALUE);
//        System.out.println(YAML_MAPPER.writeValueAsString(obj));

//        Person alice = YAML_MAPPER.convertValue(obj, Person.class);
//
//        alice.setName("Alice 2");
//        String doc = YAML_MAPPER.writeValueAsString(alice);
//        System.out.println(doc);
//
//        Node newNode = YAML_MAPPER.readValue(doc, Node.class);
//        System.out.println(YAML_MAPPER.writeValueAsString(newNode));
//
//        result = blue.resolve(newNode);
//        System.out.println(YAML_MAPPER.writeValueAsString(NodeToObject.get(result)));

//        printBlueIds();
    }

}
