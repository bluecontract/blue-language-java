package blue.buildlogic.support;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class BenchmarkResultEvidenceTest {
    @TempDir Path directory;

    @Test void rejectsAbsentEmptyIncompleteAndNonFiniteResults() throws Exception {
        for (String json : new String[] {"[]", "[{\"benchmark\":\"required\"}]",
                result("\"NaN\"", "[[1]]"), result("1", "[]"), result("1", "[[\"NaN\"]]"),
                result("1", "[[1]]").replace("required", "other.required")}) {
            Path file = directory.resolve("result.json");
            Files.writeString(file, json);
            List<String> blockers = new ArrayList<>();
            FinalQualityEvidence.benchmarks(file, List.of("required"), true, blockers);
            assertEquals(List.of("JMH_REQUIRED_SMOKE"), blockers, json);
        }
        List<String> blockers = new ArrayList<>();
        FinalQualityEvidence.benchmarks(directory.resolve("absent.json"), List.of("required"), true, blockers);
        assertEquals(List.of("JMH_REQUIRED_SMOKE"), blockers);
    }

    @Test void acceptsOnlyActualMeasuredRequiredResult() throws Exception {
        Path file = directory.resolve("valid.json");
        Files.writeString(file, result("1", "[[1]]"));
        List<String> blockers = new ArrayList<>();
        var evidence = FinalQualityEvidence.benchmarks(file, List.of("required"), true, blockers);
        assertTrue(blockers.isEmpty());
        assertEquals(true, evidence.get("smokePassed"));
    }

    private static String result(String score, String raw) {
        return "[{\"benchmark\":\"required\",\"forks\":1,\"measurementIterations\":1,"
                + "\"primaryMetric\":{\"score\":" + score + ",\"scoreUnit\":\"ops/s\",\"rawData\":" + raw + "}}]";
    }
}
