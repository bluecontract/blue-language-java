package blue.buildlogic.tasks;

import blue.buildlogic.support.DeterministicJson;
import blue.buildlogic.support.FinalQualityEvidence;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/** Fails the final release gate from the complete precomputed quality report. */
@CacheableTask
public abstract class VerifyFinalQualityReportTask extends DefaultTask {

    private static final ObjectMapper JSON = new ObjectMapper();

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getQualityReport();

    @OutputFile
    public abstract RegularFileProperty getVerificationReport();

    @TaskAction
    public void verify() {
        JsonNode report;
        try {
            report = JSON.readTree(getQualityReport().get().getAsFile());
        } catch (IOException exception) {
            throw new GradleException("Cannot read final quality report", exception);
        }
        if (!FinalQualityEvidence.SCHEMA.equals(report.path("schema").asText())) {
            throw new GradleException("Unsupported final quality report schema");
        }
        boolean eligible = report.path("releaseEligibility").path("eligible").asBoolean(false);
        List<String> blockers = new ArrayList<>();
        for (JsonNode blocker : report.path("releaseEligibility").path("blockers")) {
            blockers.add(blocker.asText());
        }
        Map<String, Object> verification = new TreeMap<>();
        verification.put("blockers", blockers);
        verification.put("eligible", eligible);
        verification.put("qualitySchema", FinalQualityEvidence.SCHEMA);
        verification.put("schema", "blue-language-java-final-quality-gate/1.0");
        write(DeterministicJson.write(verification));
        if (!eligible) {
            throw new GradleException(
                    "Final quality verification is ineligible: " + String.join(", ", blockers));
        }
    }

    private void write(String content) {
        Path output = getVerificationReport().get().getAsFile().toPath();
        try {
            Files.createDirectories(output.getParent());
            Files.writeString(output, content, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new GradleException("Cannot write final quality verification " + output, exception);
        }
    }
}
