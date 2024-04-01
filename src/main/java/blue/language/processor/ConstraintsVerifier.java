package blue.language.processor;

import blue.language.MergingProcessor;
import blue.language.NodeProvider;
import blue.language.NodeResolver;
import blue.language.model.Constraints;
import blue.language.model.Node;

import java.util.List;
import java.util.regex.Pattern;

public class ConstraintsVerifier implements MergingProcessor {

    @Override
    public void process(Node target, Node source, NodeProvider nodeProvider, NodeResolver nodeResolver) {
        Constraints constraints = source.getConstraints();
        if (constraints == null)
            return;

        verifyRequired(constraints.getRequired(), target.getValue());
        verifyAllowMultiple(constraints.getAllowMultiple(), target.getItems());
        verifyMinLength(constraints.getMinLength(), target.getValue());
        verifyMaxLength(constraints.getMaxLength(), target.getValue());
        verifyPattern(constraints.getPattern(), target.getValue());
    }

    private void verifyRequired(Boolean required, Object value) {
        if (Boolean.TRUE.equals(required) && value == null)
            throw new IllegalArgumentException("Value is required but is null.");
    }

    private void verifyAllowMultiple(Boolean allowMultiple, List<Node> items) {
        if ((allowMultiple == null || Boolean.FALSE.equals(allowMultiple)) && items != null && items.size() > 1)
            throw new IllegalArgumentException("Multiple items are not allowed.");
    }

    private void verifyMinLength(Integer minLength, Object value) {
        if (minLength != null && value instanceof String && ((String) value).length() < minLength)
            throw new IllegalArgumentException("Value is shorter than the minimum length of " + minLength + ".");
    }

    private void verifyMaxLength(Integer maxLength, Object value) {
        if (maxLength != null && value instanceof String && ((String) value).length() > maxLength) {
            throw new IllegalArgumentException("Value is longer than the maximum length of " + maxLength + ".");
        }
    }

    private void verifyPattern(String pattern, Object value) {
        if (pattern != null && value instanceof String) {
            if (!Pattern.matches(pattern, (String) value)) {
                throw new IllegalArgumentException("Value does not match the required pattern.");
            }
        }
    }

}
