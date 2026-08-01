package blue.language.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Source-level architecture gates that require no bytecode-analysis library. */
class LanguageCoreArchitectureTest {

    private static final Path PRODUCTION_ROOT =
            Paths.get("src", "main", "java");
    private static final int MAX_PRODUCTION_LINES = 800;
    private static final int MAX_FOCUSED_SERVICE_METHODS = 19;
    private static final Pattern PACKAGE_DECLARATION = Pattern.compile(
            "(?m)^\\s*package\\s+([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*)\\s*;");
    private static final Pattern IMPORT_DECLARATION = Pattern.compile(
            "(?m)^\\s*import\\s+(?:static\\s+)?([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*)\\s*;");
    private static final Pattern INTERFACE_METHOD =
            Pattern.compile("\\)\\s*;");
    private static final Pattern PUBLIC_METHOD = Pattern.compile(
            "(?s)\\bpublic\\s+(?:(?:static|final|synchronized|abstract|default)\\s+)*"
                    + "(?:<[^>{}]+>\\s*)?[A-Za-z_$][\\w$<>?,.\\[\\] ]*\\s+"
                    + "[A-Za-z_$][\\w$]*\\s*\\([^;{}]*\\)"
                    + "\\s*(?:throws\\s+[^;{]+)?\\{");
    private static final Pattern NON_FINAL_STATIC_FIELD = Pattern.compile(
            "(?m)^\\s*(?:(?:public|protected|private)\\s+)?static\\s+"
                    + "(?!final\\s+)[^;(){}]+;");

    private static final Map<String, String> OVERSIZED_ALLOWLIST =
            oversizedAllowlist();
    private static final Map<String, Integer> FOCUSED_SERVICE_BUDGETS =
            focusedServiceBudgets();
    private static final Set<String> PHASE_FOUR_CYCLE_BOUNDARY =
            phaseFourCycleBoundary();
    private static final List<String> REMOVED_API_SYMBOLS =
            Collections.unmodifiableList(Arrays.asList(
                    "calculateSemanticBlueId",
                    "SemanticBlueId",
                    "MeaningId",
                    "NodeExtender",
                    "preprocessWithDefaultBlue",
                    "preprocessWithoutDefaultBlue",
                    "DEFAULT_BLUE_BLUE_ID"));
    private static final List<String> REMOVED_OWNERSHIP_TYPES =
            Collections.unmodifiableList(Arrays.asList(
                    "blue.language.api.BlueLanguage",
                    "blue.language.api.BlueLanguageRuntime",
                    "blue.language.api.LanguageMatchingService",
                    "blue.language.api.LanguageRuntimeLimitedResolution",
                    "blue.language.api.LanguageRuntimeServices",
                    "blue.language.api.LanguageRuntimeSnapshotStore",
                    "blue.language.api.WeightedLruCache",
                    "blue.language.utils.Base58",
                    "blue.language.utils.Base58Sha256Provider",
                    "blue.language.utils.BlueIdCalculator",
                    "blue.language.utils.BlueNumbers",
                    "blue.language.utils.CircularBlueIdCalculator",
                    "blue.language.utils.FrozenTypeMatcher",
                    "blue.language.utils.JsonPointer",
                    "blue.language.utils.NodeExpander",
                    "blue.language.utils.NodePathAccessor",
                    "blue.language.utils.NodeProviderWrapper",
                    "blue.language.utils.NodeSpecializer",
                    "blue.language.utils.NodeToMapListOrValue",
                    "blue.language.utils.NodeTypeMatcher",
                    "blue.language.utils.Properties",
                    "blue.language.utils.SchemaPropertyConstants",
                    "blue.language.utils.SchemaToMapListOrValue",
                    "blue.language.utils.TypeUtils",
                    "blue.language.utils.Types"));

    @Test
    void shouldKeepLanguageCoreIndependentFromRuntimeAndLegacyAggregate()
            throws IOException {
        // given
        List<SourceFile> sources = readProductionSources();
        List<String> violations = new ArrayList<>();

        // when
        for (SourceFile source : sources) {
            if (!isLanguageCorePackage(source.packageName)) {
                continue;
            }
            for (String importedType : source.imports) {
                if (isForbiddenCoreImport(importedType)) {
                    violations.add(
                            source.relativePath + " -> " + importedType);
                }
            }
        }

        // then
        assertTrue(violations.isEmpty(),
                "Language-core source must not import Contracts runtime, "
                        + "conformance, or the legacy Blue aggregate: "
                        + violations);
    }

    @Test
    void shouldKeepConformanceApiIndependentFromFixtureImplementations()
            throws IOException {
        // given
        List<SourceFile> sources = readProductionSources();
        List<String> violations = new ArrayList<>();

        // when
        for (SourceFile source : sources) {
            if (!source.packageName.equals(
                    "blue.language.conformance.api")) {
                continue;
            }
            for (String importedType : source.imports) {
                if (importedType.startsWith(
                        "blue.language.conformance.contracts.")) {
                    violations.add(
                            source.relativePath + " -> " + importedType);
                }
            }
        }

        // then
        assertTrue(violations.isEmpty(),
                "Conformance API must not depend on fixture "
                        + "implementations: " + violations);
    }

    @Test
    void shouldKeepLanguageCoreFilesWithinBudgetOrNarrowAllowlist()
            throws IOException {
        // given
        List<SourceFile> sources = readProductionSources();
        Map<String, SourceFile> byPath = sources.stream()
                .collect(Collectors.toMap(
                        source -> source.relativePath,
                        source -> source));
        List<String> unexpectedOversizedFiles = new ArrayList<>();

        // when
        for (SourceFile source : sources) {
            if (isContractsRuntimeSource(source.relativePath)) {
                continue;
            }
            if (source.lineCount > MAX_PRODUCTION_LINES
                    && !OVERSIZED_ALLOWLIST.containsKey(
                    source.relativePath)) {
                unexpectedOversizedFiles.add(
                        source.relativePath + "=" + source.lineCount);
            }
        }
        List<String> staleAllowances = new ArrayList<>();
        for (Map.Entry<String, String> allowance :
                OVERSIZED_ALLOWLIST.entrySet()) {
            SourceFile source = byPath.get(allowance.getKey());
            if (source == null
                    || source.lineCount <= MAX_PRODUCTION_LINES
                    || allowance.getValue().trim().isEmpty()) {
                staleAllowances.add(allowance.getKey());
            }
        }

        // then
        assertTrue(unexpectedOversizedFiles.isEmpty(),
                "Unexpected Language-core source files exceed "
                        + MAX_PRODUCTION_LINES + " lines: "
                        + unexpectedOversizedFiles);
        assertTrue(staleAllowances.isEmpty(),
                "Remove obsolete or undocumented size allowances: "
                        + staleAllowances);
    }

    @Test
    void shouldKeepFocusedServiceSurfacesBelowPublicMethodBudget()
            throws IOException {
        // given
        Map<String, SourceFile> sources = readProductionSources()
                .stream()
                .collect(Collectors.toMap(
                        source -> source.relativePath,
                        source -> source));
        List<String> violations = new ArrayList<>();

        // when
        for (Map.Entry<String, Integer> budget :
                FOCUSED_SERVICE_BUDGETS.entrySet()) {
            SourceFile source = sources.get(budget.getKey());
            if (source == null) {
                violations.add(budget.getKey() + " is missing");
                continue;
            }
            int methods = budget.getKey().endsWith("BlueLanguage.java")
                    ? countMatches(PUBLIC_METHOD, source.codeWithoutComments)
                    : countMatches(INTERFACE_METHOD, source.codeWithoutComments);
            if (methods <= 0 || methods > budget.getValue()) {
                violations.add(
                        budget.getKey() + "=" + methods
                                + " (budget " + budget.getValue() + ")");
            }
        }

        // then
        assertTrue(violations.isEmpty(),
                "Focused Language services must remain small: "
                        + violations);
    }

    @Test
    void shouldUseInstanceScopedImmutableMappingRegistries()
            throws IOException {
        // given
        List<SourceFile> mappingSources = readProductionSources()
                .stream()
                .filter(source -> source.packageName.equals(
                        "blue.language.mapping"))
                .collect(Collectors.toList());
        Path removedRegistry = PRODUCTION_ROOT.resolve(
                Paths.get("blue", "language", "mapping",
                        "TypeCreatorRegistry.java"));
        List<String> mutableStaticFields = new ArrayList<>();
        List<String> legacyReferences = new ArrayList<>();

        // when
        for (SourceFile source : mappingSources) {
            Matcher staticField = NON_FINAL_STATIC_FIELD.matcher(
                    source.codeWithoutComments);
            while (staticField.find()) {
                mutableStaticFields.add(
                        source.relativePath + ": "
                                + oneLine(staticField.group()));
            }
            if (containsWord(
                    source.codeWithoutComments,
                    "TypeCreatorRegistry")) {
                legacyReferences.add(source.relativePath);
            }
        }
        SourceFile registry = mappingSources.stream()
                .filter(source -> source.relativePath.endsWith(
                        "ObjectFactoryRegistry.java"))
                .findFirst()
                .orElse(null);

        // then
        assertFalse(Files.exists(removedRegistry),
                "The process-global TypeCreatorRegistry must stay removed");
        assertTrue(legacyReferences.isEmpty(),
                "Mapping source still references TypeCreatorRegistry: "
                        + legacyReferences);
        assertTrue(mutableStaticFields.isEmpty(),
                "Mapping must not retain process-global mutable fields: "
                        + mutableStaticFields);
        assertTrue(registry != null
                        && registry.codeWithoutComments.contains(
                        "Collections.unmodifiableMap"),
                "ObjectFactoryRegistry must freeze its instance map");
    }

    @Test
    void shouldKeepRemovedCompatibilitySymbolsOutOfProductionApi()
            throws IOException {
        // given
        List<SourceFile> sources = readProductionSources();
        List<String> violations = new ArrayList<>();

        // when
        for (SourceFile source : sources) {
            for (String symbol : REMOVED_API_SYMBOLS) {
                if (containsWord(source.codeWithoutComments, symbol)
                        || source.relativePath.endsWith(
                        "/" + symbol + ".java")) {
                    violations.add(
                            source.relativePath + " -> " + symbol);
                }
            }
            if (Pattern.compile("\\bextend\\s*\\(")
                    .matcher(source.codeWithoutComments).find()) {
                violations.add(
                        source.relativePath + " -> extend(...)");
            }
            for (String importedType : source.imports) {
                for (String removedFacade : REMOVED_OWNERSHIP_TYPES) {
                    if (importedType.equals(removedFacade)
                            || importedType.startsWith(
                            removedFacade + ".")) {
                        violations.add(
                                source.relativePath + " -> "
                                        + importedType);
                    }
                }
            }
        }
        for (String removedFacade : REMOVED_OWNERSHIP_TYPES) {
            Path facadePath = PRODUCTION_ROOT.resolve(
                    removedFacade.replace('.', '/') + ".java");
            if (Files.exists(facadePath)) {
                violations.add(
                        PRODUCTION_ROOT.relativize(facadePath)
                                .toString().replace('\\', '/'));
            }
        }

        // then
        assertTrue(violations.isEmpty(),
                "Removed Language compatibility API reappeared: "
                        + violations);
    }

    @Test
    void shouldKeepKnownPackageCyclesInsideDocumentedPhaseFourBoundary()
            throws IOException {
        // given
        PackageGraph complete = PackageGraph.from(
                readProductionSources());
        PackageGraph core = complete.retainPackages(
                LanguageCoreArchitectureTest::isLanguageCorePackage);

        // when
        List<Set<String>> stronglyConnectedComponents =
                core.cyclicStronglyConnectedComponents();
        Set<String> cyclicPackages = stronglyConnectedComponents
                .stream()
                .flatMap(Set::stream)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> unexpected = new LinkedHashSet<>(cyclicPackages);
        unexpected.removeAll(PHASE_FOUR_CYCLE_BOUNDARY);

        // then
        assertTrue(unexpected.isEmpty(),
                "New package cycles escaped the documented Phase 4 "
                        + "decomposition boundary. Actual SCCs: "
                        + stronglyConnectedComponents
                        + "; unexpected packages: " + unexpected);
    }

    private static List<SourceFile> readProductionSources()
            throws IOException {
        try (Stream<Path> paths = Files.walk(PRODUCTION_ROOT)) {
            List<Path> javaSources = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString()
                            .endsWith(".java"))
                    .sorted(Comparator.comparing(Path::toString))
                    .collect(Collectors.toList());
            List<SourceFile> result = new ArrayList<>(
                    javaSources.size());
            for (Path source : javaSources) {
                result.add(SourceFile.read(source));
            }
            return result;
        }
    }

    private static boolean isLanguageCorePackage(String packageName) {
        return packageName.startsWith("blue.language.")
                && !packageName.startsWith("blue.language.api")
                && !packageName.startsWith(
                "blue.language.conformance")
                && !packageName.startsWith(
                "blue.language.processor")
                && !packageName.startsWith(
                "blue.language.runtime");
    }

    private static boolean isForbiddenCoreImport(String importedType) {
        return importedType.startsWith("blue.language.processor.")
                || importedType.startsWith(
                "blue.language.conformance.")
                || importedType.equals("blue.language.Blue")
                || importedType.startsWith("blue.language.Blue.");
    }

    private static boolean isContractsRuntimeSource(String relativePath) {
        return relativePath.startsWith("blue/language/processor/");
    }

    private static int countMatches(Pattern pattern, String value) {
        int count = 0;
        Matcher matcher = pattern.matcher(value);
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private static boolean containsWord(String source, String symbol) {
        return Pattern.compile(
                "(?<![A-Za-z0-9_$])" + Pattern.quote(symbol)
                        + "(?![A-Za-z0-9_$])")
                .matcher(source)
                .find();
    }

    private static String oneLine(String value) {
        return value.trim().replaceAll("\\s+", " ");
    }

    private static String withoutCommentsAndLiterals(String source) {
        final int code = 0;
        final int lineComment = 1;
        final int blockComment = 2;
        final int stringLiteral = 3;
        final int characterLiteral = 4;
        int state = code;
        StringBuilder result = new StringBuilder(source.length());
        for (int index = 0; index < source.length(); index++) {
            char current = source.charAt(index);
            char next = index + 1 < source.length()
                    ? source.charAt(index + 1)
                    : '\0';
            if (state == code) {
                if (current == '/' && next == '/') {
                    result.append("  ");
                    index++;
                    state = lineComment;
                } else if (current == '/' && next == '*') {
                    result.append("  ");
                    index++;
                    state = blockComment;
                } else if (current == '"') {
                    result.append(' ');
                    state = stringLiteral;
                } else if (current == '\'') {
                    result.append(' ');
                    state = characterLiteral;
                } else {
                    result.append(current);
                }
                continue;
            }
            if (state == lineComment) {
                if (current == '\n' || current == '\r') {
                    result.append(current);
                    state = code;
                } else {
                    result.append(' ');
                }
                continue;
            }
            if (state == blockComment) {
                if (current == '*' && next == '/') {
                    result.append("  ");
                    index++;
                    state = code;
                } else {
                    result.append(current == '\n' || current == '\r'
                            ? current
                            : ' ');
                }
                continue;
            }
            if (current == '\\' && next != '\0') {
                result.append(' ');
                result.append(next == '\n' || next == '\r'
                        ? next
                        : ' ');
                index++;
            } else if ((state == stringLiteral && current == '"')
                    || (state == characterLiteral && current == '\'')) {
                result.append(' ');
                state = code;
            } else {
                result.append(current == '\n' || current == '\r'
                        ? current
                        : ' ');
            }
        }
        return result.toString();
    }

    private static Map<String, String> oversizedAllowlist() {
        Map<String, String> result = new LinkedHashMap<>();
        result.put(
                "blue/language/Blue.java",
                "Legacy aggregate retained only as the Phase 4 compatibility facade");
        result.put(
                "blue/language/conformance/api/BlueConformanceSuiteRunner.java",
                "Release conformance harness decomposition is a Phase 4 module task");
        result.put(
                "blue/language/conformance/api/BlueContractsConformanceReport.java",
                "Contracts conformance report extraction belongs to the Phase 4 module boundary");
        result.put(
                "blue/language/conformance/contracts/ClosedContractsFixtureValidator.java",
                "Closed fixture schema validation remains one generated release boundary");
        result.put(
                "blue/language/conformance/contracts/ContractsFixtureHarness.java",
                "Closed executable fixture DSL remains one release-evidence boundary");
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, Integer> focusedServiceBudgets() {
        Map<String, Integer> result = new LinkedHashMap<>();
        result.put("blue/language/runtime/BlueLanguage.java",
                MAX_FOCUSED_SERVICE_METHODS);
        result.put("blue/language/codec/BlueCodec.java",
                MAX_FOCUSED_SERVICE_METHODS);
        result.put("blue/language/preprocess/BluePreprocessing.java",
                MAX_FOCUSED_SERVICE_METHODS);
        result.put("blue/language/graph/BlueGraph.java",
                MAX_FOCUSED_SERVICE_METHODS);
        result.put("blue/language/resolve/BlueResolution.java",
                MAX_FOCUSED_SERVICE_METHODS);
        result.put("blue/language/identity/BlueIdentity.java",
                MAX_FOCUSED_SERVICE_METHODS);
        result.put("blue/language/snapshot/BlueSnapshots.java",
                MAX_FOCUSED_SERVICE_METHODS);
        result.put("blue/language/matching/BlueMatching.java",
                MAX_FOCUSED_SERVICE_METHODS);
        result.put("blue/language/patching/BluePatching.java",
                MAX_FOCUSED_SERVICE_METHODS);
        return Collections.unmodifiableMap(result);
    }

    private static Set<String> phaseFourCycleBoundary() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(
                Arrays.asList(
                        "blue.language.identity",
                        "blue.language.matching",
                        "blue.language.matching.internal",
                        "blue.language.merge",
                        "blue.language.patching",
                        "blue.language.preprocess",
                        "blue.language.preprocess.processor",
                        "blue.language.provider",
                        "blue.language.registry",
                        "blue.language.resolve",
                        "blue.language.snapshot")));
    }

    private static final class SourceFile {
        private final String relativePath;
        private final String packageName;
        private final List<String> imports;
        private final String codeWithoutComments;
        private final int lineCount;

        private SourceFile(
                String relativePath,
                String packageName,
                List<String> imports,
                String codeWithoutComments,
                int lineCount) {
            this.relativePath = relativePath;
            this.packageName = packageName;
            this.imports = imports;
            this.codeWithoutComments = codeWithoutComments;
            this.lineCount = lineCount;
        }

        private static SourceFile read(Path path) throws IOException {
            String source = new String(
                    Files.readAllBytes(path), StandardCharsets.UTF_8);
            Matcher packageMatcher = PACKAGE_DECLARATION.matcher(source);
            if (!packageMatcher.find()) {
                throw new IllegalStateException(
                        "Production source has no package: " + path);
            }
            List<String> imports = new ArrayList<>();
            Matcher importMatcher = IMPORT_DECLARATION.matcher(source);
            while (importMatcher.find()) {
                imports.add(importMatcher.group(1));
            }
            String relative = PRODUCTION_ROOT.relativize(path)
                    .toString().replace('\\', '/');
            return new SourceFile(
                    relative,
                    packageMatcher.group(1),
                    Collections.unmodifiableList(imports),
                    withoutCommentsAndLiterals(source),
                    Files.readAllLines(
                            path, StandardCharsets.UTF_8).size());
        }
    }

    private static final class PackageGraph {
        private final Map<String, Set<String>> dependencies;

        private PackageGraph(Map<String, Set<String>> dependencies) {
            this.dependencies = dependencies;
        }

        private static PackageGraph from(List<SourceFile> sources) {
            Set<String> packages = sources.stream()
                    .map(source -> source.packageName)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            List<String> longestPackageFirst =
                    new ArrayList<>(packages);
            longestPackageFirst.sort(
                    Comparator.comparingInt(String::length)
                            .reversed()
                            .thenComparing(Comparator.naturalOrder()));
            Map<String, Set<String>> dependencies =
                    new LinkedHashMap<>();
            for (String packageName : packages) {
                dependencies.put(packageName, new LinkedHashSet<>());
            }
            for (SourceFile source : sources) {
                for (String importedType : source.imports) {
                    String importedPackage = resolvePackage(
                            importedType, longestPackageFirst);
                    if (importedPackage != null
                            && !importedPackage.equals(
                            source.packageName)) {
                        dependencies.get(source.packageName)
                                .add(importedPackage);
                    }
                }
            }
            return new PackageGraph(dependencies);
        }

        private PackageGraph retainPackages(
                java.util.function.Predicate<String> retained) {
            Map<String, Set<String>> result = new LinkedHashMap<>();
            for (Map.Entry<String, Set<String>> entry :
                    dependencies.entrySet()) {
                if (!retained.test(entry.getKey())) {
                    continue;
                }
                Set<String> targets = entry.getValue().stream()
                        .filter(retained)
                        .collect(Collectors.toCollection(
                                LinkedHashSet::new));
                result.put(entry.getKey(), targets);
            }
            return new PackageGraph(result);
        }

        private List<Set<String>> cyclicStronglyConnectedComponents() {
            return new Tarjan(dependencies).cyclicComponents();
        }

        private static String resolvePackage(
                String importedType,
                List<String> longestPackageFirst) {
            for (String candidate : longestPackageFirst) {
                if (importedType.equals(candidate)
                        || importedType.startsWith(
                        candidate + ".")) {
                    return candidate;
                }
            }
            return null;
        }
    }

    private static final class Tarjan {
        private final Map<String, Set<String>> graph;
        private final Map<String, Integer> indices = new HashMap<>();
        private final Map<String, Integer> lowLinks = new HashMap<>();
        private final Deque<String> stack = new ArrayDeque<>();
        private final Set<String> onStack = new HashSet<>();
        private final List<Set<String>> components = new ArrayList<>();
        private int nextIndex;

        private Tarjan(Map<String, Set<String>> graph) {
            this.graph = graph;
        }

        private List<Set<String>> cyclicComponents() {
            List<String> packages = new ArrayList<>(graph.keySet());
            Collections.sort(packages);
            for (String packageName : packages) {
                if (!indices.containsKey(packageName)) {
                    visit(packageName);
                }
            }
            List<Set<String>> cyclic = components.stream()
                    .filter(component -> component.size() > 1)
                    .sorted(Comparator.comparing(
                            component -> component.iterator().next()))
                    .collect(Collectors.toList());
            return Collections.unmodifiableList(cyclic);
        }

        private void visit(String packageName) {
            indices.put(packageName, nextIndex);
            lowLinks.put(packageName, nextIndex);
            nextIndex++;
            stack.push(packageName);
            onStack.add(packageName);

            List<String> targets = new ArrayList<>(
                    graph.getOrDefault(
                            packageName,
                            Collections.emptySet()));
            Collections.sort(targets);
            for (String target : targets) {
                if (!indices.containsKey(target)) {
                    visit(target);
                    lowLinks.put(packageName, Math.min(
                            lowLinks.get(packageName),
                            lowLinks.get(target)));
                } else if (onStack.contains(target)) {
                    lowLinks.put(packageName, Math.min(
                            lowLinks.get(packageName),
                            indices.get(target)));
                }
            }

            if (!lowLinks.get(packageName).equals(
                    indices.get(packageName))) {
                return;
            }
            List<String> component = new ArrayList<>();
            String member;
            do {
                member = stack.pop();
                onStack.remove(member);
                component.add(member);
            } while (!member.equals(packageName));
            Collections.sort(component);
            components.add(Collections.unmodifiableSet(
                    new LinkedHashSet<>(component)));
        }
    }
}
