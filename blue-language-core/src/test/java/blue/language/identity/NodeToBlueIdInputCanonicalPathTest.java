package blue.language.identity;

import blue.language.model.Node;
import blue.language.model.Nodes;
import blue.language.model.wire.JsonPointer;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.*;

/** The optimized helper accepts private canonical parents, not arbitrary authored pointers. */
class NodeToBlueIdInputCanonicalPathTest {
    @Test void everyGeneratedCanonicalPrefixMatchesTheOriginalGeneralAppender() throws Exception {
        Method append = NodeToBlueIdInput.class.getDeclaredMethod("appendPath", String.class, String.class);
        Method indexed = NodeToBlueIdInput.class.getDeclaredMethod("appendPath", String.class, String.class, int.class);
        append.setAccessible(true); indexed.setAccessible(true);
        List<String> segments = Arrays.asList(null, "", "/", "~", "~0", "~1", "~01", "~x", "a/b", "a~b",
                "a//b", "0", "-", "\uD83D\uDE00", "\u0000", "e\u0301", "\uD800");
        LinkedHashSet<String> parents = new LinkedHashSet<>(Arrays.asList("/", "/0", "/-1", "/2147483647"));
        int compared = 0;
        for (int depth = 0; depth < 3; depth++) {
            LinkedHashSet<String> next = new LinkedHashSet<String>();
            for (String parent : parents) for (String segment : segments) {
                String expected = JsonPointer.append(parent, segment);
                assertEquals(expected, append.invoke(null, parent, segment));
                assertEquals(JsonPointer.append(expected, "17"), indexed.invoke(null, parent, segment, 17));
                next.add(expected); compared++;
            }
            parents = next;
        }
        assertTrue(compared > 10_000, "Exercise escaped intermediate prefixes, not only leaves");
        assertEquals("/", append.invoke(null, "/", ""), "Preserve Blue's existing root spelling");
        assertEquals("/x", append.invoke(null, "/", "x"));
        assertEquals("/~0x/leaf", JsonPointer.append("/~x", "leaf"),
                "The public general-purpose appender still canonicalizes malformed authored tilde sequences");
    }

    @Test void allEntryPointsPreserveExactProjectionAndHashForNestedEscapedKeys() {
        Node value = new Node().properties("a/b", new Node().items(
                Nodes.emptyObject(), new Node().properties("~01", Nodes.emptyObject())));
        Object expected = singletonMap("a/b", Arrays.asList(emptyMap(), singletonMap("~01", emptyMap())));
        for (Entry entry : entries()) {
            Object actual = entry.project.apply(value);
            assertEquals(expected, actual, entry.name);
            assertArrayEquals(CanonicalJsonValueWriter.write(expected), CanonicalJsonValueWriter.write(actual), entry.name);
            assertEquals(DirectBlueIdCalculator.INSTANCE.directBlueIdFromCanonicalInput(expected),
                    DirectBlueIdCalculator.INSTANCE.directBlueIdFromCanonicalInput(actual), entry.name);
        }
        assertEquals(expected, NodeToBlueIdInput.getResolvedFormElements(Arrays.asList(value)).get(0));
        assertEquals(DirectBlueIdCalculator.INSTANCE.directBlueIdFromCanonicalInput(expected),
                DirectBlueIdCalculator.calculateBlueId(value));
    }

    @Test void everyEntryPointKeepsCompleteFailurePathsAndRootPositions() {
        for (Entry entry : entries()) {
            for (String segment : Arrays.asList("", "/", "~01", "a/b", "a~b", "\uD83D\uDE00")) {
                Node invalid = new Node().properties(segment,
                        new Node().items(new Node().properties("~x", new Node())));
                String expected = JsonPointer.append(JsonPointer.append(JsonPointer.append(
                        JsonPointer.append(entry.root, segment), "items"), "0"), "~x");
                IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> entry.project.apply(invalid), entry.name);
                assertEquals("Fieldless Node is an incomplete builder, not semantic Blue content. "
                        + "Use Nodes.emptyObject() for {} or omit the field for absence. Path: " + expected,
                        failure.getMessage(), entry.name);
            }
        }
        Node invalid = new Node().properties("a/b", new Node());
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> NodeToBlueIdInput.getResolvedFormElements(Arrays.asList(Nodes.emptyObject(), invalid)))
                .getMessage().endsWith("Path: /1/a~1b"));
    }

    @Test void metadataTypeAndListControlValidationStayAtTheSameLogicalPaths() {
        Node invalidType = new Node().properties("x/y", new Node().type(new Node().name("Expanded type")));
        String typeError = "Direct BlueId input type positions must contain pure references. Path: /x~1y/type";
        assertEquals(typeError, assertThrows(IllegalArgumentException.class,
                () -> NodeToBlueIdInput.get(invalidType)).getMessage());
        Node type = new Node().name("Expanded type");
        assertEquals(singletonMap("x/y", singletonMap("type", singletonMap("blueId", DirectBlueIdCalculator.calculateBlueId(type)))),
                NodeToBlueIdInput.getResolvedForm(invalidType));
        Node overlay = new Node().properties("a/b", new Node().items(
                new Node().properties("$replace", Nodes.emptyObject())));
        assertEquals("\"$replace\" overlays are not valid direct BlueId input. Path: /a~1b/items/0",
                assertThrows(IllegalArgumentException.class, () -> NodeToBlueIdInput.get(overlay)).getMessage());
        Node placeholder = new Node().properties("a/b", new Node().blueId("this#0"));
        assertEquals(singletonMap("a/b", singletonMap("blueId", "this#0")), NodeToBlueIdInput.getAllowingCyclicPlaceholders(placeholder));
        assertEquals(singletonMap("a/b", singletonMap("blueId", "this#0")),
                NodeToBlueIdInput.getListElementAllowingCyclicPlaceholders(placeholder, 3));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> NodeToBlueIdInput.get(placeholder))
                .getMessage().contains("/a~1b/blueId"));
    }

    private static final class Entry {
        final String name, root;
        final Function<Node, Object> project;
        Entry(String name, String root, Function<Node, Object> project) {
            this.name = name; this.root = root; this.project = project;
        }
    }
    private static List<Entry> entries() {
        List<Entry> entries = new ArrayList<Entry>();
        entries.add(new Entry("root", "/", NodeToBlueIdInput::get));
        entries.add(new Entry("cyclic-root", "/", NodeToBlueIdInput::getAllowingCyclicPlaceholders));
        entries.add(new Entry("resolved-root", "/", NodeToBlueIdInput::getResolvedForm));
        for (int index : Arrays.asList(-1, 0, 17, Integer.MAX_VALUE)) {
            entries.add(new Entry("element-" + index, "/" + index, node -> NodeToBlueIdInput.getListElement(node, index)));
            entries.add(new Entry("cyclic-element-" + index, "/" + index,
                    node -> NodeToBlueIdInput.getListElementAllowingCyclicPlaceholders(node, index)));
        }
        return entries;
    }
}
