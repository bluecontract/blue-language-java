package blue.buildlogic.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.gradle.api.GradleException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class JUnitEvidenceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldMergeSuitesAndPreserveDeterministicCaseEvidence() throws Exception {
        // given
        Path first = write("z.xml", """
                <testsuite name="blue.ExampleSuite" tests="2" failures="1" errors="0" skipped="0">
                  <testcase classname="blue.ExampleSuite" name="shouldPass()"/>
                  <testcase classname="blue.ExampleSuite" name="shouldFail(String)">
                    <failure message="expected"/>
                  </testcase>
                </testsuite>
                """);
        Path second = write("a.xml", """
                <testsuite name="blue.ExampleSuite" tests="1" failures="0" errors="0" skipped="1">
                  <testcase classname="blue.ExampleSuite" name="shouldSkip()">
                    <skipped/>
                  </testcase>
                </testsuite>
                """);
        Path hosted = write("hosted.xml", """
                <testsuite name="blue.HostedSuite" tests="1" failures="0" errors="0" skipped="0">
                  <testcase classname="blue.HostedSuite" name="shouldRun"/>
                </testsuite>
                """);

        // when
        JUnitEvidence.Summary forward = JUnitEvidence.parse(
                Arrays.asList(first, second, hosted), ":test", true);
        JUnitEvidence.Summary reverse = JUnitEvidence.parse(
                Arrays.asList(hosted, second, first), ":test", true);
        Map<String, Object> hostedEvidence = forward.suiteEvidence("HostedSuite");
        Map<String, Object> diagnosticEvidence =
                forward.suiteEvidence("ExampleSuite", true);
        List<Map<String, Object>> parameterized =
                forward.records("blue.ExampleSuite", "shouldFail");

        // then
        assertEquals(4, forward.getTests());
        assertEquals(2, forward.getPassed());
        assertEquals(1, forward.getFailed());
        assertEquals(1, forward.getSkipped());
        assertFalse(forward.isConformant());
        assertEquals(DeterministicJson.write(forward.toMap()),
                DeterministicJson.write(reverse.toMap()));
        assertEquals(Collections.singletonList("blue.HostedSuite"),
                hostedEvidence.get("suiteNames"));
        assertEquals(1, hostedEvidence.get("tests"));
        assertEquals(3, ((List<?>) diagnosticEvidence.get("testCases")).size());
        assertEquals(1, parameterized.size());
        assertEquals("FAILED", parameterized.get(0).get("status"));
    }

    @Test
    void shouldRejectXmlWithADocumentTypeDeclaration() throws Exception {
        // given
        Path result = write("unsafe.xml", """
                <!DOCTYPE testsuite [<!ENTITY external SYSTEM "file:///etc/passwd">]>
                <testsuite name="unsafe" tests="1" failures="0" errors="0" skipped="0">
                  <testcase classname="unsafe" name="shouldNotExpand">&external;</testcase>
                </testsuite>
                """);

        // when / then
        assertThrows(GradleException.class,
                () -> JUnitEvidence.parse(Collections.singletonList(result), ":test", true));
    }

    private Path write(String name, String contents) throws Exception {
        return Files.writeString(
                temporaryDirectory.resolve(name), contents, StandardCharsets.UTF_8);
    }
}
