package blue.language.mapping;

import blue.language.model.Node;

import java.lang.reflect.Type;

/**
 * Strategy for converting a Blue {@link Node} into one family of Java types.
 *
 * @param <T> converted Java value type
 */
public interface Converter<T> {

    /**
     * Converts a node to the requested reflective type.
     *
     * @param node source Blue node, possibly {@code null}
     * @param targetType requested Java type
     * @return converted Java value, possibly {@code null}
     * @throws RuntimeException when the node cannot be represented by the type
     */
    T convert(Node node, Type targetType);

    /**
     * Conversion variant allowing callers to prefer the requested Java type
     * over a more specific class resolved from Blue metadata.
     *
     * @param node source Blue node, possibly {@code null}
     * @param targetType requested Java type
     * @param prioritizeTargetType whether the requested type takes precedence
     *                             over resolved Blue metadata
     * @return converted Java value, possibly {@code null}
     * @throws RuntimeException when the node cannot be represented by the type
     */
    default T convert(Node node, Type targetType, boolean prioritizeTargetType) {
        return convert(node, targetType);
    }
}
