package blue.lang;

import blue.lang.graph.Node;
import blue.lang.model.BlueObject;
import blue.lang.utils.Base58Sha256Provider;
import blue.lang.utils.BlueIdCalculator;
import blue.lang.utils.BlueObjectToNode;
import blue.lang.utils.NodeToObject;

import java.util.function.Function;

import static blue.lang.utils.UncheckedObjectMapper.YAML_MAPPER;

public class Sample {

    public static void main(String[] args) {
        String doc = "abc:\n" +
                "  blueId: FqJGnvANURarXp5k4Y6D4b8f9GR6ZnwDr5n3dGTKsZDS\n" +
                "def:\n" +
                "  value: abc\n" +
                "  name: xyz";
        BlueObject object = YAML_MAPPER.readValue(doc, BlueObject.class);
        System.out.println(YAML_MAPPER.writeValueAsString(object));

        Node node = BlueObjectToNode.convert(object);
        Object obj = NodeToObject.get(node);
        System.out.println(YAML_MAPPER.writeValueAsString(obj));

        BlueIdCalculator calculator = new BlueIdCalculator(fakeHashValueProvider());
        System.out.println(calculator.calculate(obj));
    }

    private static Function<Object, String> fakeHashValueProvider() {
        return obj -> "hash(" + obj + ")";
    }

}
