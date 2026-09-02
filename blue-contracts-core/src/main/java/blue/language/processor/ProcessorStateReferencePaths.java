package blue.language.processor;

import blue.language.model.Node;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Public access to reference paths whose processor-state meaning is established
 * by a normalized executable-body patch effect.
 *
 * <p>The returned paths identify exact initialization witnesses carried by
 * supported {@code add} or {@code replace} effects before those effects are
 * applied. Recognition validates the runtime patch pointer and does not treat
 * arbitrary patch-shaped application data as processor state. Each invocation
 * returns an immutable snapshot.</p>
 */
public final class ProcessorStateReferencePaths {

    private ProcessorStateReferencePaths() {
    }

    /**
     * Finds exact processor-state witnesses carried by unapplied patch effects.
     *
     * @param executableBody authored executable body to inspect
     * @return immutable Json Pointer paths relative to {@code executableBody}
     * @throws NullPointerException when {@code executableBody} is {@code null}
     */
    public static Set<String> inPatchEffects(Node executableBody) {
        Objects.requireNonNull(executableBody, "executableBody");
        return Collections.unmodifiableSet(new LinkedHashSet<String>(
                ProcessorStateReferencePathCatalog.findInPatchEffects(
                        executableBody)));
    }
}
