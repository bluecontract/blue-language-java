package blue.lang.ipfs;

import blue.lang.Blue;
import blue.lang.Node;
import blue.lang.utils.JsonCanonicalizer;

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
                                Node node = JSON_MAPPER.convertValue(entry.getValue(), Node.class);
                                return BLUE.resolveToBlueId(node);
                            } else
                                return entry.getValue();
                        }
                ));
        Node node = JSON_MAPPER.convertValue(flat, Node.class);
        System.out.println(BLUE.resolveToBlueId(node));
        System.out.println(JsonCanonicalizer.canonicalize(flat));
        System.out.println("---");
    }

}
