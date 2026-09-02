package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.registry.RuntimeBlueIds;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Direct contract for the public processor-state path facade. */
final class ProcessorStateReferencePathsTest {

    @Test
    void shouldReturnImmutableValidatedPatchEffectPaths() {
        Node initialized = new Node()
                .type(new Node().blueId(
                        RuntimeBlueIds.PROCESSING_INITIALIZED_MARKER))
                .properties(
                        "document",
                        new Node().name("exact initialization witness"));
        Node patch = new Node().properties(
                "op", new Node().value("replace"),
                "path", new Node().value("/contracts"),
                "val", new Node().properties("initialized", initialized));
        Node executableBody = new Node().properties(
                "patches", new Node().items(patch));

        Set<String> paths =
                ProcessorStateReferencePaths.inPatchEffects(executableBody);

        assertEquals(
                Collections.singleton(
                        "/patches/0/val/initialized/document"),
                paths);
        assertThrows(
                UnsupportedOperationException.class,
                () -> paths.add("/not-allowed"));
    }
}
