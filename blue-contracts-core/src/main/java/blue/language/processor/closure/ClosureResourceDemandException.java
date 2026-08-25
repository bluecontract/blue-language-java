package blue.language.processor.closure;

import blue.language.processor.NoncommittingExecutionException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Internal non-error exit from tentative execution before any publication. */
final class ClosureResourceDemandException
        extends NoncommittingExecutionException {

    private static final long serialVersionUID = 1L;

    private final List<ClosureResourceDemand> demands;

    ClosureResourceDemandException(
            Collection<? extends ClosureResourceDemand> demands) {
        super("Closure execution requires exact resource evidence");
        ClosureAttemptResult canonical = ClosureAttemptResult.needsResources(
                Objects.requireNonNull(demands, "demands"));
        this.demands = Collections.unmodifiableList(
                new ArrayList<ClosureResourceDemand>(
                        canonical.resourceDemands()));
    }

    List<ClosureResourceDemand> demands() {
        return demands;
    }
}
