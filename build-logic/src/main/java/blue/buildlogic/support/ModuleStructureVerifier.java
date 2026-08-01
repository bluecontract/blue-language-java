package blue.buildlogic.support;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.gradle.api.GradleException;

/** Verifies split packages, dependency declarations, and cycles across module inventories. */
public final class ModuleStructureVerifier {

    public static final String SCHEMA = "blue-java-module-structure/1.0";

    private static final String EDGE_SEPARATOR = "->";
    private static final String WILDCARD_SUFFIX = ".*";
    private static final int EDGE_HASH_MULTIPLIER = 31;
    private static final int MINIMUM_CYCLE_SIZE = 2;
    private static final String KEY_ALLOWED_EDGES = "allowedEdges";
    private static final String KEY_CYCLES = "cycles";
    private static final String KEY_MODULE_COUNT = "moduleCount";
    private static final String KEY_MODULES = "modules";
    private static final String KEY_OBSERVED_EDGES = "observedEdges";
    private static final String KEY_PACKAGE = "package";
    private static final String KEY_SCHEMA = "schema";
    private static final String KEY_SOURCE = "source";
    private static final String KEY_SPLIT_PACKAGES = "splitPackages";
    private static final String KEY_TARGET = "target";
    private static final String KEY_UNDECLARED_EDGES = "undeclaredEdges";
    private static final String KEY_VALID = "valid";

    private ModuleStructureVerifier() {}

    /** Analyzes inventories and optionally rejects observed edges absent from {@code allowedEdges}. */
    public static Result analyze(
            Collection<JavaModuleInventory.Inventory> inventories,
            Collection<String> allowedEdges,
            boolean enforceAllowedEdges) {
        Map<String, MutableModule> modules = merge(inventories);
        Map<String, Set<String>> packageOwners = packageOwners(modules);
        Map<String, Set<String>> classOwners = classOwners(modules);
        Set<Edge> observedEdges = observedEdges(modules, packageOwners, classOwners);
        Set<Edge> allowed = parseEdges(allowedEdges);
        Set<Edge> undeclared = new TreeSet<>();
        if (enforceAllowedEdges) {
            undeclared.addAll(observedEdges);
            undeclared.removeAll(allowed);
        }
        Map<String, Set<String>> splitPackages = splitPackages(packageOwners);
        List<Set<String>> cycles = cycles(modules.keySet(), observedEdges);
        return new Result(
                modules.keySet(), observedEdges, allowed, undeclared, splitPackages, cycles);
    }

    private static Map<String, MutableModule> merge(
            Collection<JavaModuleInventory.Inventory> inventories) {
        Map<String, MutableModule> modules = new TreeMap<>();
        for (JavaModuleInventory.Inventory inventory : inventories) {
            MutableModule module = modules.computeIfAbsent(
                    inventory.getModule(), MutableModule::new);
            module.packages.addAll(inventory.getPackages());
            module.classes.addAll(inventory.getClasses());
            module.references.addAll(inventory.getReferences());
        }
        return modules;
    }

    private static Map<String, Set<String>> packageOwners(Map<String, MutableModule> modules) {
        Map<String, Set<String>> owners = new TreeMap<>();
        modules.values().forEach(module -> module.packages.forEach(packageName -> owners
                .computeIfAbsent(packageName, ignored -> new TreeSet<>())
                .add(module.name)));
        return owners;
    }

    private static Map<String, Set<String>> classOwners(Map<String, MutableModule> modules) {
        Map<String, Set<String>> owners = new TreeMap<>();
        modules.values().forEach(module -> module.classes.forEach(className -> owners
                .computeIfAbsent(className, ignored -> new TreeSet<>())
                .add(module.name)));
        return owners;
    }

    private static Set<Edge> observedEdges(
            Map<String, MutableModule> modules,
            Map<String, Set<String>> packageOwners,
            Map<String, Set<String>> classOwners) {
        Set<Edge> edges = new TreeSet<>();
        for (MutableModule module : modules.values()) {
            for (String reference : module.references) {
                for (String target : owners(reference, packageOwners, classOwners)) {
                    if (!module.name.equals(target)) {
                        edges.add(new Edge(module.name, target));
                    }
                }
            }
        }
        return edges;
    }

    private static Set<String> owners(
            String reference,
            Map<String, Set<String>> packageOwners,
            Map<String, Set<String>> classOwners) {
        String candidate = reference;
        if (candidate.endsWith(WILDCARD_SUFFIX)) {
            return packageOwners.getOrDefault(
                    candidate.substring(0, candidate.length() - WILDCARD_SUFFIX.length()),
                    Collections.emptySet());
        }
        while (!candidate.isEmpty()) {
            Set<String> owners = classOwners.get(candidate);
            if (owners != null) {
                return owners;
            }
            int separator = candidate.lastIndexOf('.');
            candidate = separator < 0 ? "" : candidate.substring(0, separator);
        }
        String bestPackage = null;
        for (String packageName : packageOwners.keySet()) {
            if ((reference.equals(packageName) || reference.startsWith(packageName + "."))
                    && (bestPackage == null || packageName.length() > bestPackage.length())) {
                bestPackage = packageName;
            }
        }
        return bestPackage == null
                ? Collections.emptySet()
                : packageOwners.get(bestPackage);
    }

    private static Set<Edge> parseEdges(Collection<String> edgeValues) {
        Set<Edge> edges = new TreeSet<>();
        for (String value : edgeValues) {
            String normalized = value == null ? "" : value.trim();
            int separator = normalized.indexOf(EDGE_SEPARATOR);
            if (separator <= 0
                    || separator != normalized.lastIndexOf(EDGE_SEPARATOR)
                    || separator + EDGE_SEPARATOR.length() >= normalized.length()) {
                throw new GradleException(
                        "Allowed module edge must use the form 'source->target': " + value);
            }
            edges.add(new Edge(
                    normalized.substring(0, separator).trim(),
                    normalized.substring(separator + EDGE_SEPARATOR.length()).trim()));
        }
        return edges;
    }

    private static Map<String, Set<String>> splitPackages(
            Map<String, Set<String>> packageOwners) {
        Map<String, Set<String>> splits = new TreeMap<>();
        packageOwners.forEach((packageName, owners) -> {
            if (owners.size() > 1) {
                splits.put(packageName, new TreeSet<>(owners));
            }
        });
        return splits;
    }

    private static List<Set<String>> cycles(Collection<String> modules, Collection<Edge> edges) {
        Map<String, Set<String>> adjacency = new TreeMap<>();
        modules.forEach(module -> adjacency.put(module, new TreeSet<>()));
        edges.forEach(edge -> adjacency.get(edge.source).add(edge.target));

        Set<String> assigned = new TreeSet<>();
        List<Set<String>> cycles = new ArrayList<>();
        for (String module : new TreeSet<>(modules)) {
            if (assigned.contains(module)) {
                continue;
            }
            Set<String> component = new TreeSet<>();
            Set<String> fromModule = reachable(module, adjacency);
            for (String candidate : fromModule) {
                if (reachable(candidate, adjacency).contains(module)) {
                    component.add(candidate);
                }
            }
            assigned.addAll(component);
            if (component.size() >= MINIMUM_CYCLE_SIZE) {
                cycles.add(component);
            }
        }
        cycles.sort((left, right) -> left.iterator().next().compareTo(right.iterator().next()));
        return cycles;
    }

    private static Set<String> reachable(String start, Map<String, Set<String>> adjacency) {
        Set<String> visited = new TreeSet<>();
        Deque<String> pending = new ArrayDeque<>();
        pending.push(start);
        while (!pending.isEmpty()) {
            String current = pending.pop();
            if (!visited.add(current)) {
                continue;
            }
            List<String> targets = new ArrayList<>(
                    adjacency.getOrDefault(current, Collections.emptySet()));
            Collections.reverse(targets);
            targets.forEach(pending::push);
        }
        return visited;
    }

    /** Immutable deterministic module structure result and report. */
    public static final class Result {

        private final Set<String> modules;
        private final Set<Edge> observedEdges;
        private final Set<Edge> allowedEdges;
        private final Set<Edge> undeclaredEdges;
        private final Map<String, Set<String>> splitPackages;
        private final List<Set<String>> cycles;

        private Result(
                Collection<String> modules,
                Collection<Edge> observedEdges,
                Collection<Edge> allowedEdges,
                Collection<Edge> undeclaredEdges,
                Map<String, Set<String>> splitPackages,
                List<Set<String>> cycles) {
            this.modules = immutableSet(modules);
            this.observedEdges = immutableSet(observedEdges);
            this.allowedEdges = immutableSet(allowedEdges);
            this.undeclaredEdges = immutableSet(undeclaredEdges);
            this.splitPackages = immutableMap(splitPackages);
            this.cycles = immutableList(cycles);
        }

        public boolean isValid() {
            return splitPackages.isEmpty() && cycles.isEmpty() && undeclaredEdges.isEmpty();
        }

        public int getSplitPackageCount() {
            return splitPackages.size();
        }

        public int getCycleCount() {
            return cycles.size();
        }

        public int getUndeclaredEdgeCount() {
            return undeclaredEdges.size();
        }

        public String toJson() {
            Map<String, Object> report = new TreeMap<>();
            report.put(KEY_ALLOWED_EDGES, edgeRecords(allowedEdges));
            report.put(KEY_CYCLES, nestedLists(cycles));
            report.put(KEY_MODULE_COUNT, modules.size());
            report.put(KEY_MODULES, new ArrayList<>(modules));
            report.put(KEY_OBSERVED_EDGES, edgeRecords(observedEdges));
            report.put(KEY_SCHEMA, SCHEMA);
            report.put(KEY_SPLIT_PACKAGES, splitPackageRecords(splitPackages));
            report.put(KEY_UNDECLARED_EDGES, edgeRecords(undeclaredEdges));
            report.put(KEY_VALID, isValid());
            return DeterministicJson.write(report);
        }

        private static List<Map<String, Object>> edgeRecords(Collection<Edge> edges) {
            List<Map<String, Object>> records = new ArrayList<>();
            for (Edge edge : edges) {
                Map<String, Object> record = new TreeMap<>();
                record.put(KEY_SOURCE, edge.source);
                record.put(KEY_TARGET, edge.target);
                records.add(record);
            }
            return records;
        }

        private static List<Map<String, Object>> splitPackageRecords(
                Map<String, Set<String>> splitPackages) {
            List<Map<String, Object>> records = new ArrayList<>();
            splitPackages.forEach((packageName, owners) -> {
                Map<String, Object> record = new TreeMap<>();
                record.put(KEY_MODULES, new ArrayList<>(owners));
                record.put(KEY_PACKAGE, packageName);
                records.add(record);
            });
            return records;
        }

        private static List<List<String>> nestedLists(List<Set<String>> values) {
            List<List<String>> lists = new ArrayList<>();
            values.forEach(value -> lists.add(new ArrayList<>(value)));
            return lists;
        }
    }

    private static final class MutableModule {

        private final String name;
        private final Set<String> packages = new TreeSet<>();
        private final Set<String> classes = new TreeSet<>();
        private final Set<String> references = new TreeSet<>();

        private MutableModule(String name) {
            this.name = name;
        }
    }

    private static final class Edge implements Comparable<Edge> {

        private final String source;
        private final String target;

        private Edge(String source, String target) {
            if (source.isBlank() || target.isBlank()) {
                throw new GradleException("Module edge source and target must be non-empty");
            }
            this.source = source;
            this.target = target;
        }

        @Override
        public int compareTo(Edge other) {
            int sourceOrder = source.compareTo(other.source);
            return sourceOrder == 0 ? target.compareTo(other.target) : sourceOrder;
        }

        @Override
        public boolean equals(Object value) {
            if (this == value) {
                return true;
            }
            if (!(value instanceof Edge)) {
                return false;
            }
            Edge other = (Edge) value;
            return source.equals(other.source) && target.equals(other.target);
        }

        @Override
        public int hashCode() {
            return EDGE_HASH_MULTIPLIER * source.hashCode() + target.hashCode();
        }
    }

    private static <T extends Comparable<? super T>> Set<T> immutableSet(Collection<T> values) {
        return Collections.unmodifiableSet(new TreeSet<>(values));
    }

    private static Map<String, Set<String>> immutableMap(Map<String, Set<String>> values) {
        Map<String, Set<String>> copy = new TreeMap<>();
        values.forEach((key, value) -> copy.put(key, immutableSet(value)));
        return Collections.unmodifiableMap(copy);
    }

    private static List<Set<String>> immutableList(List<Set<String>> values) {
        List<Set<String>> copy = new ArrayList<>();
        values.forEach(value -> copy.add(immutableSet(value)));
        return Collections.unmodifiableList(copy);
    }
}
