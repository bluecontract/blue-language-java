package blue.buildlogic.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.gradle.api.GradleException;

/** Builds the final machine-readable quality decision from already executed release evidence. */
public final class FinalQualityEvidence {

    public static final String SCHEMA = "blue-language-java-final-quality/1.0";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern API_MODULE = Pattern.compile("(?m)^# module: (.+)$");
    private static final Pattern SHA_256 = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final String BLUE_FACADE = "blue.language.Blue";

    private FinalQualityEvidence() {}

    /** Computes every report field and release blocker without hiding an ineligible candidate. */
    public static Map<String, Object> analyze(Inputs inputs) {
        Path root = inputs.repositoryRoot.toAbsolutePath().normalize();
        List<String> blockers = new ArrayList<>();
        JavaSourceQuality.Analysis source =
                JavaSourceQuality.analyze(root, inputs.productionSources);
        ApiSummary api = api(inputs.apiInventories);
        JsonNode conformance = json(inputs.releaseConformanceReport, "release conformance report");
        JsonNode documentation = json(inputs.documentationReport, "documentation analysis");
        if (inputs.sourceCommit == null
                || !inputs.sourceCommit.matches("(?:[0-9a-f]{40}|[0-9a-f]{64})")) {
            blockers.add("SOURCE_COMMIT_IDENTITY");
        }
        if (!inputs.excludedTasks.isEmpty()) {
            blockers.add("TASK_EXCLUSIONS");
        }

        Map<String, Object> identities = identities(
                conformance, inputs.languageSpecification, inputs.contractsSpecification,
                inputs.expectedLanguageFixtures, inputs.expectedContractsFixtures, blockers);
        Map<String, Object> fixtures = fixtures(
                conformance, inputs.expectedLanguageFixtures, inputs.expectedContractsFixtures,
                blockers);
        Map<String, Object> tests = tests(inputs.testResults, blockers);
        Map<String, Object> artifacts = artifacts(
                root, inputs.moduleArtifacts, inputs.expectedModuleCount, blockers);
        Map<String, Object> cycles = cycles(
                root, inputs.packageCycleReports, inputs.expectedModuleCount, blockers);
        Map<String, Object> classQuality = classes(
                source, inputs.classSizeRationales, inputs.maximumOrdinaryClassLines, blockers);
        Map<String, Object> publicTypes = publicTypes(source, api, blockers);
        Map<String, Object> apiReport = apiReport(
                api, inputs.expectedModuleCount, inputs.blueFacadeMemberLimit,
                inputs.publicFacadeMemberLimit, blockers);
        Map<String, Object> docs = documentation(
                documentation, inputs.javadocsSuccessful, inputs.examplesCompiled, blockers);
        Map<String, Object> benchmarks = benchmarks(
                inputs.benchmarkResults, inputs.requiredSmokeBenchmarks,
                inputs.benchmarksCompiled, blockers);
        Map<String, Object> architecture = architecture(inputs.moduleStructureReport, blockers);
        Map<String, Object> published = published(
                inputs.publishedRepositoryReport, inputs.publishedSmokeReport, blockers);

        JavaSourceQuality.SourceFile blue = source.files().stream()
                .filter(file -> BLUE_FACADE.equals(file.qualifiedTypeName()))
                .findFirst().orElse(null);
        boolean blueSourceSize = blue != null && blue.lineCount() < inputs.blueFacadeLineLimit;
        if (!blueSourceSize) {
            blockers.add("BLUE_FACADE_LINE_LIMIT");
        }
        int readmeLines = documentation.path("lineBudgets").path("readmeLines").asInt(-1);
        int rootBuildLines = documentation.path("lineBudgets").path("rootBuildLines").asInt(-1);
        boolean readmeCompact = readmeLines >= 0 && readmeLines < 500;
        boolean rootBuildCompact = rootBuildLines >= 0 && rootBuildLines < 350;
        if (!readmeCompact) blockers.add("README_LINE_LIMIT");
        if (!rootBuildCompact) blockers.add("ROOT_BUILD_LINE_LIMIT");

        Map<String, Object> qualityTargets = new TreeMap<>();
        qualityTargets.put("allExamplesCompiledAndTested",
                Boolean.TRUE.equals(docs.get("examplesValid")));
        qualityTargets.put("allPublicPackagesDocumented",
                Boolean.TRUE.equals(docs.get("publicPackagesDocumented")));
        qualityTargets.put("blueFacadeLineCount", blue == null ? -1 : blue.lineCount());
        qualityTargets.put("blueFacadeLineLimitExclusive", inputs.blueFacadeLineLimit);
        qualityTargets.put("blueFacadeUnderLineLimit", blueSourceSize);
        qualityTargets.put("blueFacadeWithinPublicMemberLimit",
                Boolean.TRUE.equals(apiReport.get("blueFacadeWithinMemberLimit")));
        qualityTargets.put("noHundredMethodPublicFacadeOrInterface",
                Boolean.TRUE.equals(apiReport.get("facadesAndInterfacesWithinMemberLimit")));
        qualityTargets.put("noUnallowlistedOrdinaryClassOverLimit",
                Boolean.TRUE.equals(classQuality.get("withinLimit")));
        qualityTargets.put("productionPackageCycleCount", cycles.get("cycleCount"));
        qualityTargets.put("publicClassInInternalPackageCount",
                publicTypes.get("publicTypesInInternalPackagesCount"));
        qualityTargets.put("readmeLineCount", readmeLines);
        qualityTargets.put("readmeUnder500Lines", readmeCompact);
        qualityTargets.put("rootBuildLineCount", rootBuildLines);
        qualityTargets.put("rootBuildUnder350Lines", rootBuildCompact);
        qualityTargets.put("specificationsAndFixturesExactlyBound",
                identities.get("exactlyBound"));

        blockers = new ArrayList<>(new TreeSet<>(blockers));
        Map<String, Object> eligibility = new TreeMap<>();
        eligibility.put("blockerCount", blockers.size());
        eligibility.put("blockers", blockers);
        eligibility.put("eligible", blockers.isEmpty());

        Map<String, Object> report = new TreeMap<>();
        report.put("apiTotals", apiReport);
        report.put("architecture", architecture);
        report.put("benchmarkSummary", benchmarks);
        report.put("documentation", docs);
        report.put("fixtureTotals", fixtures);
        Map<String, Object> invocation = new TreeMap<>();
        invocation.put("excludedTasks", new ArrayList<>(inputs.excludedTasks));
        invocation.put("exclusionFree", inputs.excludedTasks.isEmpty());
        report.put("invocation", invocation);
        report.put("largestClassReport", classQuality);
        report.put("moduleArtifactHashes", artifacts);
        report.put("packageCycles", cycles);
        report.put("publicTypes", publicTypes);
        report.put("publishedArtifacts", published);
        report.put("qualityTargets", qualityTargets);
        report.put("releaseEligibility", eligibility);
        report.put("schema", SCHEMA);
        report.put("sourceCommit", inputs.sourceCommit);
        report.put("specificationAndPackageIdentities", identities);
        report.put("testTotals", tests);
        return report;
    }

    private static Map<String, Object> identities(
            JsonNode report,
            Path languageSpec,
            Path contractsSpec,
            int expectedLanguageFixtures,
            int expectedContractsFixtures,
            List<String> blockers) {
        String languageHash = bareHash(languageSpec);
        String contractsHash = bareHash(contractsSpec);
        String reportedLanguage = report.path("specifications").path("languageSha256").asText();
        String reportedContracts = report.path("specifications").path("contractsSha256").asText();
        boolean specificationsBound = languageHash.equals(reportedLanguage)
                && contractsHash.equals(reportedContracts);
        Map<String, String> packages = new TreeMap<>();
        boolean packagesValid = false;
        java.util.Iterator<Map.Entry<String, JsonNode>> fields = report.path("packages").fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            packages.put(field.getKey(), field.getValue().asText());
        }
        if (!packages.isEmpty()) {
            packagesValid = packages.values().stream().allMatch(value -> SHA_256.matcher(value).matches());
        }
        String releaseIdentity = report.path("release").path("packageIdentity").asText();
        String contractsReleaseIdentity = report.path("release")
                .path("contractsReleaseIdentity").asText();
        String contractsReleasePackage = report.path("packages")
                .path("contractsRelease").asText();
        packagesValid &= SHA_256.matcher(releaseIdentity).matches();
        boolean contractsReleaseBound =
                SHA_256.matcher(contractsReleaseIdentity).matches()
                        && contractsReleaseIdentity.equals(
                                contractsReleasePackage);
        packagesValid &= contractsReleaseBound;
        int languageFixtures = fixtureCount(report, "language");
        int contractsFixtures = fixtureCount(report, "contracts");
        boolean fixtureBinding = languageFixtures == expectedLanguageFixtures
                && contractsFixtures == expectedContractsFixtures
                && allFixturesPassed(report);
        boolean exactlyBound = specificationsBound && packagesValid && fixtureBinding;
        if (!exactlyBound) blockers.add("SPECIFICATION_OR_PACKAGE_IDENTITY_BINDING");

        Map<String, Object> specs = new TreeMap<>();
        specs.put("contractsActualSha256", contractsHash);
        specs.put("contractsReportedSha256", reportedContracts);
        specs.put("languageActualSha256", languageHash);
        specs.put("languageReportedSha256", reportedLanguage);
        Map<String, Object> value = new TreeMap<>();
        value.put("exactlyBound", exactlyBound);
        value.put("contractsReleaseBound", contractsReleaseBound);
        value.put("contractsReleaseIdentity", contractsReleaseIdentity);
        value.put("packageIdentities", packages);
        value.put("packageIdentitiesValid", packagesValid);
        value.put("releasePackageIdentity", releaseIdentity);
        value.put("specifications", specs);
        value.put("specificationsBound", specificationsBound);
        return value;
    }

    private static Map<String, Object> fixtures(
            JsonNode report,
            int expectedLanguage,
            int expectedContracts,
            List<String> blockers) {
        int language = fixtureCount(report, "language");
        int contracts = fixtureCount(report, "contracts");
        boolean passed = allFixturesPassed(report);
        boolean exact = language == expectedLanguage && contracts == expectedContracts && passed;
        if (!exact) blockers.add("FIXTURE_TOTALS_OR_RESULTS");
        Map<String, Object> value = new TreeMap<>();
        value.put("allPassed", passed);
        value.put("contracts", contracts);
        value.put("expectedContracts", expectedContracts);
        value.put("expectedLanguage", expectedLanguage);
        value.put("language", language);
        value.put("total", language + contracts);
        return value;
    }

    private static Map<String, Object> tests(
            Collection<Path> testResults, List<String> blockers) {
        try {
            JUnitEvidence.Summary summary = JUnitEvidence.parse(
                    testResults, ":allUnitAndIntegrationTests", false);
            if (!summary.isConformant()) blockers.add("TEST_RESULTS");
            return summary.toMap();
        } catch (GradleException exception) {
            blockers.add("TEST_RESULTS");
            Map<String, Object> missing = new TreeMap<>();
            missing.put("conformant", false);
            missing.put("failed", 0);
            missing.put("passed", 0);
            missing.put("reason", exception.getMessage());
            missing.put("skipped", 0);
            missing.put("tests", 0);
            return missing;
        }
    }

    private static Map<String, Object> artifacts(
            Path root,
            Collection<Path> moduleArtifacts,
            int expectedCount,
            List<String> blockers) {
        List<Path> artifacts = regular(moduleArtifacts);
        artifacts.sort(Comparator.comparing(path -> relative(root, path)));
        List<Map<String, Object>> hashes = new ArrayList<>();
        Set<String> modules = new TreeSet<>();
        for (Path artifact : artifacts) {
            String path = relative(root, artifact);
            String module = path.contains("/") ? path.substring(0, path.indexOf('/')) : ":root";
            modules.add(module);
            Map<String, Object> value = new TreeMap<>();
            value.put("module", module);
            value.put("path", path);
            value.put("sha256", DeterministicHashing.sha256(artifact));
            hashes.add(value);
        }
        boolean complete = artifacts.size() == expectedCount && modules.size() == expectedCount;
        if (!complete) blockers.add("MODULE_ARTIFACT_HASHES");
        Map<String, Object> value = new TreeMap<>();
        value.put("artifactCount", artifacts.size());
        value.put("complete", complete);
        value.put("expectedArtifactCount", expectedCount);
        value.put("modules", new ArrayList<>(modules));
        value.put("records", hashes);
        return value;
    }

    private static Map<String, Object> cycles(
            Path root, Collection<Path> reports, int expectedCount, List<String> blockers) {
        List<Path> files = regular(reports);
        int cycleCount = 0;
        List<Map<String, Object>> records = new ArrayList<>();
        for (Path file : files) {
            JsonNode report = json(file, "package cycle report");
            int count = report.path("cycleCount").asInt(-1);
            cycleCount += Math.max(0, count);
            Map<String, Object> value = new TreeMap<>();
            value.put("cycleCount", count);
            value.put("packageCount", report.path("packageCount").asInt(-1));
            value.put("report", relative(root, file));
            records.add(value);
        }
        boolean valid = files.size() == expectedCount && cycleCount == 0;
        if (!valid) blockers.add("PRODUCTION_PACKAGE_CYCLES");
        Map<String, Object> value = new TreeMap<>();
        value.put("cycleCount", cycleCount);
        value.put("expectedReportCount", expectedCount);
        value.put("reportCount", files.size());
        value.put("reports", records);
        value.put("valid", valid);
        return value;
    }

    private static Map<String, Object> classes(
            JavaSourceQuality.Analysis source,
            Map<String, String> rationales,
            int lineLimit,
            List<String> blockers) {
        List<Map<String, Object>> largest = new ArrayList<>();
        for (JavaSourceQuality.SourceFile file : source.largestFiles(20)) {
            Map<String, Object> record = new TreeMap<>(file.toMap());
            record.put("allowlistedRationale", rationales.get(file.relativePath()));
            record.put("withinOrdinaryLimit", file.lineCount() <= lineLimit
                    || rationales.containsKey(file.relativePath()));
            largest.add(record);
        }
        List<String> violations = new ArrayList<>();
        for (JavaSourceQuality.SourceFile file : source.files()) {
            if (file.lineCount() > lineLimit && !rationales.containsKey(file.relativePath())) {
                violations.add(file.relativePath());
            }
        }
        List<String> staleRationales = new ArrayList<>();
        for (Map.Entry<String, String> rationale : rationales.entrySet()) {
            JavaSourceQuality.SourceFile file = source.files().stream()
                    .filter(candidate -> candidate.relativePath().equals(rationale.getKey()))
                    .findFirst().orElse(null);
            if (file == null || file.lineCount() <= lineLimit || rationale.getValue().trim().isEmpty()) {
                staleRationales.add(rationale.getKey());
            }
        }
        boolean valid = violations.isEmpty() && staleRationales.isEmpty();
        if (!valid) blockers.add("ORDINARY_CLASS_LINE_LIMIT");
        Map<String, Object> value = new TreeMap<>();
        value.put("allowlistedRationales", new TreeMap<>(rationales));
        value.put("largestClasses", largest);
        value.put("lineLimit", lineLimit);
        value.put("staleAllowlistEntries", staleRationales);
        value.put("unallowlistedOverLimit", violations);
        value.put("withinLimit", valid);
        return value;
    }

    private static Map<String, Object> publicTypes(
            JavaSourceQuality.Analysis source, ApiSummary api, List<String> blockers) {
        List<String> internal = new ArrayList<>();
        for (JavaSourceQuality.SourceFile file : source.files()) {
            if (file.isPublic() && containsPackageSegment(file.packageName(), "internal")) {
                internal.add(file.qualifiedTypeName());
            }
        }
        Collections.sort(internal);
        if (!internal.isEmpty()) blockers.add("PUBLIC_TYPES_IN_INTERNAL_PACKAGES");
        Map<String, Object> value = new TreeMap<>();
        value.put("apiPublicTypeCount", api.types.size());
        value.put("publicSourceTypeCount", source.publicTypeCount());
        value.put("publicTypesInInternalPackages", internal);
        value.put("publicTypesInInternalPackagesCount", internal.size());
        return value;
    }

    private static Map<String, Object> apiReport(
            ApiSummary api,
            int expectedModules,
            int blueMemberLimit,
            int facadeMemberLimit,
            List<String> blockers) {
        int blueMembers = api.membersByOwner.getOrDefault(BLUE_FACADE, 0);
        boolean blueValid = blueMembers <= blueMemberLimit && api.types.contains(BLUE_FACADE);
        if (!blueValid) blockers.add("BLUE_FACADE_PUBLIC_MEMBER_LIMIT");
        Map<String, Integer> oversized = new TreeMap<>();
        for (String type : api.types) {
            boolean facade = type.equals(BLUE_FACADE)
                    || type.substring(type.lastIndexOf('.') + 1).contains("Facade")
                    || api.interfaces.contains(type);
            int members = api.methodsByOwner.getOrDefault(type, 0);
            if (facade && members >= facadeMemberLimit) {
                oversized.put(type, members);
            }
        }
        if (!oversized.isEmpty()) blockers.add("PUBLIC_FACADE_OR_INTERFACE_METHOD_LIMIT");
        boolean complete = api.modules.size() == expectedModules;
        if (!complete) blockers.add("PUBLIC_API_INVENTORIES");
        Map<String, Object> value = new TreeMap<>();
        value.put("blueFacadeMemberLimit", blueMemberLimit);
        value.put("blueFacadePublicMemberCount", blueMembers);
        value.put("blueFacadeWithinMemberLimit", blueValid);
        value.put("facadeOrInterfaceMethodLimitExclusive", facadeMemberLimit);
        value.put("facadesAndInterfacesWithinMemberLimit", oversized.isEmpty());
        value.put("fieldCount", api.fieldCount);
        value.put("inventoryCount", api.modules.size());
        value.put("methodCount", api.methodCount);
        value.put("modules", new ArrayList<>(api.modules));
        value.put("oversizedFacadesOrInterfaces", oversized);
        value.put("publicTypeCount", api.types.size());
        return value;
    }

    private static Map<String, Object> documentation(
            JsonNode report,
            boolean javadocsSuccessful,
            boolean examplesCompiled,
            List<String> blockers) {
        boolean docsValid = report.path("valid").asBoolean(false);
        boolean packagesDocumented = report.path("packages").path("missingPackageInfo").size() == 0;
        boolean examplesValid = examplesCompiled
                && report.path("examples").path("allExamplesTested").asBoolean(false);
        if (!docsValid) blockers.add("DOCUMENTATION_VERIFICATION");
        if (!javadocsSuccessful) blockers.add("JAVADOCS");
        if (!packagesDocumented) blockers.add("PUBLIC_PACKAGE_DOCUMENTATION");
        if (!examplesValid) blockers.add("RUNNABLE_EXAMPLES");
        Map<String, Object> value = new TreeMap<>();
        value.put("documentationValid", docsValid);
        value.put("examplesCompiled", examplesCompiled);
        value.put("examplesValid", examplesValid);
        value.put("javadocsValid", javadocsSuccessful);
        value.put("publicPackagesDocumented", packagesDocumented);
        value.put("violationCount", report.path("violationCount").asInt(-1));
        return value;
    }

    private static Map<String, Object> benchmarks(
            Path resultFile,
            List<String> required,
            boolean compiled,
            List<String> blockers) {
        Set<String> executed = new TreeSet<>();
        if (resultFile != null && Files.isRegularFile(resultFile)) {
            JsonNode report = json(resultFile, "JMH smoke result");
            if (report.isArray()) {
                for (JsonNode benchmark : report) {
                    executed.add(benchmark.path("benchmark").asText());
                }
            }
        }
        List<String> missing = new ArrayList<>();
        for (String requiredBenchmark : required) {
            if (executed.stream().noneMatch(name -> name.equals(requiredBenchmark)
                    || name.endsWith("." + requiredBenchmark))) {
                missing.add(requiredBenchmark);
            }
        }
        if (!compiled) blockers.add("JMH_COMPILATION");
        if (!missing.isEmpty()) blockers.add("JMH_REQUIRED_SMOKE");
        Map<String, Object> value = new TreeMap<>();
        value.put("compiled", compiled);
        value.put("executedBenchmarks", new ArrayList<>(executed));
        value.put("missingRequiredBenchmarks", missing);
        value.put("requiredBenchmarks", new ArrayList<>(required));
        value.put("smokePassed", missing.isEmpty());
        return value;
    }

    private static Map<String, Object> architecture(Path reportFile, List<String> blockers) {
        JsonNode report = json(reportFile, "module structure report");
        boolean valid = report.path("valid").asBoolean(false)
                && report.path("cycles").size() == 0
                && report.path("splitPackages").size() == 0
                && report.path("undeclaredEdges").size() == 0;
        if (!valid) blockers.add("MODULE_ARCHITECTURE");
        Map<String, Object> value = new TreeMap<>();
        value.put("moduleCount", report.path("moduleCount").asInt(-1));
        value.put("moduleCycleCount", report.path("cycles").size());
        value.put("splitPackageCount", report.path("splitPackages").size());
        value.put("undeclaredEdgeCount", report.path("undeclaredEdges").size());
        value.put("valid", valid);
        return value;
    }

    private static Map<String, Object> published(
            Path repositoryReport, Path smokeReport, List<String> blockers) {
        JsonNode repository = json(repositoryReport, "published repository report");
        JsonNode smoke = json(smokeReport, "published artifact smoke report");
        boolean repositoryValid = repository.path("valid").asBoolean(false);
        boolean smokeValid = smoke.path("valid").asBoolean(false);
        if (!repositoryValid || !smokeValid) blockers.add("PUBLISHED_ARTIFACT_SMOKE");
        Map<String, Object> value = new TreeMap<>();
        value.put("repositoryValid", repositoryValid);
        value.put("resolvedCoordinateCount", smoke.path("resolvedCoordinates").size());
        value.put("smokeValid", smokeValid);
        return value;
    }

    private static ApiSummary api(Collection<Path> inventoryFiles) {
        Set<String> modules = new TreeSet<>();
        Set<String> types = new TreeSet<>();
        Set<String> interfaces = new TreeSet<>();
        Map<String, Integer> methodsByOwner = new TreeMap<>();
        Map<String, Integer> membersByOwner = new TreeMap<>();
        int methods = 0;
        int fields = 0;
        List<Path> files = regular(inventoryFiles);
        files.sort(Comparator.comparing(Path::toString));
        for (Path file : files) {
            String content = read(file, "public API inventory");
            Matcher module = API_MODULE.matcher(content);
            if (!module.find()) {
                throw new GradleException("Public API inventory has no module: " + file);
            }
            modules.add(module.group(1).trim());
            for (String line : content.split("\\R")) {
                if (line.startsWith("type ")) {
                    String type = line.substring("type ".length(), line.indexOf(" access="));
                    types.add(type);
                    String access = line.substring(line.indexOf(" access=") + " access=".length(),
                            line.indexOf(" super="));
                    if (access.split(",").length > 0
                            && java.util.Arrays.asList(access.split(",")).contains("interface")) {
                        interfaces.add(type);
                    }
                } else if (line.startsWith("method ")) {
                    String owner = owner(line, "method ");
                    methods++;
                    increment(methodsByOwner, owner);
                    increment(membersByOwner, owner);
                } else if (line.startsWith("field ")) {
                    fields++;
                    increment(membersByOwner, owner(line, "field "));
                }
            }
        }
        return new ApiSummary(modules, types, interfaces, methodsByOwner, membersByOwner,
                methods, fields);
    }

    private static String owner(String line, String prefix) {
        int member = line.indexOf('#', prefix.length());
        return member < 0 ? "<invalid>" : line.substring(prefix.length(), member);
    }

    private static void increment(Map<String, Integer> values, String key) {
        values.put(key, values.getOrDefault(key, 0) + 1);
    }

    private static boolean containsPackageSegment(String packageName, String segment) {
        if (packageName == null) return false;
        for (String candidate : packageName.split("\\.")) {
            if (candidate.equals(segment)) return true;
        }
        return false;
    }

    private static int fixtureCount(JsonNode report, String suite) {
        int count = 0;
        for (JsonNode fixture : report.path("fixtures")) {
            if (suite.equals(fixture.path("suite").asText())) count++;
        }
        return count;
    }

    private static boolean allFixturesPassed(JsonNode report) {
        if (!report.path("fixtures").isArray() || report.path("fixtures").size() == 0) {
            return false;
        }
        for (JsonNode fixture : report.path("fixtures")) {
            if (!"PASS".equals(fixture.path("status").asText())) return false;
        }
        return true;
    }

    private static List<Path> regular(Collection<Path> paths) {
        List<Path> files = new ArrayList<>();
        for (Path path : paths) {
            if (path != null && Files.isRegularFile(path)) files.add(path);
        }
        return files;
    }

    private static String bareHash(Path file) {
        return DeterministicHashing.sha256(file).substring("sha256:".length());
    }

    private static String relative(Path root, Path file) {
        Path normalized = file.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) return normalized.toString().replace('\\', '/');
        return root.relativize(normalized).toString().replace('\\', '/');
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

    /** Immutable analyzer input bundle. */
    public static final class Inputs {
        private final Path repositoryRoot;
        private final Collection<Path> productionSources;
        private final Collection<Path> apiInventories;
        private final Collection<Path> moduleArtifacts;
        private final Collection<Path> testResults;
        private final Collection<Path> packageCycleReports;
        private final Path releaseConformanceReport;
        private final Path documentationReport;
        private final Path moduleStructureReport;
        private final Path languageSpecification;
        private final Path contractsSpecification;
        private final Path benchmarkResults;
        private final Path publishedRepositoryReport;
        private final Path publishedSmokeReport;
        private final String sourceCommit;
        private final List<String> excludedTasks;
        private final Map<String, String> classSizeRationales;
        private final List<String> requiredSmokeBenchmarks;
        private final int expectedModuleCount;
        private final int expectedLanguageFixtures;
        private final int expectedContractsFixtures;
        private final int maximumOrdinaryClassLines;
        private final int blueFacadeLineLimit;
        private final int blueFacadeMemberLimit;
        private final int publicFacadeMemberLimit;
        private final boolean javadocsSuccessful;
        private final boolean examplesCompiled;
        private final boolean benchmarksCompiled;

        public Inputs(
                Path repositoryRoot,
                Collection<Path> productionSources,
                Collection<Path> apiInventories,
                Collection<Path> moduleArtifacts,
                Collection<Path> testResults,
                Collection<Path> packageCycleReports,
                Path releaseConformanceReport,
                Path documentationReport,
                Path moduleStructureReport,
                Path languageSpecification,
                Path contractsSpecification,
                Path benchmarkResults,
                Path publishedRepositoryReport,
                Path publishedSmokeReport,
                String sourceCommit,
                List<String> excludedTasks,
                Map<String, String> classSizeRationales,
                List<String> requiredSmokeBenchmarks,
                int expectedModuleCount,
                int expectedLanguageFixtures,
                int expectedContractsFixtures,
                int maximumOrdinaryClassLines,
                int blueFacadeLineLimit,
                int blueFacadeMemberLimit,
                int publicFacadeMemberLimit,
                boolean javadocsSuccessful,
                boolean examplesCompiled,
                boolean benchmarksCompiled) {
            this.repositoryRoot = repositoryRoot;
            this.productionSources = productionSources;
            this.apiInventories = apiInventories;
            this.moduleArtifacts = moduleArtifacts;
            this.testResults = testResults;
            this.packageCycleReports = packageCycleReports;
            this.releaseConformanceReport = releaseConformanceReport;
            this.documentationReport = documentationReport;
            this.moduleStructureReport = moduleStructureReport;
            this.languageSpecification = languageSpecification;
            this.contractsSpecification = contractsSpecification;
            this.benchmarkResults = benchmarkResults;
            this.publishedRepositoryReport = publishedRepositoryReport;
            this.publishedSmokeReport = publishedSmokeReport;
            this.sourceCommit = sourceCommit;
            this.excludedTasks = new ArrayList<>(excludedTasks);
            Collections.sort(this.excludedTasks);
            this.classSizeRationales = new LinkedHashMap<>(classSizeRationales);
            this.requiredSmokeBenchmarks = new ArrayList<>(requiredSmokeBenchmarks);
            this.expectedModuleCount = expectedModuleCount;
            this.expectedLanguageFixtures = expectedLanguageFixtures;
            this.expectedContractsFixtures = expectedContractsFixtures;
            this.maximumOrdinaryClassLines = maximumOrdinaryClassLines;
            this.blueFacadeLineLimit = blueFacadeLineLimit;
            this.blueFacadeMemberLimit = blueFacadeMemberLimit;
            this.publicFacadeMemberLimit = publicFacadeMemberLimit;
            this.javadocsSuccessful = javadocsSuccessful;
            this.examplesCompiled = examplesCompiled;
            this.benchmarksCompiled = benchmarksCompiled;
        }
    }

    private static final class ApiSummary {
        private final Set<String> modules;
        private final Set<String> types;
        private final Set<String> interfaces;
        private final Map<String, Integer> methodsByOwner;
        private final Map<String, Integer> membersByOwner;
        private final int methodCount;
        private final int fieldCount;

        private ApiSummary(
                Set<String> modules,
                Set<String> types,
                Set<String> interfaces,
                Map<String, Integer> methodsByOwner,
                Map<String, Integer> membersByOwner,
                int methodCount,
                int fieldCount) {
            this.modules = modules;
            this.types = types;
            this.interfaces = interfaces;
            this.methodsByOwner = methodsByOwner;
            this.membersByOwner = membersByOwner;
            this.methodCount = methodCount;
            this.fieldCount = fieldCount;
        }
    }
}
