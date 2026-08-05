package blue.buildlogic.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class JavaPublicApiInventoryTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldInventoryTheSamePublicApiFromAClassDirectoryAndJar() throws Exception {
        // given
        Path source = TestJavaCompiler.source(
                temporaryDirectory,
                "src/sample/PublicApi.java",
                "package sample;\n"
                        + "public class PublicApi<T> {\n"
                        + "  public static final String NAME = \"blue\";\n"
                        + "  protected T value;\n"
                        + "  private int hidden;\n"
                        + "  public PublicApi() {}\n"
                        + "  public T value() throws java.io.IOException { return value; }\n"
                        + "  private void hidden() {}\n"
                        + "}\n");
        Path classes = temporaryDirectory.resolve("classes");
        TestJavaCompiler.compile(classes, source);
        Path jar = jar(classes, temporaryDirectory.resolve("public-api.jar"));

        // when
        List<String> directoryInventory = JavaPublicApiInventory.inspect(
                Collections.singletonList(classes));
        List<String> jarInventory = JavaPublicApiInventory.inspect(
                Collections.singletonList(jar));

        // then
        assertEquals(directoryInventory, jarInventory);
        assertTrue(directoryInventory.stream().anyMatch(line -> line.startsWith("type sample.PublicApi")));
        assertTrue(directoryInventory.stream().anyMatch(line -> line.contains("#NAME")));
        assertTrue(directoryInventory.stream().anyMatch(line -> line.contains("#value")));
        assertFalse(directoryInventory.stream().anyMatch(line -> line.contains("hidden")));
    }

    @Test
    void shouldUnionInventoriesWithoutDependingOnInputOrderOrHeaders() throws Exception {
        // given
        Path first = Files.writeString(
                temporaryDirectory.resolve("first.txt"),
                "# module: first\nmethod z.Z#z descriptor=()V\n",
                StandardCharsets.UTF_8);
        Path second = Files.writeString(
                temporaryDirectory.resolve("second.txt"),
                "# module: second\ntype a.A access=public\nmethod z.Z#z descriptor=()V\n",
                StandardCharsets.UTF_8);

        // when
        List<String> forward = JavaPublicApiInventory.union(
                Collections.emptyList(), Arrays.asList(first, second));
        List<String> reverse = JavaPublicApiInventory.union(
                Collections.emptyList(), Arrays.asList(second, first));

        // then
        assertEquals(forward, reverse);
        assertEquals(Arrays.asList(
                "method z.Z#z descriptor=()V", "type a.A access=public"), forward);
        assertTrue(JavaPublicApiInventory.write("aggregate", forward)
                .startsWith("# schema: " + JavaPublicApiInventory.SCHEMA + "\n"));
    }

    private static Path jar(Path classes, Path output) throws Exception {
        try (OutputStream stream = Files.newOutputStream(output);
                ZipOutputStream zip = new ZipOutputStream(stream)) {
            Path classFile = classes.resolve("sample/PublicApi.class");
            zip.putNextEntry(new ZipEntry("sample/PublicApi.class"));
            zip.write(Files.readAllBytes(classFile));
            zip.closeEntry();
        }
        return output;
    }
}
