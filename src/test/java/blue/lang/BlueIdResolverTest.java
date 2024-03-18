package blue.lang;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static blue.lang.TestUtils.samplesDirectoryNodeProvider;
import static blue.lang.utils.UncheckedObjectMapper.YAML_MAPPER;

public class BlueIdResolverTest {

    @Test
    public void testSimpleNodesMerge() throws Exception {

        String doc = "name: Abc\n" +
                "x: 1";

        Blue blue = new Blue(samplesDirectoryNodeProvider());
        Node node = YAML_MAPPER.readValue(doc, Node.class);
        Object result = blue.resolve(node);

        System.out.println(result);
    }

    public static void main(String[] args) throws IOException {
        String doc = "name: Abc\n" +
                "x: Ao6wor1Lzv6M2x1M5CF9Ki4XNMQgX98JNJqRKySnhLhB";

        Blue blue = new Blue(samplesDirectoryNodeProvider());
        Node node = YAML_MAPPER.readValue(doc, Node.class);
        Object result = blue.resolveToObject(node);
        System.out.println(result);

    }

}