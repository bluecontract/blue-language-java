package blue.language.samples.ipfs;

import blue.language.model.Node;
import blue.language.utils.BlueIdCalculator;
import org.erdtman.jcs.JsonCanonicalizer;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

import static blue.language.utils.BlueIdCalculator.calculateBlueId;
import static blue.language.utils.UncheckedObjectMapper.JSON_MAPPER;

public class PrintAllBlueIdsAndCanonicalJsons {

    public static void print(Map<String, Object> map) throws IOException {
        Map<String, Object> flat = new HashMap<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            if (value instanceof Map) {
                print((Map) value);
                Node node = JSON_MAPPER.convertValue(value, Node.class);
                flat.put(key, Collections.singletonMap("blueId", calculateBlueId(node)));
            } else {
                flat.put(key, value);
            }
        }
        Node node = JSON_MAPPER.convertValue(flat, Node.class);
        System.out.println(calculateBlueId(node));
        System.out.println(new JsonCanonicalizer(JSON_MAPPER.writeValueAsString(flat)).getEncodedString());
        System.out.println("---");
    }

}
