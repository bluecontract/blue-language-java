package blue.language.preprocess;

import blue.language.model.Node;

import java.util.Objects;
import java.util.Optional;

/**
 * Immutable, preflighted transformation selected by an exact type BlueId.
 */
public final class TransformationSnapshot {

    private final String nodeBlueId;
    private final String typeBlueId;
    private final Node configuration;
    private final TransformationProcessor processor;

    /**
     * Freezes one transformation and its resolved deterministic processor.
     *
     * @param nodeBlueId exact transformation-node identity, when established
     * @param typeBlueId exact transformation-type identity
     * @param configuration verified transformation configuration
     * @param processor deterministic selected processor
     */
    public TransformationSnapshot(
            String nodeBlueId,
            String typeBlueId,
            Node configuration,
            TransformationProcessor processor) {
        this.nodeBlueId = nodeBlueId;
        this.typeBlueId = Objects.requireNonNull(
                typeBlueId, "typeBlueId");
        this.configuration = Objects.requireNonNull(
                configuration, "configuration").clone();
        this.processor = Objects.requireNonNull(
                processor, "processor");
    }

    /**
     * Returns the exact transformation-node identity when it was supplied by
     * reference or could be calculated from direct exact content.
     *
     * @return optional exact node identity
     */
    public Optional<String> nodeBlueId() {
        return Optional.ofNullable(nodeBlueId);
    }

    /**
     * Returns the exact type identity used to select behavior.
     *
     * @return exact transformation-type BlueId
     */
    public String typeBlueId() {
        return typeBlueId;
    }

    /**
     * Returns a defensive copy of the frozen configuration.
     *
     * @return independent configuration copy
     */
    public Node configuration() {
        return configuration.clone();
    }

    /**
     * Applies the preflighted processor to a defensive source copy.
     *
     * @param source current working Source Document
     * @param context immutable established preprocessing context
     * @return non-null next Source Document
     */
    public Node apply(Node source, PreprocessingContext context) {
        Node result = processor.process(
                Objects.requireNonNull(source, "source").clone(),
                Objects.requireNonNull(context, "context"));
        return Objects.requireNonNull(
                result, "Preprocessing transformation returned null")
                .clone();
    }
}
