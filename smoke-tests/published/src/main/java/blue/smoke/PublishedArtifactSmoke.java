package blue.smoke;

import blue.language.Blue;
import blue.language.model.Node;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Minimal executable consumer built strictly from staged Maven coordinates. */
public final class PublishedArtifactSmoke {

    private PublishedArtifactSmoke() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: PublishedArtifactSmoke <report>");
        }
        try (Blue blue = new Blue()) {
            Node parsed = blue.yamlToNode("name: staged-smoke\n");
            if (!"staged-smoke".equals(parsed.get("/name"))) {
                throw new IllegalStateException("Aggregate parse entry point returned wrong value");
            }
            if (!blue.nodeToYaml(parsed).contains("staged-smoke")) {
                throw new IllegalStateException("Aggregate write entry point returned wrong value");
            }
        }
        Path report = Paths.get(args[0]);
        String current = new String(Files.readAllBytes(report), StandardCharsets.UTF_8).trim();
        if (!current.contains("\"valid\":true")) {
            throw new IllegalStateException("Resolved-coordinate report was not valid");
        }
    }
}
