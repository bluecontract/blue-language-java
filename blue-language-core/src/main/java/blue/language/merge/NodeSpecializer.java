package blue.language.merge;

import blue.language.model.wire.BlueLanguageConstants;

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
     * Creates a specializer whose definition resolution validates compatibility.
     *
     * @param resolver resolver used to validate the resulting specialization
     */
    public NodeSpecializer(NodeResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    /**
     * Creates and validates a specialization without mutating either input.
     * Checks definition compatibility, not completed-instance presence.
     *
     * @param type non-null type node or pure type reference
     * @param overlay non-null compatible authored overlay without a type
     * @return independent authored specialization
     * @throws IllegalArgumentException when the overlay already declares a
     *                                  type or does not resolve compatibly
     */
    public Node specialize(Node type, Node overlay) {
        Objects.requireNonNull(type, BlueLanguageConstants.OBJECT_TYPE);
        Objects.requireNonNull(overlay, "overlay");
        if (overlay.getType() != null) {
            throw new IllegalArgumentException(
                    "specialization overlay must not already declare type");
        }

        Node specialization = overlay.clone().type(type.clone());
        // Authoring may create another definition. The metadata position
        // preserves fixed-value checks while deferring absent instance payload.
        resolver.resolve(new Node()
                .type(new Node().blueId(BlueLanguageConstants.LIST_TYPE_BLUE_ID))
                .itemType(specialization.clone()),
                blue.language.resolve.ResolutionLimits.NO_LIMITS);
        return specialization;
    }
}
