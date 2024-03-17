package blue.lang.ipfs;

import blue.lang.Blue;
import blue.lang.model.BlueObject;

import java.io.File;
import java.io.IOException;
import java.util.Map;

import static blue.lang.utils.UncheckedObjectMapper.YAML_MAPPER;

public class Sample1Print {

    public static void main(String[] args) throws IOException {
        String filename = "src/test/java/blue/lang/ipfs/sample.blue";
        BlueObject object = YAML_MAPPER.readValue(new File(filename), BlueObject.class);
        Blue blue = new Blue();
        Object result = blue.resolveToObject(object);
        PrintAllBlueIdsAndCanonicalJsons.print((Map) result);
    }

}