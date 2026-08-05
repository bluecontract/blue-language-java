package blue.buildlogic.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class JavaModuleInventoryTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldDeriveAcyclicModuleEdgesFromCompiledClassReferences() throws Exception {
        // given
        CompiledModules modules = compiledModules(false);
        JavaModuleInventory.Inventory first = JavaModuleInventory.inspect(
                "first", Collections.singletonList(modules.first), Collections.emptyList());
        JavaModuleInventory.Inventory second = JavaModuleInventory.inspect(
                "second", Collections.singletonList(modules.second), Collections.emptyList());
        Path inventoryFile = Files.writeString(
                temporaryDirectory.resolve("first-inventory.txt"), first.write());

        // when
        JavaModuleInventory.Inventory reloaded = JavaModuleInventory.read(inventoryFile);
        ModuleStructureVerifier.Result result = ModuleStructureVerifier.analyze(
                Arrays.asList(reloaded, second), Collections.singletonList("first->second"), true);

        // then
        assertEquals("first", reloaded.getModule());
        assertTrue(reloaded.getPackages().contains("first.api"));
        assertTrue(reloaded.getReferences().contains("second.api.SecondType"));
        assertTrue(result.isValid());
        assertTrue(result.toJson().contains("\"source\":\"first\",\"target\":\"second\""));
    }

    @Test
    void shouldRejectModuleCyclesFoundInCompiledArtifacts() throws Exception {
        // given
        CompiledModules modules = compiledModules(true);
        JavaModuleInventory.Inventory first = JavaModuleInventory.inspect(
                "first", Collections.singletonList(modules.first), Collections.emptyList());
        JavaModuleInventory.Inventory second = JavaModuleInventory.inspect(
                "second", Collections.singletonList(modules.second), Collections.emptyList());

        // when
        ModuleStructureVerifier.Result result = ModuleStructureVerifier.analyze(
                Arrays.asList(first, second),
                Arrays.asList("first->second", "second->first"),
                true);

        // then
        assertFalse(result.isValid());
        assertEquals(1, result.getCycleCount());
        assertTrue(result.toJson().contains("\"cycles\":[[\"first\",\"second\"]]"));
    }

    @Test
    void shouldRejectSplitPackagesAndUndeclaredSourceInventoryEdges() throws Exception {
        // given
        Path firstSource = TestJavaCompiler.source(
                temporaryDirectory,
                "first/First.java",
                "package shared.api;\nimport second.api.SecondType;\nclass First {}\n");
        Path secondSource = TestJavaCompiler.source(
                temporaryDirectory,
                "second/Second.java",
                "package shared.api;\nclass Second {}\n");
        JavaModuleInventory.Inventory first = JavaModuleInventory.inspect(
                "first", Collections.emptyList(), Collections.singletonList(firstSource));
        JavaModuleInventory.Inventory second = JavaModuleInventory.inspect(
                "second", Collections.emptyList(), Collections.singletonList(secondSource));
        JavaModuleInventory.Inventory target = JavaModuleInventory.inspect(
                "target",
                Collections.emptyList(),
                Collections.singletonList(TestJavaCompiler.source(
                        temporaryDirectory,
                        "target/SecondType.java",
                        "package second.api;\nclass SecondType {}\n")));

        // when
        ModuleStructureVerifier.Result result = ModuleStructureVerifier.analyze(
                Arrays.asList(first, second, target), Collections.emptyList(), true);

        // then
        assertFalse(result.isValid());
        assertEquals(1, result.getSplitPackageCount());
        assertEquals(1, result.getUndeclaredEdgeCount());
        assertTrue(result.toJson().contains("\"package\":\"shared.api\""));
    }

    private CompiledModules compiledModules(boolean cyclic) throws Exception {
        Path firstSource = TestJavaCompiler.source(
                temporaryDirectory,
                "src/first/api/FirstType.java",
                "package first.api; public final class FirstType {"
                        + " public second.api.SecondType value; }\n");
        Path secondSource = TestJavaCompiler.source(
                temporaryDirectory,
                "src/second/api/SecondType.java",
                cyclic
                        ? "package second.api; public final class SecondType {"
                                + " public first.api.FirstType value; }\n"
                        : "package second.api; public final class SecondType {}\n");
        Path combined = temporaryDirectory.resolve(cyclic ? "combined-cyclic" : "combined");
        TestJavaCompiler.compile(combined, firstSource, secondSource);
        Path first = temporaryDirectory.resolve(cyclic ? "first-cyclic" : "first-classes");
        Path second = temporaryDirectory.resolve(cyclic ? "second-cyclic" : "second-classes");
        copyClass(combined, first, "first/api/FirstType.class");
        copyClass(combined, second, "second/api/SecondType.class");
        return new CompiledModules(first, second);
    }

    private static void copyClass(Path combined, Path destination, String relativePath)
            throws Exception {
        Path target = destination.resolve(relativePath);
        Files.createDirectories(target.getParent());
        Files.copy(combined.resolve(relativePath), target, StandardCopyOption.REPLACE_EXISTING);
    }

    private static final class CompiledModules {

        private final Path first;
        private final Path second;

        private CompiledModules(Path first, Path second) {
            this.first = first;
            this.second = second;
        }
    }
}
