package blue.language.samples.ipfs;

import blue.language.Node;
import blue.language.utils.JsonCanonicalizer;

import java.util.Map;
import java.util.stream.Collectors;

import static blue.language.utils.BlueIdCalculator.calculateBlueId;
import static blue.language.utils.UncheckedObjectMapper.JSON_MAPPER;

public class PrintAllBlueIdsAndCanonicalJsons {

    public static void print(Map<String, Object> map) {
        Map<String, Object> flat = map.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> {
                            if (entry.getValue() instanceof Map) {
                                print((Map) entry.getValue());
                                Node node = JSON_MAPPER.convertValue(entry.getValue(), Node.class);
                                return calculateBlueId(node);
                            } else
                                return entry.getValue();
                        }
                ));
        Node node = JSON_MAPPER.convertValue(flat, Node.class);
        System.out.println(calculateBlueId(node));
        System.out.println(JsonCanonicalizer.canonicalize(flat));
        System.out.println("---");
    }

}
