package blue.buildlogic.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class FinalQualityEvidenceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldTurnEveryStructuralQualityTargetIntoAReleaseBlocker() throws Exception {
        // given
        Path blue = write(
                "blue-language-java/src/main/java/blue/language/Blue.java",
                "package blue.language;\npublic class Blue {\n\n\n}\n");
        Path internal = write(
                "module/src/main/java/blue/internal/Hidden.java",
                "package blue.internal;\npublic final class Hidden {}\n");
        Path api = write(
                "module/build/reports/api/current-api.txt",
                "# schema: blue-java-public-api/1.0\n# module: module\n# entryCount: 6\n"
                        + "type blue.language.Blue access=public super=java.lang.Object interfaces=- signature=-\n"
                        + "method blue.language.Blue#first descriptor=()V access=public signature=- throws=-\n"
                        + "method blue.language.Blue#second descriptor=()V access=public signature=- throws=-\n"
                        + "type blue.language.WideSpi access=public,interface super=java.lang.Object interfaces=- signature=-\n"
                        + "method blue.language.WideSpi#first descriptor=()V access=public,abstract signature=- throws=-\n"
                        + "method blue.language.WideSpi#second descriptor=()V access=public,abstract signature=- throws=-\n");
        Path artifact = write("module/build/libs/module.jar", "jar");
        Path tests = write(
                "module/build/test-results/test/TEST-pass.xml",
                "<testsuite name=\"Pass\" tests=\"1\" failures=\"0\" errors=\"0\" skipped=\"0\">"
                        + "<testcase classname=\"Pass\" name=\"ok\"/></testsuite>");
        Path cycles = write(
                "module/build/reports/architecture/package-cycles.json",
                "{\"cycleCount\":1,\"packageCount\":2}");
        Path languageSpec = write("language.md", "language\n");
        Path contractsSpec = write("contracts.md", "contracts\n");
        Path conformance = write("conformance.json", conformance(
                bare(languageSpec), bare(contractsSpec)));
        Path docs = write("documentation.json",
                "{\"valid\":false,\"violationCount\":2,"
                        + "\"packages\":{\"missingPackageInfo\":[\"blue.language\"]},"
                        + "\"examples\":{\"allExamplesTested\":false},"
                        + "\"lineBudgets\":{\"readmeLines\":600,\"rootBuildLines\":400}}");
        Path modules = write("modules.json",
                "{\"valid\":true,\"moduleCount\":1,\"cycles\":[],"
                        + "\"splitPackages\":[],\"undeclaredEdges\":[]}");
        Path benchmarks = write("benchmarks.json", "[]");
        Path published = write("published.json", "{\"valid\":true}");
        Path smoke = write("smoke.json",
                "{\"valid\":true,\"resolvedCoordinates\":[\"module\"]}");

        // when
        Map<String, Object> report = FinalQualityEvidence.analyze(
                new FinalQualityEvidence.Inputs(
                        temporaryDirectory,
                        Arrays.asList(blue, internal),
                        Collections.singletonList(api),
                        Collections.singletonList(artifact),
                        Collections.singletonList(tests),
                        Collections.singletonList(cycles),
                        conformance,
                        docs,
                        modules,
                        languageSpec,
                        contractsSpec,
                        benchmarks,
                        published,
                        smoke,
                        repeat('a'),
                        Collections.singletonList(":releaseVerify"),
                        Collections.singletonMap(
                                "module/src/main/java/blue/internal/Hidden.java",
                                "Reviewed exception that is now below the limit."),
                        Collections.singletonList("RequiredBenchmark.run"),
                        1,
                        1,
                        1,
                        2,
                        3,
                        1,
                        2,
                        true,
                        true,
                        true));

        // then
        @SuppressWarnings("unchecked")
        List<String> blockers = (List<String>) ((Map<String, Object>)
                report.get("releaseEligibility")).get("blockers");
        assertTrue(blockers.contains("BLUE_FACADE_LINE_LIMIT"));
        assertTrue(blockers.contains("BLUE_FACADE_PUBLIC_MEMBER_LIMIT"));
        assertTrue(blockers.contains("PUBLIC_FACADE_OR_INTERFACE_METHOD_LIMIT"));
        assertTrue(blockers.contains("PUBLIC_TYPES_IN_INTERNAL_PACKAGES"));
        assertTrue(blockers.contains("PRODUCTION_PACKAGE_CYCLES"));
        assertTrue(blockers.contains("ORDINARY_CLASS_LINE_LIMIT"));
        assertTrue(blockers.contains("DOCUMENTATION_VERIFICATION"));
        assertTrue(blockers.contains("PUBLIC_PACKAGE_DOCUMENTATION"));
        assertTrue(blockers.contains("RUNNABLE_EXAMPLES"));
        assertTrue(blockers.contains("JMH_REQUIRED_SMOKE"));
        assertTrue(blockers.contains("README_LINE_LIMIT"));
        assertTrue(blockers.contains("ROOT_BUILD_LINE_LIMIT"));
        assertTrue(blockers.contains("TASK_EXCLUSIONS"));
        assertTrue(blockers.contains(
                "SPECIFICATION_OR_PACKAGE_IDENTITY_BINDING"));
        @SuppressWarnings("unchecked")
        Map<String, Object> largestClassReport = (Map<String, Object>)
                report.get("largestClassReport");
        assertEquals(Collections.singletonList(
                "module/src/main/java/blue/internal/Hidden.java"),
                largestClassReport.get("staleAllowlistEntries"));
    }

    private String conformance(String languageHash, String contractsHash) {
        return "{\"release\":{\"packageIdentity\":\"sha256:" + repeat64('c') + "\"},"
                + "\"packages\":{\"fixtures\":\"sha256:" + repeat64('b') + "\"},"
                + "\"specifications\":{\"languageSha256\":\"" + languageHash
                + "\",\"contractsSha256\":\"" + contractsHash + "\"},"
                + "\"fixtures\":["
                + "{\"suite\":\"language\",\"status\":\"PASS\"},"
                + "{\"suite\":\"contracts\",\"status\":\"PASS\"}]}";
    }

    private static String bare(Path file) {
        return DeterministicHashing.sha256(file).substring("sha256:".length());
    }

    private static String repeat(char value) {
        return String.valueOf(value).repeat(40);
    }

    private static String repeat64(char value) {
        return String.valueOf(value).repeat(64);
    }

    private Path write(String relativePath, String content) throws Exception {
        Path file = temporaryDirectory.resolve(relativePath);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
