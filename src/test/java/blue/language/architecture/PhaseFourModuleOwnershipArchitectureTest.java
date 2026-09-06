package blue.language.architecture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Enforces the physically extracted Phase 04 module ownership contract. */
final class PhaseFourModuleOwnershipArchitectureTest {

    private static final String MODULE_MODEL = ":blue-language-model";
    private static final String MODULE_CORE = ":blue-language-core";
    private static final String MODULE_CONTRACTS = ":blue-contracts-core";
    private static final String MODULE_MAPPING = ":blue-language-mapping";
    private static final String MODULE_IPFS = ":blue-language-ipfs";
    private static final String MODULE_CONFORMANCE = ":blue-conformance";
    private static final String MODULE_AGGREGATE = ":blue-language-java";
    private static final String MODULE_EXAMPLES = ":examples";
    private static final String MODULE_BUILD_LOGIC = ":build-logic";

    private static final int EXPECTED_PRODUCTION_SOURCES = 789;
    private static final int EXPECTED_PRODUCTION_RESOURCES = 749;
    private static final int ROOT_BUILD_MAX_LINES = 200;
    private static final int MODULE_BUILD_MAX_LINES = 150;
    private static final int HARD_BUILD_SCRIPT_MAX_LINES = 999;

    private static final Path PROJECT_ROOT = projectRoot();
    private static final Path OWNERSHIP_MANIFEST = PROJECT_ROOT.resolve(
            "architecture/module-ownership-1.0.json");
    private static final Path API_LEDGER = PROJECT_ROOT.resolve(
            "api/module-api-relocation-ledger-1.0.json");
    private static final Path DEPENDENCY_REPORT = PROJECT_ROOT.resolve(
            "architecture/dependency-ownership-1.0.json");

    private static final Set<String> BUILD_SCRIPT_NAMES = immutableSet(
            "build.gradle", "build.gradle.kts",
            "settings.gradle", "settings.gradle.kts");
    private static final Set<String> ALLOWED_API_CLASSIFICATIONS = immutableSet(
            "intentional-next-major-break",
            "compatible-relocation-through-aggregate-facade",
            "internal-type-removed-from-public-surface",
            "new-supported-api-spi");
    private static final Set<String> DEPENDENCY_CONFIGURATIONS = immutableSet(
            "annotationProcessor", "api", "classpath", "compileOnly",
            "implementation", "jmh", "jmhImplementation",
            "jmhRuntimeOnly", "runtimeOnly", "testAnnotationProcessor",
            "testCompileOnly", "testFixturesApi",
            "testFixturesImplementation", "testFixturesRuntimeOnly",
            "testImplementation", "testRuntimeOnly");
    private static final Map<String, Set<String>> ALLOWED_MODULE_DAG =
            allowedModuleDag();
    private static final Map<String, String> PACKAGE_RELOCATIONS =
            packageRelocations();

    private static final Pattern PACKAGE_DECLARATION = Pattern.compile(
            "(?m)^\\s*package\\s+([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*)\\s*;");
    private static final Pattern PUBLIC_TOP_LEVEL_TYPE = Pattern.compile(
            "(?m)^public\\s+(?:(?:abstract|final|sealed|non-sealed|strictfp)\\s+)*"
                    + "(?:class|interface|enum|@interface)\\s+([A-Za-z_$][\\w$]*)\\b");
    private static final Pattern IMPORT_DECLARATION = Pattern.compile(
            "(?m)^\\s*import\\s+(?:static\\s+)?([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$*][\\w$*]*)+)\\s*;");
    private static final Pattern PROJECT_DEPENDENCY = Pattern.compile(
            "(?m)\\b(?:api|implementation|compileOnly|runtimeOnly)"
                    + "\\s*(?:\\(\\s*)?"
                    + "project\\s*\\(\\s*['\"](:[A-Za-z0-9_.:-]+)['\"]\\s*\\)");
    private static final Pattern EXTERNAL_DEPENDENCY = Pattern.compile(
            "(?m)\\b(" + String.join("|", DEPENDENCY_CONFIGURATIONS) + ")\\s*"
                    + "(?:\\(\\s*)?(?:platform\\s*\\(\\s*)?['\"]"
                    + "([A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+)"
                    + "(?::([^'\"]+))?['\"]");
    private static final Pattern VERSIONED_PLUGIN = Pattern.compile(
            "(?m)^\\s*id\\s*(?:\\(\\s*)?['\"]([^'\"]+)['\"]\\s*\\)?"
                    + "\\s+version\\s+['\"]([^'\"]+)['\"]");
    private static final Pattern TYPED_LITERAL_DEPENDENCY = Pattern.compile(
            "dependencies\\.add\\(\\s*([^,]+),\\s*"
                    + "(?:dependencies\\.platform\\(\\s*)?['\"]"
                    + "([A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+)"
                    + "(?::([^'\"]+))?['\"]",
            Pattern.MULTILINE);
    private static final Pattern TYPED_COORDINATE_CONSTANT = Pattern.compile(
            "(?m)^\\s*private\\s+static\\s+final\\s+String\\s+"
                    + "([A-Z0-9_]*COORDINATE)\\s*=\\s*['\"]"
                    + "([A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+)"
                    + "(?::([^'\"]+))?['\"]");
    private static final Pattern ROOT_SOURCE_REDIRECTION = Pattern.compile(
            "(?i)(?:rootProject|rootDir)[^\\n]*(?:src[/\\\\](?:main|test|jmh))"
                    + "|(?:srcDirs?|setSrcDirs)[^\\n]*(?:\\.\\.[/\\\\])+[^\\n]*src"
                    + "|(?:srcDirs?|setSrcDirs)[^\\n]*PROJECT_ROOT");

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void shouldAssignEveryPhysicalProductionFileExactlyOnce() throws IOException {
        // given
        JsonNode manifest = readJson(OWNERSHIP_MANIFEST);
        Map<String, Path> moduleRoots = moduleRoots(manifest);
        List<String> actualSources = productionFiles(moduleRoots, "java", ".java");
        List<String> actualResources = productionFiles(moduleRoots, "resources", null);

        // when
        List<String> assignedSources = textValues(manifest.path("sources"), "currentPath");
        List<String> assignedResources = textValues(manifest.path("resources"), "currentPath");
        List<String> invalidAssignments = invalidAssignments(manifest, moduleRoots);

        // then
        assertUniqueAndSorted(assignedSources, "production source ownership");
        assertUniqueAndSorted(assignedResources, "production resource ownership");
        assertEquals(actualSources, assignedSources,
                "Every physical production source must have exactly one owner");
        assertEquals(actualResources, assignedResources,
                "Every physical production resource must have exactly one owner");
        assertEquals(EXPECTED_PRODUCTION_SOURCES, actualSources.size());
        assertEquals(EXPECTED_PRODUCTION_RESOURCES, actualResources.size());
        assertEquals(actualSources.size(), manifest.path("inventory")
                .path("productionSourceCount").asInt());
        assertEquals(actualResources.size(), manifest.path("inventory")
                .path("productionResourceCount").asInt());
        assertEquals(digestLines(actualSources), manifest.path("inventory")
                .path("productionSourcePathIdentity").asText());
        assertEquals(digestLines(actualResources), manifest.path("inventory")
                .path("productionResourcePathIdentity").asText());
        assertTrue(invalidAssignments.isEmpty(),
                "Ownership must name the actual conventional module path: "
                        + invalidAssignments);
        assertTrue(regularFiles(PROJECT_ROOT.resolve("src/main"), null).isEmpty(),
                "The root project must not retain production files");
    }

    @Test
    void shouldKeepPublishedPackagesExclusive() throws IOException {
        // given
        JsonNode manifest = readJson(OWNERSHIP_MANIFEST);
        Set<String> publishedModules = publishedModules(manifest);
        Map<String, Set<String>> packageOwners = new LinkedHashMap<>();
        List<String> targetPaths = new ArrayList<>();
        List<String> packageMismatches = new ArrayList<>();

        // when
        for (JsonNode source : manifest.path("sources")) {
            String owner = source.path("targetModule").asText();
            String targetPackage = source.path("targetPackage").asText();
            String targetPath = source.path("targetPath").asText();
            targetPaths.add(targetPath);
            if (!targetPackage.equals(packageName(PROJECT_ROOT.resolve(targetPath)))) {
                packageMismatches.add(targetPath);
            }
            if (publishedModules.contains(owner)) {
                packageOwners.computeIfAbsent(
                        targetPackage, ignored -> new LinkedHashSet<>()).add(owner);
            }
        }
        for (JsonNode resource : manifest.path("resources")) {
            targetPaths.add(resource.path("targetPath").asText());
        }
        Map<String, Set<String>> splitPackages = packageOwners.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (left, right) -> left,
                        LinkedHashMap::new));

        // then
        assertUnique(targetPaths, "physical target paths");
        assertTrue(packageMismatches.isEmpty(),
                "Manifest packages must match source declarations: " + packageMismatches);
        assertTrue(splitPackages.isEmpty(),
                "Published modules must not split Java packages: " + splitPackages);
    }

    @Test
    void shouldKeepTheDeclaredAndObservedModuleGraphAcyclic() throws IOException {
        // given
        JsonNode manifest = readJson(OWNERSHIP_MANIFEST);
        Map<String, Set<String>> declared = declaredModuleDag(manifest);
        Map<String, Set<String>> buildEdges = projectDependencyEdges(manifest);
        List<String> undeclaredImports = undeclaredImportEdges(manifest, declared);

        // when
        List<String> cycles = cyclesIn(declared);

        // then
        assertEquals(ALLOWED_MODULE_DAG, declared,
                "The reviewed direct module DAG changed without ADR 0007");
        assertEquals(declared, buildEdges,
                "Gradle project dependencies must match the ownership manifest");
        assertTrue(cycles.isEmpty(), "Module graph must be acyclic: " + cycles);
        assertTrue(undeclaredImports.isEmpty(),
                "Production imports must not create undeclared module edges: "
                        + undeclaredImports);
        assertFalse(declared.get(MODULE_AGGREGATE).contains(MODULE_CONFORMANCE),
                "The compatibility aggregate must not pull in conformance tooling");
    }

    @Test
    void shouldClassifyEveryCurrentPublicTopLevelTypeAndRecordedRelocation()
            throws IOException {
        // given
        JsonNode manifest = readJson(OWNERSHIP_MANIFEST);
        JsonNode ledger = readJson(API_LEDGER);
        Map<String, String> ownerBySource = ownerBySource(manifest);
        Set<String> actualTopLevels = publicTopLevelTypes(manifest);
        List<String> classifiedTypes = new ArrayList<>();
        Set<String> classifiedTopLevels = new LinkedHashSet<>();
        List<String> invalidEntries = new ArrayList<>();
        Map<String, Integer> classificationCounts = new LinkedHashMap<>();

        // when
        for (JsonNode entry : ledger.path("types")) {
            String type = entry.path("type").asText();
            String sourcePath = entry.path("sourcePath").asText();
            String classification = entry.path("classification").asText();
            classifiedTypes.add(type);
            classifiedTopLevels.add(type.split("\\$", 2)[0]);
            classificationCounts.put(classification,
                    classificationCounts.getOrDefault(classification, 0) + 1);
            if (!ALLOWED_API_CLASSIFICATIONS.contains(classification)
                    || !type.equals(entry.path("targetType").asText())
                    || !entry.path("targetModule").asText()
                    .equals(ownerBySource.get(sourcePath))
                    || entry.path("reason").asText().trim().isEmpty()) {
                invalidEntries.add(type);
            }
        }
        List<String> missingRelocations = missingRelocations(ledger);

        // then
        assertUniqueAndSorted(classifiedTypes, "public API classifications");
        assertEquals(actualTopLevels, classifiedTopLevels,
                "Every public production top-level type must be classified");
        assertTrue(invalidEntries.isEmpty(),
                "Invalid public API relocation entries: " + invalidEntries);
        assertTrue(missingRelocations.isEmpty(),
                "Commit 1f79996 relocations must remain explicit: " + missingRelocations);
        assertEquals(ALLOWED_API_CLASSIFICATIONS,
                textSet(ledger.path("allowedClassifications")));
        assertEquals(classificationCounts,
                integerFields(ledger.path("inventory").path("classificationCounts")));
        assertEquals(classifiedTypes.size(), ledger.path("inventory")
                .path("publicProductionTypeCount").asInt());
        assertEquals(digestLines(classifiedTypes), ledger.path("inventory")
                .path("publicTypeIdentity").asText());
    }

    @Test
    void shouldOwnEveryDiscoveredExternalDependencyAndEnforceRuntimePolicy()
            throws IOException {
        // given
        JsonNode report = readJson(DEPENDENCY_REPORT);
        List<Path> scripts = buildScripts();
        List<Path> typedSources = typedBuildLogicSources();
        Set<String> discoveredLibraries = externalLibraries(scripts, typedSources);
        Set<String> discoveredPlugins = versionedPlugins(scripts);
        Map<String, List<String>> actualLibraryDeclarations =
                externalDeclarationEvidence(scripts, typedSources);
        Map<String, List<String>> actualPluginDeclarations =
                pluginDeclarationEvidence(scripts);
        List<String> reportedLibraries = textValues(report.path("libraries"), "component");
        List<String> reportedPlugins = textValues(report.path("plugins"), "component");
        Set<String> knownModules = ALLOWED_MODULE_DAG.keySet();
        List<String> invalidEntries = new ArrayList<>();

        // when
        validateDependencyEntries(report.path("libraries"), knownModules, invalidEntries);
        validateDependencyEntries(report.path("plugins"), knownModules, invalidEntries);
        Map<String, Set<String>> actualRuntime = runtimeLibrariesByModule(scripts);
        Map<String, Set<String>> allowedRuntime = stringSetFields(
                report.path("policy").path("moduleRuntimeAllowlist"));
        Set<String> forbiddenCore = textSet(
                report.path("policy").path("forbiddenInCoreRuntime"));
        Set<String> forbiddenPresent = new LinkedHashSet<>(
                actualRuntime.getOrDefault(MODULE_CORE, Collections.emptySet()));
        forbiddenPresent.retainAll(forbiddenCore);

        // then
        assertUnique(reportedLibraries, "external library ownership");
        assertUnique(reportedPlugins, "versioned plugin ownership");
        assertEquals(discoveredLibraries, new LinkedHashSet<>(reportedLibraries));
        assertEquals(discoveredPlugins, new LinkedHashSet<>(reportedPlugins));
        assertEquals(actualLibraryDeclarations,
                reportedDeclarationEvidence(report.path("libraries"), false));
        assertEquals(actualPluginDeclarations,
                reportedDeclarationEvidence(report.path("plugins"), true));
        assertEquals(actualRuntime, allowedRuntime,
                "Direct module runtime libraries changed without dependency review");
        assertTrue(forbiddenPresent.isEmpty(),
                "HTTP, reflection, and fixture YAML must stay out of core: "
                        + forbiddenPresent);
        assertTrue(invalidEntries.isEmpty(),
                "Dependency entries must have one known owner and rationale: "
                        + invalidEntries);
        List<String> scannedScripts = textElements(report.path("scannedBuildScripts"));
        List<String> scannedTypedSources = textElements(
                report.path("scannedTypedBuildLogicSources"));
        assertEquals(scripts.stream().map(PhaseFourModuleOwnershipArchitectureTest::relative)
                        .collect(Collectors.toList()), scannedScripts);
        assertEquals(typedSources.stream()
                        .map(PhaseFourModuleOwnershipArchitectureTest::relative)
                        .collect(Collectors.toList()), scannedTypedSources);
        assertEquals(scripts.size(), report.path("inventory")
                .path("buildScriptCount").asInt());
        assertEquals(digestLines(scannedScripts), report.path("inventory")
                .path("buildScriptPathIdentity").asText());
        assertEquals(typedSources.size(), report.path("inventory")
                .path("typedBuildLogicSourceCount").asInt());
        assertEquals(digestLines(scannedTypedSources), report.path("inventory")
                .path("typedBuildLogicSourcePathIdentity").asText());
        assertEquals(reportedLibraries.size(), report.path("inventory")
                .path("ownedLibraries").asInt());
        assertEquals(reportedPlugins.size(), report.path("inventory")
                .path("ownedPlugins").asInt());
    }

    @Test
    void shouldKeepBuildScriptsSmallAndModuleSourcesConventional() throws IOException {
        // given
        List<Path> scripts = buildScripts();
        Map<String, Integer> lineCounts = new LinkedHashMap<>();
        List<String> redirections = new ArrayList<>();

        // when
        for (Path script : scripts) {
            int lines = Files.readAllLines(script, StandardCharsets.UTF_8).size();
            lineCounts.put(relative(script), lines);
            if (!script.equals(PROJECT_ROOT.resolve("build.gradle"))
                    && ROOT_SOURCE_REDIRECTION.matcher(read(script)).find()) {
                redirections.add(relative(script));
            }
        }

        // then
        assertTrue(lineCounts.get("build.gradle") <= ROOT_BUILD_MAX_LINES,
                "Root build.gradle must stay declarative and at most 200 lines");
        lineCounts.forEach((path, lines) -> {
            assertTrue(lines <= HARD_BUILD_SCRIPT_MAX_LINES,
                    path + " must not become a 1,000-line build script");
            if (path.endsWith("/build.gradle") || path.endsWith("/build.gradle.kts")) {
                assertTrue(lines <= MODULE_BUILD_MAX_LINES,
                        path + " must stay at most 150 lines");
            }
        });
        assertTrue(redirections.isEmpty(),
                "Modules must use conventional local source roots: " + redirections);
    }

    private static Map<String, Set<String>> allowedModuleDag() {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        result.put(MODULE_MODEL, immutableSet());
        result.put(MODULE_CORE, immutableSet(MODULE_MODEL));
        result.put(MODULE_CONTRACTS,
                immutableSet(MODULE_MODEL, MODULE_CORE, MODULE_MAPPING));
        result.put(MODULE_MAPPING, immutableSet(MODULE_MODEL, MODULE_CORE));
        result.put(MODULE_IPFS, immutableSet(MODULE_CORE));
        result.put(MODULE_CONFORMANCE,
                immutableSet(MODULE_MODEL, MODULE_CORE,
                        MODULE_CONTRACTS, MODULE_MAPPING));
        result.put(MODULE_AGGREGATE,
                immutableSet(MODULE_MODEL, MODULE_CORE, MODULE_CONTRACTS,
                        MODULE_MAPPING, MODULE_IPFS));
        result.put(MODULE_EXAMPLES, immutableSet(MODULE_AGGREGATE));
        result.put(MODULE_BUILD_LOGIC, immutableSet());
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, String> packageRelocations() {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("blue.language.provider.NodeProviderOutcome",
                "blue.language.api.NodeProviderOutcome");
        result.put("blue.language.snapshot.BlueSnapshots",
                "blue.language.merge.BlueSnapshots");
        result.put("blue.language.snapshot.ResolvedReferenceCache",
                "blue.language.merge.ResolvedReferenceCache");
        result.put("blue.language.snapshot.ResolvedSnapshot",
                "blue.language.merge.ResolvedSnapshot");
        result.put("blue.language.api.LanguageRuntimeAccess",
                "blue.language.runtime.LanguageRuntimeAccess");
        result.put("blue.language.patching.BluePatch",
                "blue.language.snapshot.BluePatch");
        result.put("blue.language.patching.BluePatchOperation",
                "blue.language.snapshot.BluePatchOperation");
        result.put("blue.language.patching.ImmutableBluePatch",
                "blue.language.snapshot.ImmutableBluePatch");
        return Collections.unmodifiableMap(result);
    }

    private static Path projectRoot() {
        Path current = Paths.get("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve(
                    "architecture/module-ownership-1.0.json"))
                    || Files.isRegularFile(current.resolve("settings.gradle.kts"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Cannot locate Blue Language repository root");
    }

    private static JsonNode readJson(Path path) throws IOException {
        return JSON.readTree(path.toFile());
    }

    private static String read(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static String relative(Path path) {
        return PROJECT_ROOT.relativize(path).toString().replace('\\', '/');
    }

    private static Map<String, Path> moduleRoots(JsonNode manifest) {
        Map<String, Path> result = new LinkedHashMap<>();
        for (JsonNode module : manifest.path("modules")) {
            result.put(module.path("id").asText(),
                    PROJECT_ROOT.resolve(module.path("directory").asText()));
        }
        return result;
    }

    private static List<String> productionFiles(
            Map<String, Path> moduleRoots, String kind, String suffix)
            throws IOException {
        List<String> result = new ArrayList<>();
        for (String module : ALLOWED_MODULE_DAG.keySet()) {
            if (MODULE_EXAMPLES.equals(module) || MODULE_BUILD_LOGIC.equals(module)) {
                continue;
            }
            Path root = moduleRoots.get(module).resolve("src/main/" + kind);
            result.addAll(regularFiles(root, suffix));
        }
        result.sort(Comparator.naturalOrder());
        return result;
    }

    private static List<String> regularFiles(Path root, String suffix)
            throws IOException {
        if (!Files.isDirectory(root)) {
            return Collections.emptyList();
        }
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> suffix == null
                            || path.getFileName().toString().endsWith(suffix))
                    .map(PhaseFourModuleOwnershipArchitectureTest::relative)
                    .sorted()
                    .collect(Collectors.toList());
        }
    }

    private static List<String> invalidAssignments(
            JsonNode manifest, Map<String, Path> moduleRoots) {
        List<String> result = new ArrayList<>();
        for (String group : Arrays.asList("sources", "resources")) {
            for (JsonNode entry : manifest.path(group)) {
                String current = entry.path("currentPath").asText();
                String target = entry.path("targetPath").asText();
                Path owner = moduleRoots.get(entry.path("targetModule").asText());
                if (!current.equals(target)
                        || owner == null
                        || !PROJECT_ROOT.resolve(target).normalize().startsWith(owner)
                        || !Files.isRegularFile(PROJECT_ROOT.resolve(target))) {
                    result.add(current);
                }
            }
        }
        return result;
    }

    private static String packageName(Path source) throws IOException {
        Matcher matcher = PACKAGE_DECLARATION.matcher(read(source));
        assertTrue(matcher.find(), "Missing package declaration: " + relative(source));
        return matcher.group(1);
    }

    private static Map<String, Set<String>> declaredModuleDag(JsonNode manifest) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (JsonNode module : manifest.path("modules")) {
            result.put(module.path("id").asText(),
                    textSet(module.path("dependencies")));
        }
        return result;
    }

    private static Map<String, Set<String>> projectDependencyEdges(JsonNode manifest)
            throws IOException {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (JsonNode module : manifest.path("modules")) {
            String id = module.path("id").asText();
            Path build = PROJECT_ROOT.resolve(module.path("directory").asText())
                    .resolve("build.gradle");
            Set<String> edges = new LinkedHashSet<>();
            if (Files.isRegularFile(build)) {
                Matcher matcher = PROJECT_DEPENDENCY.matcher(read(build));
                while (matcher.find()) {
                    edges.add(matcher.group(1));
                }
            }
            result.put(id, edges);
        }
        return result;
    }

    private static List<String> undeclaredImportEdges(
            JsonNode manifest, Map<String, Set<String>> declared) throws IOException {
        Map<String, String> packageOwners = new HashMap<>();
        for (JsonNode source : manifest.path("sources")) {
            packageOwners.put(source.path("targetPackage").asText(),
                    source.path("targetModule").asText());
        }
        List<String> result = new ArrayList<>();
        for (JsonNode source : manifest.path("sources")) {
            String owner = source.path("targetModule").asText();
            String path = source.path("targetPath").asText();
            Matcher matcher = IMPORT_DECLARATION.matcher(read(PROJECT_ROOT.resolve(path)));
            while (matcher.find()) {
                String importedOwner = ownerForImport(matcher.group(1), packageOwners);
                if (importedOwner != null && !owner.equals(importedOwner)
                        && !declared.get(owner).contains(importedOwner)) {
                    result.add(path + " -> " + importedOwner + " via " + matcher.group(1));
                }
            }
        }
        Collections.sort(result);
        return result;
    }

    private static String ownerForImport(
            String imported, Map<String, String> packageOwners) {
        if (imported.endsWith(".*")) {
            return null;
        }
        String candidate = imported;
        while (candidate.contains(".")) {
            String owner = packageOwners.get(candidate);
            if (owner != null) {
                return owner;
            }
            candidate = candidate.substring(0, candidate.lastIndexOf('.'));
        }
        return null;
    }

    private static Set<String> publicTopLevelTypes(JsonNode manifest)
            throws IOException {
        Set<String> result = new LinkedHashSet<>();
        for (JsonNode source : manifest.path("sources")) {
            String content = read(PROJECT_ROOT.resolve(
                    source.path("targetPath").asText()));
            Matcher matcher = PUBLIC_TOP_LEVEL_TYPE.matcher(content);
            if (matcher.find()) {
                result.add(source.path("targetPackage").asText()
                        + "." + matcher.group(1));
            }
        }
        return result.stream().sorted()
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static List<String> missingRelocations(JsonNode ledger) {
        Map<String, JsonNode> byType = new HashMap<>();
        for (JsonNode entry : ledger.path("types")) {
            byType.put(entry.path("type").asText(), entry);
        }
        List<String> result = new ArrayList<>();
        PACKAGE_RELOCATIONS.forEach((previous, current) -> {
            JsonNode entry = byType.get(current);
            boolean found = false;
            if (entry != null) {
                for (JsonNode relocation : entry.path("relocationHistory")) {
                    if (previous.equals(relocation.path("from").asText())
                            && current.equals(relocation.path("to").asText())
                            && relocation.path("commit").asText()
                            .startsWith("1f79996")) {
                        found = true;
                    }
                }
            }
            if (!found) {
                result.add(previous + " -> " + current);
            }
        });
        return result;
    }

    private static Map<String, String> ownerBySource(JsonNode manifest) {
        Map<String, String> result = new HashMap<>();
        for (JsonNode source : manifest.path("sources")) {
            result.put(source.path("currentPath").asText(),
                    source.path("targetModule").asText());
        }
        return result;
    }

    private static List<Path> buildScripts() throws IOException {
        try (Stream<Path> paths = Files.walk(PROJECT_ROOT)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> BUILD_SCRIPT_NAMES.contains(
                            path.getFileName().toString()))
                    .filter(PhaseFourModuleOwnershipArchitectureTest::isOwnedBuildScript)
                    .sorted(Comparator.comparing(
                            PhaseFourModuleOwnershipArchitectureTest::relative))
                    .collect(Collectors.toList());
        }
    }

    private static boolean isOwnedBuildScript(Path script) {
        Path relative = PROJECT_ROOT.relativize(script);
        if (relative.getNameCount() == 1) {
            return true;
        }
        String first = relative.getName(0).toString();
        if (!ALLOWED_MODULE_DAG.containsKey(":" + first)) {
            return false;
        }
        for (int index = 1; index < relative.getNameCount() - 1; index++) {
            String part = relative.getName(index).toString();
            if (".gradle".equals(part) || "build".equals(part)) {
                return false;
            }
        }
        return true;
    }

    private static List<Path> typedBuildLogicSources() throws IOException {
        Path root = PROJECT_ROOT.resolve("build-logic/src/main/java");
        if (!Files.isDirectory(root)) {
            return Collections.emptyList();
        }
        try (Stream<Path> paths = Files.walk(root)) {
            List<Path> result = new ArrayList<>();
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().endsWith(".java"))
                    .sorted(Comparator.comparing(
                            PhaseFourModuleOwnershipArchitectureTest::relative))
                    .collect(Collectors.toList())) {
                String content = read(path);
                if (TYPED_LITERAL_DEPENDENCY.matcher(content).find()
                        || TYPED_COORDINATE_CONSTANT.matcher(content).find()) {
                    result.add(path);
                }
            }
            return result;
        }
    }

    private static Set<String> externalLibraries(
            List<Path> scripts, List<Path> typedSources) throws IOException {
        return externalDeclarationEvidence(scripts, typedSources).keySet()
                .stream().sorted()
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static Set<String> versionedPlugins(List<Path> scripts)
            throws IOException {
        Set<String> result = new LinkedHashSet<>();
        for (Path script : scripts) {
            Matcher matcher = VERSIONED_PLUGIN.matcher(read(script));
            while (matcher.find()) {
                result.add(matcher.group(1));
            }
        }
        return result.stream().sorted()
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static Map<String, List<String>> externalDeclarationEvidence(
            List<Path> scripts, List<Path> typedSources) throws IOException {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Path script : scripts) {
            Matcher matcher = EXTERNAL_DEPENDENCY.matcher(read(script));
            while (matcher.find()) {
                String evidence = relative(script)
                        + "|" + moduleForBuildScript(script)
                        + "|" + matcher.group(1)
                        + "|" + (matcher.group(3) == null
                        ? "managed" : matcher.group(3));
                result.computeIfAbsent(
                        matcher.group(2), ignored -> new ArrayList<>()).add(evidence);
            }
        }
        for (Path source : typedSources) {
            String content = read(source);
            String declaringProject = source.getFileName().toString()
                    .equals("RootOrchestrationPlugin.java")
                    ? ":root" : MODULE_BUILD_LOGIC;
            Matcher literal = TYPED_LITERAL_DEPENDENCY.matcher(content);
            while (literal.find()) {
                String evidence = relative(source)
                        + "|" + declaringProject
                        + "|" + typedConfiguration(literal.group(1), null)
                        + "|" + (literal.group(3) == null
                        ? "managed" : literal.group(3));
                result.computeIfAbsent(
                        literal.group(2), ignored -> new ArrayList<>()).add(evidence);
            }
            Matcher constant = TYPED_COORDINATE_CONSTANT.matcher(content);
            while (constant.find()) {
                String evidence = relative(source)
                        + "|" + MODULE_BUILD_LOGIC
                        + "|" + typedConfiguration("", constant.group(1))
                        + "|" + (constant.group(3) == null
                        ? "managed" : constant.group(3));
                result.computeIfAbsent(
                        constant.group(2), ignored -> new ArrayList<>()).add(evidence);
            }
        }
        return sortedEvidence(result);
    }

    private static String typedConfiguration(
            String expression, String coordinateName) {
        if (coordinateName != null) {
            return coordinateName.contains("LAUNCHER")
                    ? "testRuntimeOnly" : "testImplementation";
        }
        String normalized = expression.trim().replace("\"", "")
                .replace("'", "");
        if (normalized.contains("TEST_RUNTIME_ONLY")) {
            return "testRuntimeOnly";
        }
        if (normalized.contains("TEST_IMPLEMENTATION")) {
            return "testImplementation";
        }
        return normalized;
    }

    private static Map<String, List<String>> pluginDeclarationEvidence(
            List<Path> scripts) throws IOException {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Path script : scripts) {
            Matcher matcher = VERSIONED_PLUGIN.matcher(read(script));
            while (matcher.find()) {
                String evidence = relative(script)
                        + "|" + moduleForBuildScript(script)
                        + "|" + matcher.group(2);
                result.computeIfAbsent(
                        matcher.group(1), ignored -> new ArrayList<>()).add(evidence);
            }
        }
        return sortedEvidence(result);
    }

    private static Map<String, List<String>> reportedDeclarationEvidence(
            JsonNode entries, boolean plugin) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (JsonNode entry : entries) {
            List<String> evidence = new ArrayList<>();
            for (JsonNode declaration : entry.path("declarations")) {
                String value = declaration.path("path").asText()
                        + "|" + declaration.path("declaringProject").asText()
                        + "|" + (plugin
                        ? declaration.path("version").asText()
                        : declaration.path("configuration").asText()
                        + "|" + declaration.path("declaredVersion").asText());
                evidence.add(value);
            }
            result.put(entry.path("component").asText(), evidence);
        }
        return sortedEvidence(result);
    }

    private static Map<String, List<String>> sortedEvidence(
            Map<String, List<String>> evidence) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        evidence.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    List<String> values = new ArrayList<>(entry.getValue());
                    Collections.sort(values);
                    result.put(entry.getKey(), values);
                });
        return result;
    }

    private static Map<String, Set<String>> runtimeLibrariesByModule(
            List<Path> scripts) throws IOException {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (String module : Arrays.asList(
                MODULE_MODEL, MODULE_CORE, MODULE_CONTRACTS, MODULE_MAPPING,
                MODULE_IPFS, MODULE_CONFORMANCE, MODULE_AGGREGATE)) {
            result.put(module, new LinkedHashSet<>());
        }
        for (Path script : scripts) {
            String module = moduleForBuildScript(script);
            if (!result.containsKey(module)) {
                continue;
            }
            Matcher matcher = EXTERNAL_DEPENDENCY.matcher(read(script));
            while (matcher.find()) {
                String configuration = matcher.group(1);
                if (!configuration.startsWith("test")
                        && !configuration.startsWith("jmh")
                        && !"classpath".equals(configuration)) {
                    result.get(module).add(matcher.group(2));
                }
            }
        }
        return result;
    }

    private static String moduleForBuildScript(Path script) {
        Path relative = PROJECT_ROOT.relativize(script);
        if (relative.getNameCount() == 1) {
            return ":root";
        }
        String directory = relative.getName(0).toString();
        for (Map.Entry<String, Set<String>> entry : ALLOWED_MODULE_DAG.entrySet()) {
            if (entry.getKey().substring(1).equals(directory)) {
                return entry.getKey();
            }
        }
        return ":" + directory;
    }

    private static void validateDependencyEntries(
            JsonNode entries, Set<String> knownModules, List<String> invalid) {
        for (JsonNode entry : entries) {
            if (entry.path("component").asText().trim().isEmpty()
                    || entry.path("currentVersion").asText().trim().isEmpty()
                    || entry.path("reason").asText().trim().isEmpty()
                    || !knownModules.contains(entry.path("owner").asText())) {
                invalid.add(entry.path("component").asText());
            }
        }
    }

    private static Map<String, Set<String>> stringSetFields(JsonNode object) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        object.fields().forEachRemaining(entry ->
                result.put(entry.getKey(), textSet(entry.getValue())));
        return result;
    }

    private static List<String> textValues(JsonNode array, String fieldName) {
        List<String> result = new ArrayList<>();
        for (JsonNode entry : array) {
            result.add(entry.path(fieldName).asText());
        }
        return result;
    }

    private static List<String> textElements(JsonNode array) {
        List<String> result = new ArrayList<>();
        for (JsonNode entry : array) {
            result.add(entry.asText());
        }
        return result;
    }

    private static Set<String> textSet(JsonNode array) {
        Set<String> result = new LinkedHashSet<>();
        for (JsonNode entry : array) {
            result.add(entry.asText());
        }
        return result;
    }

    private static Map<String, Integer> integerFields(JsonNode object) {
        Map<String, Integer> result = new LinkedHashMap<>();
        object.fields().forEachRemaining(entry ->
                result.put(entry.getKey(), entry.getValue().asInt()));
        return result;
    }

    private static Set<String> publishedModules(JsonNode manifest) {
        Set<String> result = new LinkedHashSet<>();
        for (JsonNode module : manifest.path("modules")) {
            if (module.path("published").asBoolean()) {
                result.add(module.path("id").asText());
            }
        }
        return result;
    }

    private static String digestLines(List<String> values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                digest.update(value.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            }
            StringBuilder hex = new StringBuilder("sha256:");
            for (byte value : digest.digest()) {
                hex.append(String.format("%02x", value & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void assertUniqueAndSorted(
            List<String> values, String subject) {
        assertUnique(values, subject);
        List<String> sorted = new ArrayList<>(values);
        sorted.sort(Comparator.naturalOrder());
        assertEquals(sorted, values, subject + " must be deterministic");
    }

    private static void assertUnique(List<String> values, String subject) {
        assertEquals(values.size(), new LinkedHashSet<>(values).size(),
                subject + " contains duplicate entries");
    }

    private static List<String> cyclesIn(Map<String, Set<String>> graph) {
        List<String> cycles = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Set<String> active = new HashSet<>();
        Deque<String> path = new ArrayDeque<>();
        for (String node : graph.keySet()) {
            findCycles(node, graph, visited, active, path, cycles);
        }
        return cycles;
    }

    private static void findCycles(
            String node,
            Map<String, Set<String>> graph,
            Set<String> visited,
            Set<String> active,
            Deque<String> path,
            List<String> cycles) {
        if (active.contains(node)) {
            cycles.add(String.join(" -> ", path) + " -> " + node);
            return;
        }
        if (!visited.add(node)) {
            return;
        }
        active.add(node);
        path.addLast(node);
        for (String dependency : graph.getOrDefault(node, Collections.emptySet())) {
            findCycles(dependency, graph, visited, active, path, cycles);
        }
        path.removeLast();
        active.remove(node);
    }

    @SafeVarargs
    private static <T> Set<T> immutableSet(T... values) {
        return Collections.unmodifiableSet(
                new LinkedHashSet<>(Arrays.asList(values)));
    }
}
