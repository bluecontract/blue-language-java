package blue.lang;

import blue.lang.utils.BlueIdCalculator;
import blue.lang.utils.NodeToObject;

import java.io.File;
import java.io.IOException;
import java.util.function.Function;

import static blue.lang.utils.UncheckedObjectMapper.YAML_MAPPER;

public class Sample {

    public static void main(String[] args) throws IOException {
        Node node = YAML_MAPPER.readValue(new File("src/test/resources/sample.blue"), Node.class);
        Object obj = NodeToObject.get(node);
        System.out.println(YAML_MAPPER.writeValueAsString(obj));

        BlueIdCalculator calculator = new BlueIdCalculator(fakeHashValueProvider());
        System.out.println(calculator.calculate(obj));
    }

    private static Function<Object, String> fakeHashValueProvider() {
        return obj -> "hash(" + obj + ")";
    }

}
