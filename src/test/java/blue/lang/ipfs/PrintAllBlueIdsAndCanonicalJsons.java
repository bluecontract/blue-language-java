package blue.lang.ipfs;

import blue.lang.Blue;
import blue.lang.model.BlueObject;
import blue.lang.utils.JsonCanonicalizer;
import blue.lang.utils.UncheckedObjectMapper;

import java.util.Map;
import java.util.stream.Collectors;

import static blue.lang.utils.UncheckedObjectMapper.JSON_MAPPER;

public class PrintAllBlueIdsAndCanonicalJsons {

    private static final Blue BLUE = new Blue();

    public static void print(Map<String, Object> map) {
        Map<String, Object> flat = map.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> {
                            if (entry.getValue() instanceof Map) {
                                print((Map) entry.getValue());
                                BlueObject object = JSON_MAPPER.convertValue(entry.getValue(), BlueObject.class);
                                return BLUE.resolveToBlueId(object);
                            } else
                                return entry.getValue();
                        }
                ));
        BlueObject object = JSON_MAPPER.convertValue(flat, BlueObject.class);
        System.out.println(BLUE.resolveToBlueId(object));
        System.out.println(JsonCanonicalizer.canonicalize(flat));
        System.out.println("---");
    }

}
