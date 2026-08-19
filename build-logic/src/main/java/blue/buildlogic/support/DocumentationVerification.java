package blue.buildlogic.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.gradle.api.GradleException;

/** Pure deterministic analysis behind the documentation release gate. */
public final class DocumentationVerification {

    public static final String SCHEMA = "blue-language-java-documentation-verification/1.0";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern INLINE_LINK = Pattern.compile(
            "!?\\[[^]\\n]*]\\((?:<([^>]+)>|([^\\s)]+))(?:\\s+\"[^\"]*\")?\\)");
    private static final Pattern REFERENCE_LINK = Pattern.compile(
            "(?m)^\\s*\\[[^]]+]:\\s*(?:<([^>]+)>|([^\\s]+))");
    private static final Pattern JAVA_FENCE = Pattern.compile(
            "(?ms)^```java[ \\t]*\\R(.*?)^```[ \\t]*$");
    private static final Pattern EXAMPLE_BINDING = Pattern.compile(
            "(?s)<!--\\s*blue-example:\\s*([^#\\s]+)#([A-Za-z0-9_-]+)\\s*-->\\s*$");
    private static final Pattern EXAMPLE_RUN_METHOD = Pattern.compile(
            "(?m)^\\s*public\\s+static\\s+[A-Za-z_$][A-Za-z0-9_$.<>?, \\t]*"
                    + "\\s+run\\s*\\(\\s*\\)");
    private static final Pattern EXAMPLE_MAIN_METHOD = Pattern.compile(
            "(?m)^\\s*public\\s+static\\s+void\\s+main\\s*\\(\\s*String\\s*\\[\\s*]"
                    + "\\s+[A-Za-z_$][A-Za-z0-9_$]*\\s*\\)");
    private static final Pattern SHA_256 = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final int README_LINE_LIMIT = 500;
    private static final int ROOT_BUILD_LINE_LIMIT = 350;
    private static final int REQUIRED_EXAMPLE_COUNT = 16;

    private static final List<String> REQUIRED_DOCUMENTS = Collections.unmodifiableList(
            java.util.Arrays.asList(
                    "ARCHITECTURE.md",
                    "CONTRIBUTING.md",
                    "README.md",
                    "docs/start-here.md",
                    "docs/architecture/overview.md",
                    "docs/architecture/modules-and-dependencies.md",
                    "docs/architecture/language-pipeline.md",
                    "docs/architecture/contracts-pipeline.md",
                    "docs/architecture/immutability-and-runtime-state.md",
                    "docs/architecture/provider-and-fragment-model.md",
                    "docs/architecture/conformance-and-release.md",
                    "docs/guides/nodes-graphs-and-blueids.md",
                    "docs/guides/preprocessing-and-blue-directive.md",
                    "docs/guides/types-and-specialization.md",
                    "docs/guides/expand-collapse-resolve-canonicalize-minimize.md",
                    "docs/guides/lists-and-incremental-identity.md",
                    "docs/guides/schema-and-unconstrained-fields.md",
                    "docs/guides/providers-and-evidence.md",
                    "docs/guides/cyclic-sets.md",
                    "docs/guides/immutable-snapshots.md",
                    "docs/guides/patching-and-generalization.md",
                    "docs/guides/contracts-processing.md",
                    "docs/guides/custom-runtime-types.md",
                    "docs/guides/events-updates-checkpoints-and-lifecycle.md",
                    "docs/guides/gas-and-runtime-work.md",
                    "docs/guides/fragmented-processing.md",
                    "docs/reference/public-api.md",
                    "docs/reference/packages.md",
                    "docs/reference/runtime-spi.md",
                    "docs/reference/statuses-and-diagnostics.md",
                    "docs/reference/gas-counters.md",
                    "docs/reference/host-metrics.md",
                    "docs/reference/conformance-fixtures.md",
                    "docs/adr/0001-one-blueid-two-calculation-paths.md",
                    "docs/adr/0002-specialization-vs-expansion.md",
                    "docs/adr/0003-canonicalization-vs-minimization.md",
                    "docs/adr/0004-one-root-two-input-contracts.md",
                    "docs/adr/0005-fragments-are-ordinary-blue-nodes.md",
                    "docs/adr/0006-runtime-extension-boundary.md",
                    "docs/adr/0007-module-boundaries.md"));

    private static final Map<String, Pattern> FORBIDDEN_TERMS = forbiddenTerms();

    private DocumentationVerification() {}

    /** Analyzes authored and generated documentation without throwing for quality violations. */
    public static Map<String, Object> analyze(Inputs inputs) {
        Path root = inputs.repositoryRoot.toAbsolutePath().normalize();
        Map<String, Path> markdown = markdown(root, inputs.documentationFiles);
        JavaSourceQuality.Analysis sources =
                JavaSourceQuality.analyze(root, inputs.productionSources);
        List<Violation> violations = new ArrayList<>();

        checkRequiredDocuments(root, violations);
        checkInternalLinks(root, markdown, violations);
        checkForbiddenTerms(root, markdown, violations);
        checkJavaSnippets(root, markdown, inputs, violations);
        checkPackageDocumentation(sources, violations);
        checkGeneratedReferences(root, inputs.generatedDocumentationDirectory, violations);
        IdentityStatus identities = checkIdentities(inputs, violations);
        checkRemovedApis(markdown, inputs.relocationLedger, violations);
        ExampleStatus examples = checkExamples(root, inputs.exampleSources, inputs.exampleTests,
                violations);
        checkLineBudget(root.resolve("README.md"), README_LINE_LIMIT, "README_LINE_LIMIT",
                violations);
        checkLineBudget(root.resolve("build.gradle"), ROOT_BUILD_LINE_LIMIT,
                "ROOT_BUILD_LINE_LIMIT", violations);

        violations.sort(Comparator.comparing(Violation::code)
                .thenComparing(Violation::path)
                .thenComparing(Violation::detail));
        List<Map<String, Object>> encoded = new ArrayList<>();
        for (Violation violation : violations) {
            encoded.add(violation.toMap());
        }

        Map<String, Object> packages = new TreeMap<>();
        packages.put("documentedPublicPackageCount",
                sources.publicTypesByPackage().size() - sources.missingPackageInfo().size());
        packages.put("missingPackageInfo", sources.missingPackageInfo());
        packages.put("publicPackageCount", sources.publicTypesByPackage().size());
        packages.put("publicTypeCount", sources.publicTypeCount());

        Map<String, Object> lineBudgets = new TreeMap<>();
        lineBudgets.put("readmeLimit", README_LINE_LIMIT);
        lineBudgets.put("readmeLines", lines(root.resolve("README.md")));
        lineBudgets.put("rootBuildLimit", ROOT_BUILD_LINE_LIMIT);
        lineBudgets.put("rootBuildLines", lines(root.resolve("build.gradle")));

        Map<String, Object> report = new TreeMap<>();
        report.put("checks", checks(encoded));
        report.put("examples", examples.toMap());
        report.put("generatedReferenceCount", DocumentationReferences.OUTPUT_PATHS.size());
        report.put("identities", identities.toMap());
        report.put("lineBudgets", lineBudgets);
        report.put("packages", packages);
        report.put("requiredDocumentCount", REQUIRED_DOCUMENTS.size());
        report.put("schema", SCHEMA);
        report.put("valid", violations.isEmpty());
        report.put("violationCount", violations.size());
        report.put("violations", encoded);
        return report;
    }

    private static void checkRequiredDocuments(Path root, List<Violation> violations) {
        for (String document : REQUIRED_DOCUMENTS) {
            if (!Files.isRegularFile(root.resolve(document))) {
                violations.add(new Violation(
                        "MISSING_DOCUMENT", document, "required documentation file is absent"));
            }
        }
    }

    private static void checkInternalLinks(
            Path root, Map<String, Path> markdown, List<Violation> violations) {
        for (Map.Entry<String, Path> entry : markdown.entrySet()) {
            String content = read(entry.getValue(), "documentation link input");
            checkLinks(root, entry.getKey(), entry.getValue(), content, INLINE_LINK, violations);
            checkLinks(root, entry.getKey(), entry.getValue(), content, REFERENCE_LINK, violations);
        }
    }

    private static void checkLinks(
            Path root,
            String sourcePath,
            Path source,
            String content,
            Pattern pattern,
            List<Violation> violations) {
        Matcher matcher = pattern.matcher(content);
        while (matcher.find()) {
            String target = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            if (target == null || externalOrAnchor(target)) {
                continue;
            }
            String pathPart = target.split("[#?]", 2)[0];
            if (pathPart.isBlank() || pathPart.contains("${")) {
                continue;
            }
            String decoded;
            try {
                decoded = URLDecoder.decode(pathPart, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException exception) {
                violations.add(new Violation(
                        "BROKEN_INTERNAL_LINK", sourcePath, "invalid encoded target " + target));
                continue;
            }
            Path resolved = decoded.startsWith("/")
                    ? root.resolve(decoded.substring(1)).normalize()
                    : source.getParent().resolve(decoded).normalize();
            if (!resolved.startsWith(root) || !Files.exists(resolved)) {
                violations.add(new Violation(
                        "BROKEN_INTERNAL_LINK", sourcePath, "unresolved target " + target));
            }
        }
    }

    private static void checkForbiddenTerms(
            Path root, Map<String, Path> markdown, List<Violation> violations) {
        for (Map.Entry<String, Path> entry : markdown.entrySet()) {
            if (!primaryDocumentation(entry.getKey())) {
                continue;
            }
            String content = read(entry.getValue(), "terminology input");
            for (Map.Entry<String, Pattern> term : FORBIDDEN_TERMS.entrySet()) {
                if (term.getValue().matcher(content).find()) {
                    violations.add(new Violation(
                            "FORBIDDEN_PRIMARY_TERM", entry.getKey(), term.getKey()));
                }
            }
        }
    }

    private static void checkJavaSnippets(
            Path root,
            Map<String, Path> markdown,
            Inputs inputs,
            List<Violation> violations) {
        Set<Path> compiledSources = new TreeSet<>(Comparator.comparing(Path::toString));
        addNormalized(compiledSources, inputs.productionSources);
        addNormalized(compiledSources, inputs.exampleSources);
        addNormalized(compiledSources, inputs.exampleTests);
        for (Map.Entry<String, Path> document : markdown.entrySet()) {
            String content = read(document.getValue(), "Java snippet input");
            Matcher fence = JAVA_FENCE.matcher(content);
            while (fence.find()) {
                Matcher binding = EXAMPLE_BINDING.matcher(content.substring(0, fence.start()));
                if (!binding.find()) {
                    violations.add(new Violation(
                            "UNBOUND_JAVA_SNIPPET", document.getKey(),
                            "Java fence must follow <!-- blue-example: path#region -->"));
                    continue;
                }
                Path source = root.resolve(binding.group(1)).normalize().toAbsolutePath();
                if (!source.startsWith(root) || !compiledSources.contains(source)
                        || !Files.isRegularFile(source)) {
                    violations.add(new Violation(
                            "JAVA_SNIPPET_SOURCE_MISSING", document.getKey(), binding.group(1)));
                    continue;
                }
                String region = sourceRegion(source, binding.group(2));
                if (region == null) {
                    violations.add(new Violation(
                            "JAVA_SNIPPET_REGION_MISSING", document.getKey(),
                            binding.group(1) + "#" + binding.group(2)));
                    continue;
                }
                if (!normalizeSnippet(region).equals(normalizeSnippet(fence.group(1)))) {
                    violations.add(new Violation(
                            "JAVA_SNIPPET_DRIFT", document.getKey(),
                            binding.group(1) + "#" + binding.group(2)));
                }
            }
        }
    }

    private static void addNormalized(Set<Path> output, Collection<Path> inputs) {
        for (Path input : inputs) {
            if (Files.isRegularFile(input) && input.getFileName().toString().endsWith(".java")) {
                output.add(input.toAbsolutePath().normalize());
            }
        }
    }

    private static String sourceRegion(Path source, String regionName) {
        String start = "// tag::" + regionName + "[]";
        String end = "// end::" + regionName + "[]";
        String content = read(source, "bound Java example source");
        int startIndex = content.indexOf(start);
        if (startIndex < 0) {
            return null;
        }
        int contentStart = content.indexOf('\n', startIndex + start.length());
        if (contentStart < 0) {
            return null;
        }
        int endIndex = content.indexOf(end, contentStart + 1);
        if (endIndex < 0 || content.indexOf(start, startIndex + start.length()) >= 0
                && content.indexOf(start, startIndex + start.length()) < endIndex) {
            return null;
        }
        return content.substring(contentStart + 1, endIndex);
    }

    private static String normalizeSnippet(String snippet) {
        return snippet.replace("\r\n", "\n").replace('\r', '\n').stripTrailing();
    }

    private static void checkPackageDocumentation(
            JavaSourceQuality.Analysis source, List<Violation> violations) {
        for (String packageName : source.missingPackageInfo()) {
            violations.add(new Violation(
                    "MISSING_PACKAGE_INFO", packageName, "public package has no package-info.java"));
        }
        for (String packageName : source.publicTypesByPackage().keySet()) {
            for (String segment : packageName.split("\\.")) {
                if (segment.equals("utils") || segment.equals("misc") || segment.equals("helpers")) {
                    violations.add(new Violation(
                            "FORBIDDEN_PUBLIC_PACKAGE_NAME", packageName,
                            "public package uses reserved catch-all segment " + segment));
                }
            }
        }
    }

    private static void checkGeneratedReferences(
            Path root, Path generatedDirectory, List<Violation> violations) {
        for (String relative : DocumentationReferences.OUTPUT_PATHS) {
            Path generated = generatedDirectory.resolve(relative);
            Path tracked = root.resolve("docs").resolve(relative);
            if (!Files.isRegularFile(generated)) {
                violations.add(new Violation(
                        "GENERATED_REFERENCE_MISSING", "docs/" + relative,
                        "generator did not produce expected output"));
                continue;
            }
            if (!Files.isRegularFile(tracked)) {
                violations.add(new Violation(
                        "GENERATED_REFERENCE_NOT_TRACKED", "docs/" + relative,
                        "generated reference has not been checked in"));
                continue;
            }
            try {
                if (Files.mismatch(generated, tracked) != -1L) {
                    violations.add(new Violation(
                            "GENERATED_REFERENCE_DRIFT", "docs/" + relative,
                            "tracked bytes differ from deterministic generator output"));
                }
            } catch (IOException exception) {
                throw new GradleException("Cannot compare generated documentation " + relative,
                        exception);
            }
        }
    }

    private static IdentityStatus checkIdentities(Inputs inputs, List<Violation> violations) {
        JsonNode report = json(inputs.releaseConformanceReport, "release conformance report");
        if (!"blue-language-java-release-conformance-report/1.0"
                .equals(report.path("schema").asText())) {
            violations.add(new Violation(
                    "CONFORMANCE_SCHEMA_MISMATCH", "release-conformance.json",
                    "unexpected release conformance schema"));
        }
        int languageFixtures = 0;
        int contractsFixtures = 0;
        boolean allPassed = true;
        for (JsonNode fixture : report.path("fixtures")) {
            if ("language".equals(fixture.path("suite").asText())) languageFixtures++;
            if ("contracts".equals(fixture.path("suite").asText())) contractsFixtures++;
            allPassed &= "PASS".equals(fixture.path("status").asText());
        }
        if (languageFixtures != inputs.expectedLanguageFixtures) {
            violations.add(new Violation(
                    "LANGUAGE_FIXTURE_COUNT", "release-conformance.json",
                    "expected " + inputs.expectedLanguageFixtures + " but found "
                            + languageFixtures));
        }
        if (contractsFixtures != inputs.expectedContractsFixtures) {
            violations.add(new Violation(
                    "CONTRACTS_FIXTURE_COUNT", "release-conformance.json",
                    "expected " + inputs.expectedContractsFixtures + " but found "
                            + contractsFixtures));
        }
        if (!allPassed) {
            violations.add(new Violation(
                    "CONFORMANCE_FIXTURE_FAILURE", "release-conformance.json",
                    "one or more release fixtures did not pass"));
        }

        String actualLanguage = bareHash(inputs.languageSpecification);
        String actualContracts = bareHash(inputs.contractsSpecification);
        String reportedLanguage = report.path("specifications").path("languageSha256").asText();
        String reportedContracts = report.path("specifications").path("contractsSha256").asText();
        if (!actualLanguage.equals(reportedLanguage)) {
            violations.add(new Violation(
                    "LANGUAGE_SPEC_IDENTITY_DRIFT", inputs.languageSpecification.toString(),
                    "release report hash does not match specification bytes"));
        }
        if (!actualContracts.equals(reportedContracts)) {
            violations.add(new Violation(
                    "CONTRACTS_SPEC_IDENTITY_DRIFT", inputs.contractsSpecification.toString(),
                    "release report hash does not match specification bytes"));
        }
        boolean packageIdentitiesValid = true;
        java.util.Iterator<Map.Entry<String, JsonNode>> packages = report.path("packages").fields();
        int packageIdentityCount = 0;
        while (packages.hasNext()) {
            Map.Entry<String, JsonNode> entry = packages.next();
            packageIdentityCount++;
            if (!SHA_256.matcher(entry.getValue().asText()).matches()) {
                packageIdentitiesValid = false;
                violations.add(new Violation(
                        "PACKAGE_IDENTITY_INVALID", entry.getKey(), entry.getValue().asText()));
            }
        }
        if (packageIdentityCount == 0) {
            packageIdentitiesValid = false;
            violations.add(new Violation(
                    "PACKAGE_IDENTITIES_MISSING", "release-conformance.json",
                    "release report contains no package identities"));
        }
        String contractsReleaseIdentity = report.path("release")
                .path("contractsReleaseIdentity").asText();
        String contractsReleasePackage = report.path("packages")
                .path("contractsRelease").asText();
        if (!SHA_256.matcher(contractsReleaseIdentity).matches()
                || !SHA_256.matcher(contractsReleasePackage).matches()
                || !contractsReleaseIdentity.equals(
                        contractsReleasePackage)) {
            packageIdentitiesValid = false;
            violations.add(new Violation(
                    "CONTRACTS_RELEASE_IDENTITY_INVALID",
                    "release-conformance.json",
                    "release.contractsReleaseIdentity and "
                            + "packages.contractsRelease must be the same "
                            + "SHA-256 identity"));
        }
        return new IdentityStatus(
                languageFixtures,
                contractsFixtures,
                actualLanguage.equals(reportedLanguage),
                actualContracts.equals(reportedContracts),
                packageIdentitiesValid,
                allPassed);
    }

    private static void checkRemovedApis(
            Map<String, Path> markdown, Path ledger, List<Violation> violations) {
        JsonNode root = json(ledger, "module API relocation ledger");
        Set<String> removedTypes = new TreeSet<>();
        for (JsonNode type : root.path("types")) {
            if (!"internal-type-removed-from-public-surface"
                    .equals(type.path("classification").asText())) {
                continue;
            }
            removedTypes.add(type.path("type").asText());
            for (JsonNode previous : type.path("previousTypes")) {
                removedTypes.add(previous.asText());
            }
        }
        for (Map.Entry<String, Path> document : markdown.entrySet()) {
            if (!primaryDocumentation(document.getKey())) {
                continue;
            }
            String content = read(document.getValue(), "removed API documentation input");
            if (content.contains(DocumentationReferences.MARKER)) {
                continue;
            }
            for (String removedType : removedTypes) {
                if (!removedType.isBlank() && content.contains(removedType)) {
                    violations.add(new Violation(
                            "REMOVED_PUBLIC_API_REFERENCE", document.getKey(), removedType));
                }
            }
        }
    }

    private static ExampleStatus checkExamples(
            Path root,
            Collection<Path> exampleSources,
            Collection<Path> exampleTests,
            List<Violation> violations) {
        List<Path> sources = runnableExamples(exampleSources);
        List<Path> tests = regularJava(exampleTests);
        if (sources.size() < REQUIRED_EXAMPLE_COUNT) {
            violations.add(new Violation(
                    "INSUFFICIENT_RUNNABLE_EXAMPLES", "examples/src/main/java",
                    "expected at least " + REQUIRED_EXAMPLE_COUNT + " example classes but found "
                            + sources.size()));
        }
        StringBuilder testContent = new StringBuilder();
        for (Path test : tests) {
            testContent.append(read(test, "example test source")).append('\n');
        }
        List<String> untested = new ArrayList<>();
        for (Path source : sources) {
            String file = source.getFileName().toString();
            String type = file.substring(0, file.length() - ".java".length());
            if (!testContent.toString().contains(type)) {
                untested.add(relative(root, source));
                violations.add(new Violation(
                        "UNTESTED_RUNNABLE_EXAMPLE", relative(root, source),
                        "no example test names the example type"));
            }
        }
        if (tests.isEmpty()) {
            violations.add(new Violation(
                    "MISSING_EXAMPLE_TESTS", "examples/src/test/java",
                    "runnable examples have no automated tests"));
        }
        return new ExampleStatus(sources.size(), tests.size(), untested);
    }

    private static void checkLineBudget(
            Path file, int limit, String code, List<Violation> violations) {
        int count = lines(file);
        if (count < 0) {
            violations.add(new Violation(code, file.toString(), "required file is missing"));
        } else if (count > limit) {
            violations.add(new Violation(
                    code, file.getFileName().toString(),
                    count + " lines exceeds limit " + limit));
        }
    }

    private static Map<String, Object> checks(List<Map<String, Object>> violations) {
        Map<String, Integer> counts = new TreeMap<>();
        for (Map<String, Object> violation : violations) {
            String code = (String) violation.get("code");
            counts.put(code, counts.getOrDefault(code, 0) + 1);
        }
        Map<String, Object> checks = new TreeMap<>();
        checks.put("failureCountsByCode", counts);
        checks.put("internalLinks", !counts.containsKey("BROKEN_INTERNAL_LINK"));
        checks.put("generatedReferences", counts.keySet().stream()
                .noneMatch(value -> value.startsWith("GENERATED_REFERENCE_")));
        checks.put("packageDocumentation", !counts.containsKey("MISSING_PACKAGE_INFO")
                && !counts.containsKey("FORBIDDEN_PUBLIC_PACKAGE_NAME"));
        checks.put("terminology", !counts.containsKey("FORBIDDEN_PRIMARY_TERM"));
        checks.put("removedApiNames", !counts.containsKey("REMOVED_PUBLIC_API_REFERENCE"));
        checks.put("javaSnippets", !counts.containsKey("UNBOUND_JAVA_SNIPPET")
                && !counts.containsKey("JAVA_SNIPPET_SOURCE_MISSING")
                && !counts.containsKey("JAVA_SNIPPET_REGION_MISSING")
                && !counts.containsKey("JAVA_SNIPPET_DRIFT"));
        return checks;
    }

    private static Map<String, Path> markdown(Path root, Collection<Path> files) {
        Map<String, Path> markdown = new TreeMap<>();
        for (Path file : files) {
            if (Files.isRegularFile(file)
                    && file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".md")) {
                markdown.put(relative(root, file), file.toAbsolutePath().normalize());
            }
        }
        return markdown;
    }

    private static List<Path> regularJava(Collection<Path> inputs) {
        List<Path> files = new ArrayList<>();
        for (Path input : inputs) {
            if (Files.isRegularFile(input) && input.getFileName().toString().endsWith(".java")) {
                files.add(input);
            }
        }
        files.sort(Comparator.comparing(path -> path.toAbsolutePath().normalize().toString()));
        return files;
    }

    private static List<Path> runnableExamples(Collection<Path> inputs) {
        List<Path> files = regularJava(inputs);
        files.removeIf(file -> {
            String content = read(file, "runnable example source");
            return !EXAMPLE_RUN_METHOD.matcher(content).find()
                    || !EXAMPLE_MAIN_METHOD.matcher(content).find();
        });
        return files;
    }

    private static boolean primaryDocumentation(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return !lower.contains("migration")
                && !lower.contains("historical")
                && !lower.contains("history")
                && !lower.contains("legacy")
                && !lower.contains("clarification");
    }

    private static boolean externalOrAnchor(String target) {
        String lower = target.toLowerCase(Locale.ROOT);
        return target.startsWith("#")
                || lower.startsWith("http://")
                || lower.startsWith("https://")
                || lower.startsWith("mailto:")
                || lower.startsWith("data:")
                || lower.startsWith("javascript:");
    }

    private static Map<String, Pattern> forbiddenTerms() {
        Map<String, Pattern> terms = new LinkedHashMap<>();
        int flags = Pattern.CASE_INSENSITIVE | Pattern.MULTILINE;
        terms.put("Semantic BlueId", Pattern.compile("\\bSemantic\\s+BlueId\\b", flags));
        terms.put("calculateSemanticBlueId", Pattern.compile("\\bcalculateSemanticBlueId\\b", flags));
        terms.put("canonical content means minimized content", Pattern.compile(
                "canonical\\s+content\\s+(?:is|means)\\s+minimi[sz]ed\\s+content", flags));
        terms.put("NodeExtender", Pattern.compile("\\bNodeExtender\\b", flags));
        terms.put("type extension for specialization", Pattern.compile(
                "type\\s+extension.{0,80}speciali[sz]ation|speciali[sz]ation.{0,80}type\\s+extension",
                flags));
        terms.put("implicit Default Blue directive", Pattern.compile(
                "implicit.{0,40}Default\\s+Blue\\s+directive", flags));
        terms.put("Blue document is a tree", Pattern.compile(
                "Blue\\s+document\\s+is\\s+(?:a\\s+)?tree", flags));
        terms.put("public transitive effect log", Pattern.compile(
                "(?:public\\s+)?transitive\\s+effect\\s+log", flags));
        terms.put("Embedded Child Commit", Pattern.compile("Embedded\\s+Child\\s+Commit", flags));
        terms.put("deliveryOccurrence input", Pattern.compile("\\bdeliveryOccurrence\\b", flags));
        return Collections.unmodifiableMap(terms);
    }

    private static int lines(Path file) {
        if (!Files.isRegularFile(file)) {
            return -1;
        }
        try (java.util.stream.Stream<String> stream = Files.lines(file, StandardCharsets.UTF_8)) {
            return (int) stream.count();
        } catch (IOException exception) {
            throw new GradleException("Cannot count documentation lines in " + file, exception);
        }
    }

    private static String bareHash(Path file) {
        String identity = DeterministicHashing.sha256(file);
        return identity.substring("sha256:".length());
    }

    private static String read(Path file, String description) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new GradleException("Cannot read " + description + ": " + file, exception);
        }
    }

    private static JsonNode json(Path file, String description) {
        try {
            return JSON.readTree(file.toFile());
        } catch (IOException exception) {
            throw new GradleException("Cannot read " + description + ": " + file, exception);
        }
    }

    private static String relative(Path root, Path file) {
        Path normalized = file.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) {
            return normalized.toString().replace('\\', '/');
        }
        return root.relativize(normalized).toString().replace('\\', '/');
    }

    /** Immutable input bundle that keeps the pure analyzer independent of Gradle task APIs. */
    public static final class Inputs {

        private final Path repositoryRoot;
        private final Collection<Path> documentationFiles;
        private final Path generatedDocumentationDirectory;
        private final Collection<Path> productionSources;
        private final Collection<Path> exampleSources;
        private final Collection<Path> exampleTests;
        private final Path releaseConformanceReport;
        private final Path languageSpecification;
        private final Path contractsSpecification;
        private final Path relocationLedger;
        private final int expectedLanguageFixtures;
        private final int expectedContractsFixtures;

        public Inputs(
                Path repositoryRoot,
                Collection<Path> documentationFiles,
                Path generatedDocumentationDirectory,
                Collection<Path> productionSources,
                Collection<Path> exampleSources,
                Collection<Path> exampleTests,
                Path releaseConformanceReport,
                Path languageSpecification,
                Path contractsSpecification,
                Path relocationLedger,
                int expectedLanguageFixtures,
                int expectedContractsFixtures) {
            this.repositoryRoot = repositoryRoot;
            this.documentationFiles = documentationFiles;
            this.generatedDocumentationDirectory = generatedDocumentationDirectory;
            this.productionSources = productionSources;
            this.exampleSources = exampleSources;
            this.exampleTests = exampleTests;
            this.releaseConformanceReport = releaseConformanceReport;
            this.languageSpecification = languageSpecification;
            this.contractsSpecification = contractsSpecification;
            this.relocationLedger = relocationLedger;
            this.expectedLanguageFixtures = expectedLanguageFixtures;
            this.expectedContractsFixtures = expectedContractsFixtures;
        }
    }

    private static final class Violation {

        private final String code;
        private final String path;
        private final String detail;

        private Violation(String code, String path, String detail) {
            this.code = code;
            this.path = path;
            this.detail = detail;
        }

        private String code() { return code; }
        private String path() { return path; }
        private String detail() { return detail; }

        private Map<String, Object> toMap() {
            Map<String, Object> value = new TreeMap<>();
            value.put("code", code);
            value.put("detail", detail);
            value.put("path", path);
            return value;
        }
    }

    private static final class IdentityStatus {

        private final int languageFixtures;
        private final int contractsFixtures;
        private final boolean languageSpecBound;
        private final boolean contractsSpecBound;
        private final boolean packageIdentitiesValid;
        private final boolean allFixturesPassed;

        private IdentityStatus(
                int languageFixtures,
                int contractsFixtures,
                boolean languageSpecBound,
                boolean contractsSpecBound,
                boolean packageIdentitiesValid,
                boolean allFixturesPassed) {
            this.languageFixtures = languageFixtures;
            this.contractsFixtures = contractsFixtures;
            this.languageSpecBound = languageSpecBound;
            this.contractsSpecBound = contractsSpecBound;
            this.packageIdentitiesValid = packageIdentitiesValid;
            this.allFixturesPassed = allFixturesPassed;
        }

        private Map<String, Object> toMap() {
            Map<String, Object> value = new TreeMap<>();
            value.put("allFixturesPassed", allFixturesPassed);
            value.put("contractsFixtureCount", contractsFixtures);
            value.put("contractsSpecificationBound", contractsSpecBound);
            value.put("exactlyBound", allFixturesPassed && languageSpecBound
                    && contractsSpecBound && packageIdentitiesValid);
            value.put("languageFixtureCount", languageFixtures);
            value.put("languageSpecificationBound", languageSpecBound);
            value.put("packageIdentitiesValid", packageIdentitiesValid);
            return value;
        }
    }

    private static final class ExampleStatus {

        private final int sourceCount;
        private final int testCount;
        private final List<String> untested;

        private ExampleStatus(int sourceCount, int testCount, List<String> untested) {
            this.sourceCount = sourceCount;
            this.testCount = testCount;
            this.untested = untested;
        }

        private Map<String, Object> toMap() {
            Map<String, Object> value = new TreeMap<>();
            value.put("allExamplesTested", sourceCount >= REQUIRED_EXAMPLE_COUNT
                    && testCount > 0 && untested.isEmpty());
            value.put("requiredExampleCount", REQUIRED_EXAMPLE_COUNT);
            value.put("sourceCount", sourceCount);
            value.put("testCount", testCount);
            value.put("untestedSources", untested);
            return value;
        }
    }
}
