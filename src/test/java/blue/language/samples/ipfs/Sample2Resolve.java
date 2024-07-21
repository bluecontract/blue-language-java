package blue.language.samples.ipfs;

import blue.language.*;
import blue.language.model.Node;
import blue.language.provider.ipfs.IPFSNodeProvider;
import blue.language.utils.NodeToObject;

import java.io.IOException;

import static blue.language.utils.UncheckedObjectMapper.YAML_MAPPER;

public class Sample2Resolve {

    public static void main(String[] args) throws IOException {
        String doc = "name: Abc\n" +
                "a: xyz\n" +
                "b: ANJbvdyojDfqp93ZQbo8eLXeyYvvVEr227ELDZpgwHQW";

        Blue blue = new Blue(new IPFSNodeProvider());
        Node node = YAML_MAPPER.readValue(doc, Node.class);
        Object result = NodeToObject.get(blue.resolve(node));
        System.out.println(result);
    }

}