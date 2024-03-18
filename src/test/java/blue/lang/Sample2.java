package blue.lang;

import java.io.File;
import java.io.IOException;
import java.util.function.Function;

import static blue.lang.utils.UncheckedObjectMapper.YAML_MAPPER;

public class Sample2 {

    public static void main(String[] args) throws IOException {
        Node object = YAML_MAPPER.readValue(new File("src/test/resources/sample.blue"), Node.class);
        Blue blue = new Blue(TestUtils.samplesDirectoryNodeProvider());
        System.out.println(blue.resolveToObject(object, null));

    }

    private static Function<Object, String> fakeHashValueProvider() {
        return obj -> "hash(" + obj + ")";
    }

}
