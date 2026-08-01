package blue.language.codec;

import blue.language.model.Node;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.BlueIdReferenceValidator;
import blue.language.model.NodeWireForm;

import java.util.Objects;

import static blue.language.utils.UncheckedObjectMapper.JSON_MAPPER;
import static blue.language.utils.UncheckedObjectMapper.YAML_MAPPER;

/** Default strict JSON/YAML implementation of {@link BlueCodec}. */
public final class StandardBlueCodec implements BlueCodec {

    @Override
    public Node parseSource(String text, BlueFormat format) {
        return mapper(format).readValue(
                Objects.requireNonNull(text, "text"), Node.class);
    }

    @Override
    public Node parseBlueIdInput(String text, BlueFormat format) {
        Node node = parseSource(text, format);
        BlueIdReferenceValidator.validate(node);
        BlueIdCalculator.calculateBlueId(node);
        return node;
    }

    @Override
    public String write(Node node, BlueFormat format) {
        return mapper(format).writeValueAsString(
                NodeWireForm.get(
                        Objects.requireNonNull(node, "node")));
    }

    @Override
    public String writeSimple(Node node, BlueFormat format) {
        return mapper(format).writeValueAsString(
                NodeWireForm.get(
                        Objects.requireNonNull(node, "node"),
                        NodeWireForm.Strategy.SIMPLE));
    }

    private blue.language.utils.UncheckedObjectMapper mapper(
            BlueFormat format) {
        switch (Objects.requireNonNull(format, "format")) {
            case JSON:
                return JSON_MAPPER;
            case YAML:
                return YAML_MAPPER;
            default:
                throw new IllegalArgumentException(
                        "Unsupported Blue format: " + format);
        }
    }
}
