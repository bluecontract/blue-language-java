package blue.language.processor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable result of evaluating one indexed Root/event subscription surface.
 */
public final class IndexedDeliveryPreparation {

    private final ExternalDeliveryPlan deliveryPlan;
    private final List<IndexedDeliveryDiagnostic> diagnostics;

    IndexedDeliveryPreparation(
            ExternalDeliveryPlan deliveryPlan,
            List<IndexedDeliveryDiagnostic> diagnostics) {
        this.deliveryPlan = Objects.requireNonNull(
                deliveryPlan, "deliveryPlan");
        this.diagnostics = Collections.unmodifiableList(
                new ArrayList<>(Objects.requireNonNull(
                        diagnostics, "diagnostics")));
    }

    /**
     * Returns the independently verified exact delivery plan.
     *
     * @return immutable revision-complete plan
     */
    public ExternalDeliveryPlan deliveryPlan() {
        return deliveryPlan;
    }

    /**
     * Returns evaluated facts for the complete active interval surface.
     *
     * @return immutable diagnostics in canonical occurrence order
     */
    public List<IndexedDeliveryDiagnostic> diagnostics() {
        return diagnostics;
    }
}
