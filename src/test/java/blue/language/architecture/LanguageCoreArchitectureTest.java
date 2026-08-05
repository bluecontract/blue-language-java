package blue.language.architecture;

import blue.language.testing.RepositoryLayout;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

    private static final int MAX_PRODUCTION_LINES = 800;
    private static final int MAX_FOCUSED_SERVICE_METHODS = 19;
    private static final List<String> PRODUCT_MODULES =
            Collections.unmodifiableList(Arrays.asList(
                    "blue-language-model",
                    "blue-language-core",
                    "blue-language-mapping",
                    "blue-language-ipfs",
                    "blue-contracts-core",
                    "blue-conformance",
                    "blue-language-java"));
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
                    "blue.language.api.LanguageRuntimeAccess",
                    "blue.language.api.LanguageMatchingService",
                    "blue.language.api.LanguageRuntimeLimitedResolution",
                    "blue.language.api.LanguageRuntimeServices",
                    "blue.language.api.LanguageRuntimeSnapshotStore",
                    "blue.language.api.WeightedLruCache",
                    "blue.language.preprocess.processor.InferBasicTypesForUntypedValues",
                    "blue.language.preprocess.processor.NormalizeListPlaceholders",
                    "blue.language.preprocess.processor.ReplaceInlineValuesForTypeAttributesWithImports",
                    "blue.language.provider.BasicNodeProvider",
                    "blue.language.provider.BootstrapProvider",
                    "blue.language.provider.BundledTransformationProvider",
                    "blue.language.provider.DirectoryBasedNodeProvider",
                    "blue.language.provider.NodeProviderOutcome",
                    "blue.language.provider.NodeProviderWrapper",
                    "blue.language.patching.BluePatch",
                    "blue.language.patching.BluePatchOperation",
                    "blue.language.patching.CanonicalOverlayPatchEngine",
                    "blue.language.patching.CanonicalPatchResult",
                    "blue.language.patching.ImmutableBluePatch",
                    "blue.language.snapshot.BlueSnapshots",
                    "blue.language.snapshot.ResolvedReferenceCache",
                    "blue.language.snapshot.ResolvedReferenceCacheAccounting",
                    "blue.language.snapshot.ResolvedReferenceCacheGeneration",
                    "blue.language.snapshot.ResolvedReferenceCacheLifecycle",
                    "blue.language.snapshot.ResolvedReferenceCacheStatistics",
                    "blue.language.snapshot.ResolvedReferenceGraphIndex",
                    "blue.language.snapshot.ResolvedSnapshot",
                    "blue.language.snapshot.VerifiedCanonicalLoadCoordinator",
                    "blue.language.snapshot.VerifiedReferenceEntry",
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
    void shouldKeepLanguageCoreIndependentFromContractsConformanceAndAggregate()
            throws IOException {
        // given
        List<SourceFile> sources = readLanguageCoreSources();
        List<String> violations = new ArrayList<>();

        // when
        for (SourceFile source : sources) {
            for (String importedType : source.imports) {
                if (isForbiddenCoreImport(importedType)) {
                    violations.add(
                            source.relativePath + " -> " + importedType);
                }
            }
        }

        // then
        assertTrue(violations.isEmpty(),
                "The blue-language-core module must not import Contracts, "
                        + "conformance tooling, or the aggregate Blue facade: "
                        + violations);
    }

    @Test
    void shouldKeepConformanceApiIndependentFromFixtureImplementations()
            throws IOException {
        // given
        List<SourceFile> sources = readModuleSources("blue-conformance");
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
    void shouldKeepLanguageCoreImplementationWithinBudgetOrNarrowAllowlist()
            throws IOException {
        // given
        List<SourceFile> sources = readLanguageCoreSources();
        Map<String, SourceFile> byPath = sources.stream()
                .collect(Collectors.toMap(
                        source -> source.relativePath,
                        source -> source));
        List<String> unexpectedOversizedFiles = new ArrayList<>();

        // when
        for (SourceFile source : sources) {
            if (source.implementationLineCount > MAX_PRODUCTION_LINES
                    && !OVERSIZED_ALLOWLIST.containsKey(
                    source.relativePath)) {
                unexpectedOversizedFiles.add(
                        source.relativePath + "="
                                + source.implementationLineCount);
            }
        }
        List<String> staleAllowances = new ArrayList<>();
        for (Map.Entry<String, String> allowance :
                OVERSIZED_ALLOWLIST.entrySet()) {
            SourceFile source = byPath.get(allowance.getKey());
            if (source == null
                    || source.implementationLineCount
                    <= MAX_PRODUCTION_LINES
                    || allowance.getValue().trim().isEmpty()) {
                staleAllowances.add(allowance.getKey());
            }
        }

        // then
        assertTrue(unexpectedOversizedFiles.isEmpty(),
                "Unexpected Language-core source files exceed "
                        + MAX_PRODUCTION_LINES
                        + " implementation lines: "
                        + unexpectedOversizedFiles);
        assertTrue(staleAllowances.isEmpty(),
                "Remove obsolete or undocumented size allowances: "
                        + staleAllowances);
    }

    @Test
    void shouldKeepFocusedServiceSurfacesBelowPublicMethodBudget()
            throws IOException {
        // given
        Map<String, SourceFile> sources = readLanguageCoreSources()
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
        List<SourceFile> mappingSources =
                readModuleSources("blue-language-mapping").stream()
                        .filter(source -> source.packageName.equals(
                                "blue.language.mapping"))
                        .collect(Collectors.toList());
        Path removedRegistry =
                RepositoryLayout.productionJavaRoot(
                                "blue-language-mapping")
                        .resolve("blue/language/mapping/"
                                + "TypeCreatorRegistry.java");
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
        List<SourceFile> sources = readProductSources();
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
            String relativeFacade =
                    removedFacade.replace('.', '/') + ".java";
            for (String module : PRODUCT_MODULES) {
                Path productionRoot =
                        RepositoryLayout.productionJavaRoot(module);
                Path facadePath = productionRoot.resolve(relativeFacade);
                if (Files.exists(facadePath)) {
                    violations.add(relativeFacade);
                }
            }
        }

        // then
        assertTrue(violations.isEmpty(),
                "Removed Language compatibility API reappeared: "
                        + violations);
    }

    @Test
    void shouldKeepLanguageCorePackageGraphAcyclic()
            throws IOException {
        // given
        PackageGraph complete = PackageGraph.from(
                readLanguageCoreSources());

        // when
        List<Set<String>> stronglyConnectedComponents =
                complete.cyclicStronglyConnectedComponents();

        // then
        assertTrue(stronglyConnectedComponents.isEmpty(),
                "Language-core packages must remain acyclic. Actual SCCs: "
                        + stronglyConnectedComponents);
    }

    private static List<SourceFile> readLanguageCoreSources()
            throws IOException {
        return readModuleSources("blue-language-core");
    }

    private static List<SourceFile> readProductSources()
            throws IOException {
        List<SourceFile> result = new ArrayList<>();
        for (String module : PRODUCT_MODULES) {
            result.addAll(readModuleSources(module));
        }
        return result;
    }

    private static List<SourceFile> readModuleSources(String module)
            throws IOException {
        Path productionRoot =
                RepositoryLayout.productionJavaRoot(module);
        List<SourceFile> result = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(productionRoot)) {
            List<Path> javaSources = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString()
                            .endsWith(".java"))
                    .sorted(Comparator.comparing(Path::toString))
                    .collect(Collectors.toList());
            for (Path source : javaSources) {
                result.add(SourceFile.read(
                        productionRoot, source));
            }
        }
        return result;
    }

    private static boolean isForbiddenCoreImport(String importedType) {
        return importedType.startsWith("blue.language.processor.")
                || importedType.startsWith(
                "blue.language.conformance.api.")
                || importedType.startsWith(
                "blue.language.conformance.cli.")
                || importedType.startsWith(
                "blue.language.conformance.contracts.")
                || importedType.startsWith(
                "blue.language.conformance.runner.")
                || importedType.equals("blue.language.Blue")
                || importedType.startsWith("blue.language.Blue.");
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

    private static int implementationLineCount(String source) {
        int count = 0;
        for (String line : source.split("\\r\\n|\\r|\\n", -1)) {
            if (!line.trim().isEmpty()) {
                count++;
            }
        }
        return count;
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
                "blue/language/runtime/RuntimeLanguageProcessing.java",
                "The strict invocation-provider scope shares the complete "
                        + "snapshot lifecycle and cache-domain implementation "
                        + "so no provider path can bypass the common guard.");
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
        result.put("blue/language/merge/BlueSnapshots.java",
                MAX_FOCUSED_SERVICE_METHODS);
        result.put("blue/language/matching/BlueMatching.java",
                MAX_FOCUSED_SERVICE_METHODS);
        result.put("blue/language/patching/BluePatching.java",
                MAX_FOCUSED_SERVICE_METHODS);
        return Collections.unmodifiableMap(result);
    }

    private static final class SourceFile {
        private final String relativePath;
        private final String packageName;
        private final List<String> imports;
        private final String codeWithoutComments;
        private final int implementationLineCount;

        private SourceFile(
                String relativePath,
                String packageName,
                List<String> imports,
                String codeWithoutComments,
                int implementationLineCount) {
            this.relativePath = relativePath;
            this.packageName = packageName;
            this.imports = imports;
            this.codeWithoutComments = codeWithoutComments;
            this.implementationLineCount = implementationLineCount;
        }

        private static SourceFile read(
                Path productionRoot, Path path) throws IOException {
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
            String relative = productionRoot.relativize(path)
                    .toString().replace('\\', '/');
            String codeWithoutComments =
                    withoutCommentsAndLiterals(source);
            return new SourceFile(
                    relative,
                    packageMatcher.group(1),
                    Collections.unmodifiableList(imports),
                    codeWithoutComments,
                    implementationLineCount(codeWithoutComments));
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
