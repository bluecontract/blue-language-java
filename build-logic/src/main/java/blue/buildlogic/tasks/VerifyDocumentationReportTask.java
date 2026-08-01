package blue.buildlogic.tasks;

import blue.buildlogic.support.DeterministicJson;
import blue.buildlogic.support.DocumentationVerification;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

/** Enforces a previously generated documentation analysis and records the gate result. */
@CacheableTask
public abstract class VerifyDocumentationReportTask extends DefaultTask {

    private static final ObjectMapper JSON = new ObjectMapper();

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getAnalysisFile();

    @OutputFile
    public abstract RegularFileProperty getVerificationFile();

    @TaskAction
    public void verify() {
        JsonNode analysis;
        try {
            analysis = JSON.readTree(getAnalysisFile().get().getAsFile());
        } catch (IOException exception) {
            throw new GradleException("Cannot read documentation analysis", exception);
        }
        if (!DocumentationVerification.SCHEMA.equals(analysis.path("schema").asText())) {
            throw new GradleException("Unsupported documentation analysis schema");
        }
        boolean valid = analysis.path("valid").asBoolean(false);
        int violations = analysis.path("violationCount").asInt(-1);
        Map<String, Object> result = new TreeMap<>();
        result.put("analysisSchema", DocumentationVerification.SCHEMA);
        result.put("schema", "blue-language-java-documentation-gate/1.0");
        result.put("valid", valid);
        result.put("violationCount", violations);
        write(DeterministicJson.write(result));
        if (!valid) {
            throw new GradleException(
                    "Documentation verification failed with " + violations
                            + " violation(s); see " + getAnalysisFile().get().getAsFile());
        }
    }

    private void write(String content) {
        Path output = getVerificationFile().get().getAsFile().toPath();
        try {
            Files.createDirectories(output.getParent());
            Files.writeString(output, content, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new GradleException("Cannot write documentation gate report " + output, exception);
        }
    }
}
