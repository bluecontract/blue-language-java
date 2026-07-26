package blue.language.processor.conformance;

import blue.language.model.Node;
import blue.language.utils.NodeToMapListOrValue;
import blue.language.utils.UncheckedObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Closed, path-addressed projection of one fixture execution. Missing values are
 * represented explicitly and are never conflated with a present null value.
 */
public final class ContractsConformanceProjection {

    private final Map<String, Object> values = new LinkedHashMap<>();
    private final Map<String, ContractsConformanceProjection> variants = new LinkedHashMap<>();

    public ContractsConformanceProjection put(String path, Object value) {
        if (path == null || path.trim().isEmpty()) {
            throw new IllegalArgumentException("Projection path is required");
        }
        values.put(path, normalize(value));
        return this;
    }

    public ContractsConformanceProjection putVariant(String name,
                                                     ContractsConformanceProjection projection) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Variant name is required");
        }
        if (projection == null) {
            throw new IllegalArgumentException("Variant projection is required");
        }
        if (variants.put(name, projection) != null) {
            throw new IllegalArgumentException("Duplicate projection variant: " + name);
        }
        return this;
    }

    public Presence project(String path) {
        if (path == null || path.isEmpty()) {
            return Presence.absent();
        }
        if (values.containsKey(path)) {
            return Presence.present(values.get(path));
        }
        if (path.startsWith("variants.")) {
            int nameEnd = path.indexOf('.', "variants.".length());
            if (nameEnd < 0) {
                return Presence.absent();
            }
            ContractsConformanceProjection variant =
                    variants.get(path.substring("variants.".length(), nameEnd));
            return variant == null
                    ? Presence.absent()
                    : variant.project(path.substring(nameEnd + 1));
        }
        if (path.contains(".{") && path.endsWith("}")) {
            return bracedProjection(path);
        }
        String rootPath = longestStoredPrefix(path);
        if (rootPath == null) {
            return Presence.absent();
        }
        Object current = values.get(rootPath);
        String[] segments = path.substring(rootPath.length() + 1).split("\\.");
        for (String segment : segments) {
            Presence next = select(current, segment);
            if (!next.isPresent()) {
                return next;
            }
            current = next.getValue();
        }
        return Presence.present(current);
    }

    private String longestStoredPrefix(String path) {
        String match = null;
        for (String candidate : values.keySet()) {
            if (path.startsWith(candidate + ".")
                    && (match == null || candidate.length() > match.length())) {
                match = candidate;
            }
        }
        return match;
    }

    public Map<String, Presence> projectAcrossVariants(String path, String selector) {
        if (variants.isEmpty()) {
            throw new IllegalStateException(
                    "Projection has no variants for sameAcrossVariants assertion: " + path);
        }
        Map<String, Presence> selected = new LinkedHashMap<>();
        if (selector != null && !selector.isEmpty() && !"all".equals(selector)) {
            ContractsConformanceProjection variant = variants.get(selector);
            if (variant == null) {
                throw new IllegalArgumentException("Unknown projection variant: " + selector);
            }
            selected.put(selector, variant.project(path));
            return Collections.unmodifiableMap(selected);
        }
        for (Map.Entry<String, ContractsConformanceProjection> entry : variants.entrySet()) {
            selected.put(entry.getKey(), entry.getValue().project(path));
        }
        return Collections.unmodifiableMap(selected);
    }

    public Map<String, Object> values() {
        return Collections.unmodifiableMap(values);
    }

    public Map<String, ContractsConformanceProjection> variants() {
        return Collections.unmodifiableMap(variants);
    }

    private Presence bracedProjection(String path) {
        int marker = path.indexOf(".{");
        String base = path.substring(0, marker);
        Presence baseValue = project(base);
        if (!baseValue.isPresent() || !(baseValue.getValue() instanceof Map)) {
            return Presence.absent();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> object = (Map<String, Object>) baseValue.getValue();
        String body = path.substring(marker + 2, path.length() - 1);
        Map<String, Object> selected = new LinkedHashMap<>();
        for (String field : body.split(",")) {
            if (!object.containsKey(field)) {
                return Presence.absent();
            }
            selected.put(field, object.get(field));
        }
        return Presence.present(selected);
    }

    @SuppressWarnings("unchecked")
    private static Presence select(Object current, String segment) {
        if (current instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) current;
            return map.containsKey(segment)
                    ? Presence.present(map.get(segment))
                    : Presence.absent();
        }
        if (current instanceof List) {
            int index;
            try {
                index = Integer.parseInt(segment);
            } catch (NumberFormatException ex) {
                return Presence.absent();
            }
            List<Object> list = (List<Object>) current;
            return index >= 0 && index < list.size()
                    ? Presence.present(list.get(index))
                    : Presence.absent();
        }
        return Presence.absent();
    }

    @SuppressWarnings("unchecked")
    static Object normalize(Object value) {
        if (value instanceof Node) {
            return normalize(NodeToMapListOrValue.get((Node) value));
        }
        if (value instanceof JsonNode) {
            return normalize(UncheckedObjectMapper.JSON_MAPPER.convertValue(
                    value, new TypeReference<Object>() {
                    }));
        }
        if (value instanceof Map) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                normalized.put(String.valueOf(entry.getKey()), normalize(entry.getValue()));
            }
            return normalized;
        }
        if (value instanceof Iterable) {
            List<Object> normalized = new ArrayList<>();
            for (Object item : (Iterable<?>) value) {
                normalized.add(normalize(item));
            }
            return normalized;
        }
        if (value != null && value.getClass().isArray()) {
            List<Object> normalized = new ArrayList<>();
            Object[] items = (Object[]) value;
            for (Object item : items) {
                normalized.add(normalize(item));
            }
            return normalized;
        }
        return value;
    }

    public static final class Presence {
        private static final Presence ABSENT = new Presence(false, null);

        private final boolean present;
        private final Object value;

        private Presence(boolean present, Object value) {
            this.present = present;
            this.value = value;
        }

        public static Presence present(Object value) {
            return new Presence(true, normalize(value));
        }

        public static Presence absent() {
            return ABSENT;
        }

        public boolean isPresent() {
            return present;
        }

        public Object getValue() {
            if (!present) {
                throw new IllegalStateException("Projection is absent");
            }
            return value;
        }
    }
}
