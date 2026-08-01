package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.processor.util.PointerUtils;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.processor.util.ProcessorPointerConstants;
import blue.language.model.wire.JsonPointer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Selects affected retained intervals and binds deterministic commit bounds.
 *
 * <p>The validator deliberately reuses the authoritative retained occurrence
 * value. Closing an interval must preserve its original activation revision
 * and event-order boundary exactly.</p>
 */
final class ActivationIntervalValidator {

    private final SubscriptionSurfaceRules rules;

    ActivationIntervalValidator(SubscriptionSurfaceRules rules) {
        this.rules = rules;
    }

    /** Returns retained occurrences whose reachability or dependencies changed. */
    Map<String, SubscriptionDelta.Entry> affectedRetainedSurface(
            SubscriptionSurfaceValidationContext context,
            Set<String> changedPaths) {
        Map<String, SubscriptionDelta.Entry> result = new LinkedHashMap<>();
        for (SubscriptionDelta.Entry interval
                : context.activeSubscriptionIntervals()) {
            if (isAffected(
                    interval,
                    changedPaths,
                    context.inputRoot(),
                    context.tentativeRoot())) {
                result.put(interval.occurrenceKey(), interval);
            }
        }
        return result;
    }

    /** Opens a new interval when commit coordinates were supplied. */
    SubscriptionDelta.Entry activate(
            SubscriptionDelta.Entry entry,
            SubscriptionSurfaceValidationContext context) {
        return hasCommittingInterval(context)
                ? entry.activatedAt(
                        context.committingRootRevision(),
                        context.currentEventOrderKey())
                : entry;
    }

    /** Closes a retained interval when commit coordinates were supplied. */
    SubscriptionDelta.Entry retire(
            SubscriptionDelta.Entry entry,
            SubscriptionSurfaceValidationContext context) {
        return hasCommittingInterval(context)
                ? entry.retiredAt(context.committingRootRevision())
                : entry;
    }

    private boolean isAffected(
            SubscriptionDelta.Entry interval,
            Set<String> changedPaths,
            Node inputRoot,
            Node tentativeRoot) {
        String scopePath = PointerUtils.normalizeScope(interval.scopePath());
        String contractPath = PointerUtils.resolvePointer(
                scopePath,
                ProcessorPointerConstants.relativeContractsEntry(
                        interval.channelKey()));
        if (rules.dependencyAffected(
                scopePath, contractPath, changedPaths)) {
            return true;
        }
        if (rules.sameScopeContractsAffected(scopePath, changedPaths)) {
            return true;
        }
        for (String changed : changedPaths) {
            // Replacing an ancestor changes every occurrence below it.
            if (PointerUtils.descendantOrEqual(scopePath, changed)) {
                return true;
            }
        }
        for (String ancestor : ancestorScopes(scopePath)) {
            String typePath = PointerUtils.resolvePointer(
                    ancestor,
                    ProcessorPointerConstants.RELATIVE_TYPE);
            String terminationPath = PointerUtils.resolvePointer(
                    ancestor,
                    ProcessorPointerConstants.RELATIVE_TERMINATED);
            String contractsPath = PointerUtils.resolvePointer(
                    ancestor,
                    ProcessorPointerConstants.RELATIVE_CONTRACTS);
            for (String changed : changedPaths) {
                if (rules.overlaps(changed, typePath)
                        || rules.overlaps(changed, terminationPath)
                        || changed.equals(contractsPath)
                        || processEmbeddedPathsChanged(
                                contractsPath, changed)
                        || processEmbeddedContractChanged(
                                ancestor,
                                contractsPath,
                                changed,
                                inputRoot,
                                tentativeRoot)) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<String> ancestorScopes(String scopePath) {
        List<String> ancestors = new ArrayList<>();
        String current = JsonPointer.ROOT;
        ancestors.add(current);
        List<String> segments = JsonPointer.split(scopePath);
        for (int index = 0; index + 1 < segments.size(); index++) {
            current = PointerUtils.appendPointer(
                    current, segments.get(index));
            ancestors.add(current);
        }
        return ancestors;
    }

    private boolean processEmbeddedPathsChanged(
            String contractsPath,
            String changedPath) {
        if (!PointerUtils.descendantOrEqual(changedPath, contractsPath)
                || changedPath.equals(contractsPath)) {
            return false;
        }
        List<String> relative = JsonPointer.split(
                PointerUtils.relativizePointer(
                        contractsPath, changedPath));
        return relative.size() >= 2
                && ProcessorContractConstants.KEY_PATHS.equals(
                        relative.get(1));
    }

    private boolean processEmbeddedContractChanged(
            String scopePath,
            String contractsPath,
            String changedPath,
            Node inputRoot,
            Node tentativeRoot) {
        if (!PointerUtils.descendantOrEqual(changedPath, contractsPath)
                || changedPath.equals(contractsPath)) {
            return false;
        }
        List<String> relative = JsonPointer.split(
                PointerUtils.relativizePointer(
                        contractsPath, changedPath));
        if (relative.isEmpty()) {
            return false;
        }
        String contractKey = relative.get(0);
        return isDirectProcessEmbeddedContract(
                inputRoot, scopePath, contractKey)
                || isDirectProcessEmbeddedContract(
                        tentativeRoot, scopePath, contractKey);
    }

    private boolean isDirectProcessEmbeddedContract(
            Node root,
            String scopePath,
            String contractKey) {
        Node scope = rules.nodeAtRoot(root, scopePath);
        Node contracts = scope != null ? scope.getContracts() : null;
        Node contract = contracts != null
                && contracts.getProperties() != null
                ? contracts.getProperties().get(contractKey)
                : null;
        return contract != null
                && RuntimeBlueIds.PROCESS_EMBEDDED.equals(
                        rules.recognizedType(contract));
    }

    private boolean hasCommittingInterval(
            SubscriptionSurfaceValidationContext context) {
        return context.committingRootRevision() != null
                && context.currentEventOrderKey() != null;
    }
}
