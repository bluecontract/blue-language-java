package blue.lang;

import blue.lang.NodeProcessor;
import blue.lang.processor.*;
import blue.lang.model.BlueObject;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.function.Function;

import static blue.lang.utils.UncheckedObjectMapper.YAML_MAPPER;

public class Sample2 {

    public static void main(String[] args) throws IOException {
        BlueObject object = YAML_MAPPER.readValue(new File("src/test/resources/sample.blue"), BlueObject.class);
        System.out.println(YAML_MAPPER.writeValueAsString(object));

        NodeProcessor nodeProcessor = new SequentialNodeProcessor(
                Arrays.asList(
                        new BlueIdResolver(),
                        new ValuePropagator(),
                        new NamePropagator(),
                        new NameToNullOnTypeMatchTransformer()
                )
        );

        Blue blue = new Blue(null, nodeProcessor);
        System.out.println(blue.resolveToBlueId(object, null));

    }

    private static Function<Object, String> fakeHashValueProvider() {
        return obj -> "hash(" + obj + ")";
    }

}
