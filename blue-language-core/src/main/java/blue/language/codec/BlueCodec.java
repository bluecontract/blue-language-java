package blue.language.codec;

import blue.language.model.Node;

/**
 * Parses and writes Blue documents without running semantic preprocessing or
 * resolution.
 *
 * <p>The two parse entry points deliberately distinguish authored Source from
 * exact direct-BlueId input. This keeps validation at the boundary where the
 * caller's intent is known.</p>
 */
public interface BlueCodec {

    /**
     * Parses an authored Source Document.
     *
     * @param text JSON or YAML text
     * @param format text format
     * @return a new mutable authoring node
     */
    Node parseSource(String text, BlueFormat format);

    /**
     * Parses and validates exact direct-BlueId input.
     *
     * @param text JSON or YAML text
     * @param format text format
     * @return a new mutable node valid for direct identity calculation
     */
    Node parseBlueIdInput(String text, BlueFormat format);

    /**
     * Writes the normalized Blue representation.
     *
     * @param node node to write; it is not mutated
     * @param format target text format
     * @return serialized document
     */
    String write(Node node, BlueFormat format);

    /**
     * Writes scalar and list sugar where the Blue syntax permits it.
     *
     * @param node node to write; it is not mutated
     * @param format target text format
     * @return simplified serialized document
     */
    String writeSimple(Node node, BlueFormat format);
}
