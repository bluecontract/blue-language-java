package blue.language.identity;

import blue.language.model.wire.BlueLanguageConstants;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Function;

import static blue.language.utils.CanonicalIdentityConstants.LIST_CONS_ELEMENT_KEY;
import static blue.language.utils.CanonicalIdentityConstants.LIST_CONS_KEY;
import static blue.language.utils.CanonicalIdentityConstants.LIST_CONS_PREVIOUS_KEY;
import static blue.language.utils.CanonicalIdentityConstants.LIST_SEED_KEY;
import static blue.language.utils.CanonicalIdentityConstants.LIST_SEED_VALUE;
import static blue.language.model.wire.BlueLanguageConstants.LIST_CONTROL_EMPTY;
import static blue.language.model.wire.BlueLanguageConstants.LIST_CONTROL_PREVIOUS;
import static blue.language.model.wire.BlueLanguageConstants.OBJECT_BLUE_ID;

/** Implements the one normative recursive-prefix Blue list identity fold. */
public final class ListBlueIdFold {

    private final Function<Object, String> hashProvider;

    /**
     * Creates a list fold.
     *
     * @param hashProvider canonical JSON hash function
     */
    public ListBlueIdFold(Function<Object, String> hashProvider) {
        this.hashProvider = Objects.requireNonNull(hashProvider, "hashProvider");
    }

    /**
     * Returns {@code L0 = id([])}, the accumulator for an empty list.
     *
     * @return empty-list BlueId
     */
    public String seedBlueId() {
        return hashProvider.apply(
                Collections.singletonMap(LIST_SEED_KEY, LIST_SEED_VALUE));
    }

    /**
     * Applies one exact {@code FOLD_LIST_ID} step.
     *
     * <p>Only the established prefix BlueId and appended element BlueId are
     * required; prior element content is deliberately absent from this API.</p>
     *
     * @param previousListBlueId established prefix accumulator
     * @param appendedElementBlueId exact appended element identity
     * @return next list accumulator
     */
    public String appendBlueId(
            String previousListBlueId,
            String appendedElementBlueId) {
        Objects.requireNonNull(previousListBlueId, "previousListBlueId");
        Objects.requireNonNull(appendedElementBlueId, "appendedElementBlueId");
        Map<String, Object> cons = new TreeMap<>(String::compareTo);
        cons.put(
                LIST_CONS_ELEMENT_KEY,
                Collections.singletonMap(
                        OBJECT_BLUE_ID,
                        appendedElementBlueId));
        cons.put(
                LIST_CONS_PREVIOUS_KEY,
                Collections.singletonMap(
                        OBJECT_BLUE_ID,
                        previousListBlueId));
        return hashProvider.apply(Collections.singletonMap(LIST_CONS_KEY, cons));
    }

    /**
     * Recomputes exactly one suffix from an established prefix accumulator.
     *
     * @param previousListBlueId accumulator immediately before the suffix
     * @param suffixElementBlueIds ordered element identities from the changed
     *                             index onward
     * @return final list BlueId
     */
    public String foldSuffix(
            String previousListBlueId,
            List<String> suffixElementBlueIds) {
        Objects.requireNonNull(suffixElementBlueIds, "suffixElementBlueIds");
        String accumulator = Objects.requireNonNull(
                previousListBlueId,
                "previousListBlueId");
        for (String elementBlueId : suffixElementBlueIds) {
            accumulator = appendBlueId(accumulator, elementBlueId);
        }
        return accumulator;
    }

    /**
     * Folds normalized element inputs through the same append operation.
     *
     * @param elements normalized list identity input
     * @param elementBlueId recursive element identity function
     * @return list BlueId
     */
    public String fold(
            List<Object> elements,
            Function<Object, String> elementBlueId) {
        Objects.requireNonNull(elements, "elements");
        Objects.requireNonNull(elementBlueId, "elementBlueId");
        boolean hasEstablishedPrefix = !elements.isEmpty()
                && isPreviousControl(elements.get(0));
        String accumulator = hasEstablishedPrefix
                ? previousBlueId(elements.get(0))
                : seedBlueId();
        int start = hasEstablishedPrefix ? 1 : 0;
        for (int index = start; index < elements.size(); index++) {
            Object element = elements.get(index);
            String identity = isEmptyPlaceholder(element)
                    ? emptyPlaceholderBlueId()
                    : elementBlueId.apply(element);
            accumulator = appendBlueId(accumulator, identity);
        }
        return accumulator;
    }

    /**
     * Calculates the protocol identity of the explicit {@code $empty} list
     * marker. The marker Boolean is a raw control value, not scalar-node
     * sugar.
     *
     * @return canonical empty-placeholder element BlueId
     */
    public String emptyPlaceholderBlueId() {
        Map<String, Object> helper = new TreeMap<>(String::compareTo);
        helper.put(
                LIST_CONTROL_EMPTY,
                Collections.singletonMap(
                        OBJECT_BLUE_ID,
                        hashProvider.apply(Boolean.TRUE)));
        return hashProvider.apply(helper);
    }

    private boolean isEmptyPlaceholder(Object element) {
        if (!(element instanceof Map)) {
            return false;
        }
        Map<?, ?> map = (Map<?, ?>) element;
        return map.size() == 1
                && Boolean.TRUE.equals(map.get(LIST_CONTROL_EMPTY));
    }

    private boolean isPreviousControl(Object element) {
        if (!(element instanceof Map)) {
            return false;
        }
        Map<?, ?> map = (Map<?, ?>) element;
        if (map.size() != 1 || !(map.get(LIST_CONTROL_PREVIOUS) instanceof Map)) {
            return false;
        }
        Map<?, ?> previous = (Map<?, ?>) map.get(LIST_CONTROL_PREVIOUS);
        return previous.size() == 1
                && previous.get(OBJECT_BLUE_ID) instanceof String;
    }

    private String previousBlueId(Object element) {
        Map<?, ?> map = (Map<?, ?>) element;
        Map<?, ?> previous = (Map<?, ?>) map.get(LIST_CONTROL_PREVIOUS);
        return (String) previous.get(OBJECT_BLUE_ID);
    }
}
