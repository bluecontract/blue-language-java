package blue.language.utils.limits;

import blue.language.model.Node;

import java.util.Set;
import java.util.Stack;

public class TypeSpecificPropertyFilter implements Limits {
    private final String typeBlueId;
    private final Set<String> ignoredProperties;
    private final Stack<String> currentPath = new Stack<>();
    private final Stack<Boolean> typeMatchStack = new Stack<>();

    public TypeSpecificPropertyFilter(String typeBlueId, Set<String> ignoredProperties) {
        this.typeBlueId = typeBlueId;
        this.ignoredProperties = ignoredProperties;
    }

    @Override
    public boolean shouldExtendPathSegment(String pathSegment, Node currentNode) {
        boolean isCurrentlyInTargetType = !typeMatchStack.isEmpty() && typeMatchStack.peek();
        boolean isIgnoredProperty = ignoredProperties.contains(pathSegment);

        return !isCurrentlyInTargetType || !isIgnoredProperty || currentPath.isEmpty();
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