package blue.buildlogic.support;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/** Small deterministic Java fixture compiler shared by compiled-artifact tests. */
public final class TestJavaCompiler {

    private static final String RELEASE_VERSION = "17";

    private TestJavaCompiler() {}

    public static Path source(Path root, String relativePath, String content) throws Exception {
        Path source = root.resolve(relativePath);
        Files.createDirectories(source.getParent());
        return Files.writeString(source, content, StandardCharsets.UTF_8);
    }

    public static void compile(Path output, Path... sources) throws Exception {
        Files.createDirectories(output);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        List<String> arguments = new ArrayList<>();
        arguments.add("--release");
        arguments.add(RELEASE_VERSION);
        arguments.add("-d");
        arguments.add(output.toString());
        for (Path source : sources) {
            arguments.add(source.toString());
        }
        int result = compiler.run(null, null, null, arguments.toArray(new String[0]));
        assertEquals(0, result, "fixture compilation");
    }
}
