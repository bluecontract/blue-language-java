package blue.language.processor.model;

import blue.language.model.Node;
import blue.language.model.TypeBlueId;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.utils.BlueIdCalculator;
import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * Processor-owned marker that retains the exact document selected at
 * initialization.
 *
 * <p>The legacy document-id accessors remain JVM-compatible aliases, but are
 * excluded from the Contracts 1.0 wire shape. The mutable document node is
 * retained and returned by reference.</p>
 */
@TypeBlueId(RuntimeBlueIds.PROCESSING_INITIALIZED_MARKER)
public class InitializationMarker extends MarkerContract {

    private Node document;

    /** Creates an empty initialization marker. */
    public InitializationMarker() {
    }

    /**
     * Returns the document captured at initialization.
     *
     * @return retained document reference, or {@code null} when absent
     */
    public Node getDocument() {
        return document;
    }

    /**
     * Sets the document captured at initialization.
     *
     * @param document document retained by reference, or {@code null}
     */
    public void setDocument(Node document) {
        this.document = document;
    }

    /**
     * Retained JVM compatibility accessor. The Contracts 1.0 wire shape uses
     * {@link #getDocument()} and does not serialize this compatibility value.
     *
     * @return calculated document BlueId, or {@code null} when no document is
     *         stored
     */
    @JsonIgnore
    public String getDocumentId() {
        return document == null
                ? null
                : BlueIdCalculator.calculateBlueId(
                        document);
    }

    /**
     * Replaces the captured document with an exact BlueId reference.
     *
     * @param documentId exact document BlueId, or {@code null} to clear it
     */
    @JsonIgnore
    public void setDocumentId(String documentId) {
        this.document =
                documentId == null
                        ? null
                        : new Node().blueId(
                                documentId);
    }
}
