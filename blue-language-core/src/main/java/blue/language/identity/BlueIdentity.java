package blue.language.identity;

import blue.language.model.Node;

import java.util.List;

/**
 * Calculates the one BlueId representation through either the strict direct
 * path or the complete Source Document path.
 *
 * <p>The two entry points differ only in preparation. Direct identity accepts
 * an exact valid BlueId input. Source identity first obtains the canonical
 * identity input and then invokes that same direct calculation.</p>
 */
public interface BlueIdentity {

    /**
     * Calculates a BlueId from exact direct identity input.
     *
     * @param blueIdInput strict direct BlueId input
     * @return canonical Base58 SHA-256 BlueId
     */
    String directBlueId(Node blueIdInput);

    /**
     * Calculates a BlueId through the complete Source Document identity path.
     *
     * @param sourceDocument authored Source Document
     * @return canonical Base58 SHA-256 BlueId
     */
    String sourceDocumentBlueId(Node sourceDocument);

    /**
     * Produces the unique direct identity input for a Source Document.
     *
     * @param sourceDocument authored Source Document
     * @return canonical direct BlueId input
     */
    Node canonicalIdentityInput(Node sourceDocument);

    /**
     * Calculates stable member BlueIds for a closed cyclic document set.
     *
     * @param documents cyclic documents containing indexed {@code this}
     *                  references
     * @return member BlueIds in caller order
     */
    List<String> circularBlueIds(List<Node> documents);
}
