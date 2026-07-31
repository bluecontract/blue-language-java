package blue.language.utils.limits;

import blue.language.model.Node;
import blue.language.utils.JsonPointer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NodeToPathLimitsConverterTest {

    private final Node mockNode = new Node();

    @Test
    void shouldConvertEmptyNodeToPathLimits() {
        // given
        Node node = new Node();

        // when
        boolean rootAllowed = allows(node, "/");
        boolean arbitraryPathAllowed = allows(node, "/anyOtherPath");

        // then
        assertTrue(rootAllowed, "/");
        assertFalse(arbitraryPathAllowed, "/anyOtherPath");
    }

    @Test
    void shouldAllowOnlySingleDeclaredPropertyPath() {
        // given
        Node node = new Node().properties("prop", new Node());

        // when
        boolean propertyAllowed = allows(node, "/prop");
        boolean arbitraryPathAllowed = allows(node, "/anyOtherPath");

        // then
        assertTrue(propertyAllowed, "/prop");
        assertFalse(arbitraryPathAllowed, "/anyOtherPath");
    }

    @Test
    void shouldAllowDeclaredNestedPropertyPaths() {
        // given
        Node node = new Node().properties(
                "prop1", new Node().properties("nested", new Node()),
                "prop2", new Node()
        );

        // when
        boolean firstPropertyAllowed = allows(node, "/prop1");
        boolean nestedPropertyAllowed = allows(node, "/prop1/nested");
        boolean secondPropertyAllowed = allows(node, "/prop2");
        boolean nonexistentPropertyAllowed = allows(node, "/prop1/nonexistent");

        // then
        assertTrue(firstPropertyAllowed, "/prop1");
        assertTrue(nestedPropertyAllowed, "/prop1/nested");
        assertTrue(secondPropertyAllowed, "/prop2");
        assertFalse(nonexistentPropertyAllowed, "/prop1/nonexistent");
    }

    @Test
    void shouldConvertNodeItemsToPathLimits() {
        // given
        Node node = new Node().items(new Node(), new Node().properties("itemProp", new Node()));

        // when
        boolean firstItemAllowed = allows(node, "/0");
        boolean secondItemAllowed = allows(node, "/1");
        boolean itemPropertyAllowed = allows(node, "/1/itemProp");
        boolean missingItemAllowed = allows(node, "/2");

        // then
        assertTrue(firstItemAllowed, "/0");
        assertTrue(secondItemAllowed, "/1");
        assertTrue(itemPropertyAllowed, "/1/itemProp");
        assertFalse(missingItemAllowed, "/2");
    }

    @Test
    void shouldConvertComplexNodeToPathLimits() {
        // given
        Node node = new Node().properties(
                "prop1", new Node().items(new Node(), new Node().properties("nestedItemProp", new Node())),
                "prop2", new Node().properties("nestedProp", new Node())
        );

        // when
        boolean firstPropertyAllowed = allows(node, "/prop1");
        boolean firstItemAllowed = allows(node, "/prop1/0");
        boolean secondItemAllowed = allows(node, "/prop1/1");
        boolean nestedItemPropertyAllowed = allows(node, "/prop1/1/nestedItemProp");
        boolean secondPropertyAllowed = allows(node, "/prop2");
        boolean nestedPropertyAllowed = allows(node, "/prop2/nestedProp");
        boolean nestedDescendantAllowed = allows(node, "/prop2/nestedProp/xyz");
        boolean nonexistentPropertyAllowed = allows(node, "/nonexistent");

        // then
        assertTrue(firstPropertyAllowed, "/prop1");
        assertTrue(firstItemAllowed, "/prop1/0");
        assertTrue(secondItemAllowed, "/prop1/1");
        assertTrue(nestedItemPropertyAllowed, "/prop1/1/nestedItemProp");
        assertTrue(secondPropertyAllowed, "/prop2");
        assertTrue(nestedPropertyAllowed, "/prop2/nestedProp");
        assertFalse(nestedDescendantAllowed, "/prop2/nestedProp/xyz");
        assertFalse(nonexistentPropertyAllowed, "/nonexistent");
    }

    @Test
    void shouldAllowJsonPointerEscapesInPropertyNames() {
        // given
        Node node = new Node().properties(
                "a/b", new Node().properties("c~d", new Node())
        );

        // when
        boolean escapedSlashAllowed = allows(node, "/a~1b");
        boolean escapedTildeAllowed = allows(node, "/a~1b/c~0d");
        boolean unescapedSlashAllowed = allows(node, "/a/b");

        // then
        assertTrue(escapedSlashAllowed, "/a~1b");
        assertTrue(escapedTildeAllowed, "/a~1b/c~0d");
        assertFalse(unescapedSlashAllowed, "/a/b");
    }

    @Test
    void shouldIncludeReservedContractsFieldPaths() {
        // given
        Node node = new Node().contracts(new Node().properties("audit", new Node().properties("enabled", new Node())));

        // when
        boolean contractsAllowed = allows(node, "/contracts");
        boolean auditAllowed = allows(node, "/contracts/audit");
        boolean enabledAllowed = allows(node, "/contracts/audit/enabled");
        boolean unqualifiedAuditAllowed = allows(node, "/audit");

        // then
        assertTrue(contractsAllowed, "/contracts");
        assertTrue(auditAllowed, "/contracts/audit");
        assertTrue(enabledAllowed, "/contracts/audit/enabled");
        assertFalse(unqualifiedAuditAllowed, "/audit");
    }

    @Test
    void shouldConvertNullNodeToNoLimits() {
        // given
        Node node = null;

        // when
        boolean rootAllowed = allows(node, "/");
        boolean arbitraryPathAllowed = allows(node, "/anyPath");

        // then
        assertFalse(rootAllowed, "/");
        assertFalse(arbitraryPathAllowed, "/anyPath");
    }

    private boolean allows(Node node, String pointer) {
        PathLimits limits = NodeToPathLimitsConverter.convert(node);
        List<String> segments = JsonPointer.split(pointer);
        if (segments.isEmpty()) {
            return limits.shouldExpandPathSegment("", mockNode);
        }
        for (String segment : segments) {
            if (!limits.shouldExpandPathSegment(segment, mockNode)) {
                return false;
            }
            limits.enterPathSegment(segment, mockNode);
        }
        return true;
    }
}
