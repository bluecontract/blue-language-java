package blue.lang.samples.ipfs;

import blue.lang.*;
import blue.lang.utils.NodeToObject;

import java.io.IOException;

import static blue.lang.utils.UncheckedObjectMapper.YAML_MAPPER;

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