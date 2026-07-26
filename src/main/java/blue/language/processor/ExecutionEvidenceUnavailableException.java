package blue.language.processor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Host-side suspension raised when exact execution evidence has not yet been
 * acquired.
 *
 * <p>This is deliberately distinct from
 * {@link InvalidExecutionEvidenceException}: unavailable feeder/provider
 * evidence is not malformed Processing Document content and cannot become a
 * completed {@link DocumentProcessingResult}. When the missing evidence has
 * exact node identities, {@link DocumentProcessor#processAttempt} converts the
 * exception to {@link ProcessAttemptResult.Kind#NEEDS_RESOURCES}.</p>
 */
public final class ExecutionEvidenceUnavailableException
        extends RuntimeException {

    private final List<String> requiredExactBlueIds;

    public ExecutionEvidenceUnavailableException(String message) {
        this(message, Collections.<String>emptyList());
    }

    public ExecutionEvidenceUnavailableException(
            String message,
            Collection<String> requiredExactBlueIds) {
        super(Objects.requireNonNull(message, "message"));
        Objects.requireNonNull(
                requiredExactBlueIds, "requiredExactBlueIds");
        TreeSet<String> sorted = new TreeSet<>();
        for (String blueId : requiredExactBlueIds) {
            if (blueId == null || blueId.isEmpty()) {
                throw new IllegalArgumentException(
                        "Required exact BlueIds must be non-empty");
            }
            sorted.add(blueId);
        }
        this.requiredExactBlueIds = Collections.unmodifiableList(
                new ArrayList<>(sorted));
    }

    public List<String> requiredExactBlueIds() {
        return requiredExactBlueIds;
    }
}
