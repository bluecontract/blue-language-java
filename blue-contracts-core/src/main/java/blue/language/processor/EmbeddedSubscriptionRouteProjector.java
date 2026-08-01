package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.util.PointerUtils;
import blue.language.processor.util.ProcessorContractConstants;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Validates Process Embedded path declarations and projects absolute routes.
 */
final class EmbeddedSubscriptionRouteProjector {

    private final SubscriptionSurfaceRules rules;

    EmbeddedSubscriptionRouteProjector(SubscriptionSurfaceRules rules) {
        this.rules = rules;
    }

    /** Projects routes from a direct Process Embedded contract node. */
    List<String> project(Node embedded,
                         String scopePath,
                         String key,
                         GasSchedule schedule) {
        Node paths = rules.property(
                embedded,
                ProcessorContractConstants.KEY_PATHS);
        if (paths == null || paths.getItems() == null) {
            throw rules.invalid(
                    "Process Embedded paths must be a finite List",
                    scopePath,
                    key);
        }
        rules.requireLimit(
                GasScheduleConstants.PortableLimit
                        .PROCESS_EMBEDDED_PATHS_PER_SCOPE,
                paths.getItems().size(),
                schedule.portableLimit(
                        GasScheduleConstants.PortableLimit
                                .PROCESS_EMBEDDED_PATHS_PER_SCOPE),
                scopePath,
                key);
        List<String> result = new ArrayList<>();
        Set<String> unique = new LinkedHashSet<>();
        for (Node item : paths.getItems()) {
            Object value = item != null ? item.getValue() : null;
            if (!(value instanceof String)) {
                throw rules.invalid(
                        "Process Embedded path must be Text",
                        scopePath,
                        key);
            }
            addRoute(
                    (String) value,
                    scopePath,
                    key,
                    result,
                    unique);
        }
        return result;
    }

    /** Projects routes from the effective contract bundle path list. */
    List<String> project(List<String> paths,
                         String scopePath,
                         String key,
                         GasSchedule schedule) {
        if (paths == null) {
            throw rules.invalid(
                    "Process Embedded paths must be a finite List",
                    scopePath,
                    key);
        }
        rules.requireLimit(
                GasScheduleConstants.PortableLimit
                        .PROCESS_EMBEDDED_PATHS_PER_SCOPE,
                paths.size(),
                schedule.portableLimit(
                        GasScheduleConstants.PortableLimit
                                .PROCESS_EMBEDDED_PATHS_PER_SCOPE),
                scopePath,
                key);
        List<String> result = new ArrayList<>();
        Set<String> unique = new LinkedHashSet<>();
        for (String value : paths) {
            if (value == null) {
                throw rules.invalid(
                        "Process Embedded path must be Text",
                        scopePath,
                        key);
            }
            addRoute(value, scopePath, key, result, unique);
        }
        return result;
    }

    private void addRoute(
            String value,
            String scopePath,
            String key,
            List<String> result,
            Set<String> unique) {
        String relative;
        try {
            relative = PointerUtils.assertValidRuntimePointer(value);
        } catch (IllegalArgumentException exception) {
            throw rules.invalid(
                    "Invalid Process Embedded path: " + value,
                    scopePath,
                    key);
        }
        String target = PointerUtils.resolvePointer(scopePath, relative);
        if (target.equals(scopePath) || !unique.add(target)) {
            throw rules.invalid(
                    "Duplicate or cyclic Process Embedded path: " + value,
                    scopePath,
                    key);
        }
        for (String prior : result) {
            if (PointerUtils.descendantOrEqual(target, prior)
                    || PointerUtils.descendantOrEqual(prior, target)) {
                throw rules.invalid(
                        "Ambiguous Process Embedded paths: "
                                + prior + " and " + target,
                        scopePath,
                        key);
            }
        }
        result.add(target);
    }
}
