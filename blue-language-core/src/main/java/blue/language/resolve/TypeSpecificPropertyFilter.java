package blue.language.resolve;

import blue.language.model.Node;

import java.util.Set;
import java.util.Stack;

/**
 * Suppresses expansion of selected properties while traversing instances of
 * one exact declared type.
 *
 * <p>Merging is never suppressed. The root path remains eligible even if its
 * segment name appears in the ignored-property set.</p>
 */
final class TypeSpecificPropertyFilter implements ResolutionLimits {
    private final String typeBlueId;
    private final Set<String> ignoredProperties;
    private final Stack<String> currentPath = new Stack<>();
    private final Stack<Boolean> typeMatchStack = new Stack<>();

    /**
     * Creates a filter for one declared type BlueId and property-name set.
     *
     * @param typeBlueId exact declared type whose properties are filtered
     * @param ignoredProperties property names whose expansion is suppressed
     */
    TypeSpecificPropertyFilter(String typeBlueId, Set<String> ignoredProperties) {
        this.typeBlueId = typeBlueId;
        this.ignoredProperties = ignoredProperties;
    }

    @Override
    public boolean shouldExpandPathSegment(String pathSegment, Node currentNode) {
        boolean isCurrentlyInTargetType = !typeMatchStack.isEmpty() && typeMatchStack.peek();
        boolean isIgnoredProperty = ignoredProperties.contains(pathSegment);

        return !isCurrentlyInTargetType || !isIgnoredProperty || currentPath.isEmpty();
    }

    /** Legacy binary-API spelling delegated to the canonical method. */
    @Override
    public boolean shouldExtendPathSegment(String pathSegment, Node currentNode) {
        return shouldExpandPathSegment(pathSegment, currentNode);
    }

    @Override
    public boolean shouldMergePathSegment(String pathSegment, Node currentNode) {
        return true;
    }

    @Override
    public void enterPathSegment(String pathSegment, Node currentNode) {
        currentPath.push(pathSegment);

        boolean isEnteringTargetType = false;
        if (currentNode != null && currentNode.getType() != null) {
            isEnteringTargetType = typeBlueId.equals(currentNode.getType().getBlueId());
        }
        typeMatchStack.push(isEnteringTargetType);
    }

    @Override
    public void exitPathSegment() {
        if (!currentPath.isEmpty()) {
            currentPath.pop();
            typeMatchStack.pop();
        }
    }
}
