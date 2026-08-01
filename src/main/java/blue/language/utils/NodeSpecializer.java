package blue.language.utils;

import blue.language.merge.NodeResolver;
import blue.language.model.Node;

import java.util.Objects;

/**
 * Creates an authored specialization from a type and a compatible overlay.
 *
 * <p>Specialization creates a new node whose {@code type} names the supplied
 * type and whose remaining content comes from the overlay. It is distinct
 * from expansion: expansion reveals verified content for an existing BlueId,
 * while specialization normally establishes a new BlueId.</p>
 */
public final class NodeSpecializer {

    private final NodeResolver resolver;

    /**
     * Creates a specializer whose completed resolution validates compatibility.
     *
     * @param resolver resolver used to validate the resulting specialization
     */
    public NodeSpecializer(NodeResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    /**
     * Creates and validates a specialization without mutating either input.
     *
     * @param type non-null type node or pure type reference
     * @param overlay non-null compatible authored overlay without a type
     * @return independent authored specialization
     * @throws IllegalArgumentException when the overlay already declares a
     *                                  type or does not resolve compatibly
     */
    public Node specialize(Node type, Node overlay) {
        Objects.requireNonNull(type, Properties.OBJECT_TYPE);
        Objects.requireNonNull(overlay, "overlay");
        if (overlay.getType() != null) {
            throw new IllegalArgumentException(
                    "specialization overlay must not already declare type");
        }

        Node specialization = overlay.clone().type(type.clone());
        resolver.resolve(specialization.clone());
        return specialization;
    }
}
