package blue.language.conformance.contracts;

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
 * Mutable, path-addressed projection of one fixture execution.
 *
 * <p>Missing values are represented explicitly and are never conflated with
 * a present {@code null} value. Registration order is preserved for both
 * observables and variants. Instances are execution-local and not
 * thread-safe.</p>
 */
final class ContractsConformanceProjection {

    private final Map<String, Object> values = new LinkedHashMap<>();
    private final Map<String, ContractsConformanceProjection> variants = new LinkedHashMap<>();

    /**
     * Creates an empty execution-local projection.
     */
    public ContractsConformanceProjection() {
    }

    /**
     * Stores one observable value after converting Nodes, JSON values,
     * iterables, and arrays to the projection's map/list/scalar vocabulary.
     *
     * @param path declared projection path
     * @param value value to normalize; {@code null} remains explicitly present
     * @return this projection
     */
    public ContractsConformanceProjection put(String path, Object value) {
        if (path == null || path.trim().isEmpty()) {
            throw new IllegalArgumentException("Projection path is required");
        }
        values.put(path, normalize(value));
        return this;
    }

    /**
     * Registers a uniquely named execution variant.
     *
     * @param name nonblank unique variant name
     * @param projection variant projection retained by reference
     * @return this projection
     * @throws IllegalArgumentException if the name is blank, the projection is
     *         null, or the name was already registered
     */
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

    /**
     * Resolves a stored path, a nested map/list selection, a variant-prefixed
     * path, or a braced field selection.
     *
     * @param path exact projection path or supported nested selection
     * @return explicit presence, preserving the distinction between an absent
     *         path and a present {@code null}
     */
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

    /**
     * Projects the same path from every variant, or only the named selector.
     *
     * @param path projection path resolved within each selected variant
     * @param selector variant name, {@code "all"}, or blank for all variants
     * @return immutable variant-to-presence map in registration order
     * @throws IllegalStateException when no variants were registered
     * @throws IllegalArgumentException when a named selector is unknown
     */
    public Map<String, Presence> projectAcrossVariants(String path, String selector) {
        if (variants.isEmpty()) {
            throw new IllegalStateException(
                    "Projection has no variants for sameAcrossVariants assertion: " + path);
        }
        Map<String, Presence> selected = new LinkedHashMap<>();
        if (selector != null
                && !selector.isEmpty()
                && !ContractsFixtureConstants.VariantSelector.ALL.equals(
                        selector)) {
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

    /**
     * Returns the directly stored observables.
     *
     * <p>The map is unmodifiable, while normalized container values retain
     * their execution-owned map/list representation.</p>
     *
     * @return unmodifiable values view in registration order
     */
    public Map<String, Object> values() {
        return Collections.unmodifiableMap(values);
    }

    /**
     * Returns registered variant projections.
     *
     * @return unmodifiable variant view in registration order
     */
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

    /**
     * Presence-aware projection result that can represent a present
     * {@code null} without conflating it with absence.
     */
    public static final class Presence {
        private static final Presence ABSENT = new Presence(false, null);

        private final boolean present;
        private final Object value;

        private Presence(boolean present, Object value) {
            this.present = present;
            this.value = value;
        }

        /**
         * Creates a present result whose value is normalized for comparison.
         *
         * @param value present value; {@code null} remains present
         * @return a new presence result
         */
        public static Presence present(Object value) {
            return new Presence(true, normalize(value));
        }

        /**
         * Returns the shared absent result.
         *
         * @return immutable absent result
         */
        public static Presence absent() {
            return ABSENT;
        }

        /**
         * Reports whether the requested projection path was present.
         *
         * @return {@code true} for a present value, including present null
         */
        public boolean isPresent() {
            return present;
        }

        /**
         * Returns the present value.
         *
         * @return normalized present value, possibly {@code null}
         * @throws IllegalStateException when this result represents absence
         */
        public Object getValue() {
            if (!present) {
                throw new IllegalStateException("Projection is absent");
            }
            return value;
        }
    }
}
