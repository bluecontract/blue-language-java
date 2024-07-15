package blue.language.samples.ipfs;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.utils.NodeToObject;

import java.io.File;
import java.io.IOException;
import java.util.Map;

import static blue.language.utils.UncheckedObjectMapper.YAML_MAPPER;

public class Sample1Print {

    public static void main(String[] args) throws IOException {
        String filename = "src/test/java/blue/language/samples/ipfs/sample.blue";
        Node node = YAML_MAPPER.readValue(new File(filename), Node.class);
        Blue blue = new Blue();
        Object result = NodeToObject.get(blue.resolve(node));
        PrintAllBlueIdsAndCanonicalJsons.print((Map) result);
    }

}