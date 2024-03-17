package blue.lang;

import blue.lang.Node;
import blue.lang.model.BlueObject;
import blue.lang.utils.*;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import static blue.lang.utils.UncheckedObjectMapper.YAML_MAPPER;

public class Sample {

    public static void main(String[] args) throws IOException {
        BlueObject object = YAML_MAPPER.readValue(new File("src/test/resources/sample.blue"), BlueObject.class);
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
