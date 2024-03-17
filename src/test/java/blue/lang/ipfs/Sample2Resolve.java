package blue.lang.ipfs;

import blue.lang.Blue;
import blue.lang.model.BlueObject;

import java.io.IOException;

import static blue.lang.utils.UncheckedObjectMapper.YAML_MAPPER;

public class Sample2Resolve {

    public static void main(String[] args) throws IOException {
        String doc = "name: Abc\n" +
                "a: xyz\n" +
                "b: ANJbvdyojDfqp93ZQbo8eLXeyYvvVEr227ELDZpgwHQW";

        Blue blue = new Blue(new IPFSNodeProvider());
        BlueObject object = YAML_MAPPER.readValue(doc, BlueObject.class);
        Object result = blue.resolveToObject(object);
        System.out.println(result);
    }

}