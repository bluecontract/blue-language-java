package blue.lang.utils;

import java.util.*;
import java.util.function.Function;

public class Sha256Calculator {

    private static final String SHA256_KEY = "sha256";

    private Function<Object, String> sha256Provider;

    public Sha256Calculator(Function<Object, String> sha256Provider) {
        this.sha256Provider = sha256Provider;
    }

    public Map<String, Object> calculate(Map<String, Object> map) {
        if (map.size() == 1 && map.containsKey(SHA256_KEY))
            return map;

        Map<String, Object> newMap = new HashMap<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Map<?, ?>) {
                newMap.put(entry.getKey(), calculate((Map<String, Object>) value));
            } else if (value instanceof List<?>) {
                newMap.put(entry.getKey(), calculate((List<Object>) value));
            } else {
                newMap.put(entry.getKey(), calculate(value));
            }
        }

        String hash = sha256Provider.apply(newMap);
        return Collections.singletonMap(SHA256_KEY, hash);
    }

    public List<Object> calculate(List<Object> list) {
        if (getSingleShaValue(list) != null)
            return list;

        List<String> hashes = new ArrayList<>();
        for (Object element : list) {
            if (element instanceof Map)
                hashes.add((String) calculate((Map<String, Object>) element).get(SHA256_KEY));
            else if (element instanceof List)
                hashes.add(getSingleShaValue((List<Object>) element));
            else
                hashes.add(getSingleShaValue(Collections.singletonList(calculate(element))));
        }

        while (hashes.size() > 1) {
            List<String> combinedHashes = new ArrayList<>();
            for (int i = 0; i < hashes.size(); i += 2) {
                if (i + 1 < hashes.size()) {
                    String combinedHash = sha256Provider.apply(hashes.get(i) + hashes.get(i + 1));
                    combinedHashes.add(combinedHash);
                } else {
                    combinedHashes.add(hashes.get(i));
                }
            }
            hashes = combinedHashes;
        }

        return Collections.singletonList(Collections.singletonMap(SHA256_KEY, hashes.get(0)));
    }

    public Map<String, Object> calculate(Object object) {
        String hash = sha256Provider.apply(object.toString());
        return Collections.singletonMap(SHA256_KEY, hash);
    }

    private String getSingleShaValue(List<Object> list) {
        if (list.size() == 1 && list.get(0) instanceof Map) {
            Map<?, ?> singleElement = (Map<?, ?>) list.get(0);
            if (singleElement.size() == 1 && singleElement.containsKey(SHA256_KEY)) {
                return (String) singleElement.get(SHA256_KEY);
            }
        }
        return null;
    }
}