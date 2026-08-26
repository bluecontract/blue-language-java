package blue.language.processor;

import blue.language.snapshot.FrozenNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Exact immutable evidence for one effective contract-surface transition. */
final class ContractSurfaceDelta {

    enum Change {
        ADD,
        REPLACE,
        REMOVE
    }

    private final String beforeIdentity;
    private final String afterIdentity;
    private final List<ContractOccurrenceDelta> contracts;
    private final List<ChannelOccurrenceDelta> channels;
    private final List<OperationRouteDelta> operations;
    private final List<ProcessEmbeddedDeclarationDelta> processEmbedded;
    private final List<SubscriptionOccurrenceDelta> subscriptions;

    ContractSurfaceDelta(
            String beforeIdentity,
            String afterIdentity,
            List<ContractOccurrenceDelta> contracts,
            List<ChannelOccurrenceDelta> channels,
            List<OperationRouteDelta> operations,
            List<ProcessEmbeddedDeclarationDelta> processEmbedded,
            List<SubscriptionOccurrenceDelta> subscriptions) {
        this.beforeIdentity = requireText(beforeIdentity, "beforeIdentity");
        this.afterIdentity = requireText(afterIdentity, "afterIdentity");
        this.contracts = immutable(contracts, "contract delta");
        this.channels = immutable(channels, "channel delta");
        this.operations = immutable(operations, "operation route delta");
        this.processEmbedded = immutable(
                processEmbedded, "Process Embedded delta");
        this.subscriptions = immutable(
                subscriptions, "subscription delta");
    }

    String beforeIdentity() {
        return beforeIdentity;
    }

    String afterIdentity() {
        return afterIdentity;
    }

    List<ContractOccurrenceDelta> contracts() {
        return contracts;
    }

    List<ChannelOccurrenceDelta> channels() {
        return channels;
    }

    List<OperationRouteDelta> operations() {
        return operations;
    }

    List<ProcessEmbeddedDeclarationDelta> processEmbedded() {
        return processEmbedded;
    }

    List<SubscriptionOccurrenceDelta> subscriptions() {
        return subscriptions;
    }

    private static <T> List<T> immutable(List<T> source, String label) {
        Objects.requireNonNull(source, label + "s");
        List<T> copy = new ArrayList<T>(source.size());
        for (T value : source) {
            copy.add(Objects.requireNonNull(value, label));
        }
        return Collections.unmodifiableList(copy);
    }

    /** Exact identity-bearing view of one recognized effective contract. */
    static final class ContractOccurrence {
        private final String scopePath;
        private final String key;
        private final List<String> sourceContributionNodeBlueIds;
        private final String effectiveTypeBlueId;
        private final String role;
        private final int order;
        private final Map<String, String> dispatchFields;
        private final Map<String, String> headerFieldBlueIds;
        private final List<String> executableBodyFields;
        private final Map<String, String> executableBodyNodeBlueIdsByField;
        private final Map<String, ExecutableBodySourceDescriptor>
                executableBodySourcesByField;
        private final List<String> deterministicDependencyNodeBlueIds;

        private ContractOccurrence(EffectiveContractSnapshot snapshot) {
            scopePath = requireText(snapshot.scopePath(), "scopePath");
            key = requireText(snapshot.key(), "key");
            sourceContributionNodeBlueIds = immutableText(
                    snapshot.sourceContributionNodeBlueIds());
            effectiveTypeBlueId = requireText(
                    snapshot.effectiveTypeBlueId(), "effectiveTypeBlueId");
            role = requireText(snapshot.role(), "role");
            order = snapshot.order();
            dispatchFields = orderedTextMap(snapshot.dispatchFields());
            headerFieldBlueIds = headerIdentities(snapshot.headerFields());
            executableBodyFields = immutableText(
                    snapshot.executableBodyFields());
            executableBodyNodeBlueIdsByField = orderedTextMap(
                    snapshot.executableBodyNodeBlueIdsByField());
            executableBodySourcesByField = orderedDescriptorMap(
                    snapshot.executableBodySourceDescriptorsByField());
            deterministicDependencyNodeBlueIds = immutableText(
                    snapshot.deterministicDependencyNodeBlueIds());
        }

        static ContractOccurrence from(EffectiveContractSnapshot snapshot) {
            return new ContractOccurrence(
                    Objects.requireNonNull(snapshot, "snapshot"));
        }

        String scopePath() {
            return scopePath;
        }

        String key() {
            return key;
        }

        List<String> sourceContributionNodeBlueIds() {
            return sourceContributionNodeBlueIds;
        }

        String effectiveTypeBlueId() {
            return effectiveTypeBlueId;
        }

        String role() {
            return role;
        }

        int order() {
            return order;
        }

        Map<String, String> dispatchFields() {
            return dispatchFields;
        }

        Map<String, String> headerFieldBlueIds() {
            return headerFieldBlueIds;
        }

        List<String> executableBodyFields() {
            return executableBodyFields;
        }

        Map<String, String> executableBodyNodeBlueIdsByField() {
            return executableBodyNodeBlueIdsByField;
        }

        Map<String, ExecutableBodySourceDescriptor>
        executableBodySourcesByField() {
            return executableBodySourcesByField;
        }

        List<String> deterministicDependencyNodeBlueIds() {
            return deterministicDependencyNodeBlueIds;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof ContractOccurrence)) {
                return false;
            }
            ContractOccurrence that = (ContractOccurrence) other;
            return order == that.order
                    && scopePath.equals(that.scopePath)
                    && key.equals(that.key)
                    && sourceContributionNodeBlueIds.equals(
                            that.sourceContributionNodeBlueIds)
                    && effectiveTypeBlueId.equals(that.effectiveTypeBlueId)
                    && role.equals(that.role)
                    && dispatchFields.equals(that.dispatchFields)
                    && headerFieldBlueIds.equals(that.headerFieldBlueIds)
                    && executableBodyFields.equals(that.executableBodyFields)
                    && executableBodyNodeBlueIdsByField.equals(
                            that.executableBodyNodeBlueIdsByField)
                    && executableBodySourcesByField.equals(
                            that.executableBodySourcesByField)
                    && deterministicDependencyNodeBlueIds.equals(
                            that.deterministicDependencyNodeBlueIds);
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                    scopePath, key, sourceContributionNodeBlueIds,
                    effectiveTypeBlueId, role, order, dispatchFields,
                    headerFieldBlueIds, executableBodyFields,
                    executableBodyNodeBlueIdsByField,
                    executableBodySourcesByField,
                    deterministicDependencyNodeBlueIds);
        }

        private static Map<String, String> headerIdentities(
                Map<String, FrozenNode> source) {
            Map<String, String> identities =
                    new LinkedHashMap<String, String>();
            for (String key : orderedKeys(source)) {
                identities.put(key, Objects.requireNonNull(
                        source.get(key), "header field").blueId());
            }
            return Collections.unmodifiableMap(identities);
        }

        private static Map<String, String> orderedTextMap(
                Map<String, String> source) {
            Map<String, String> ordered = new LinkedHashMap<String, String>();
            for (String key : orderedKeys(source)) {
                ordered.put(
                        requireText(key, "map key"),
                        requireText(source.get(key), "map value"));
            }
            return Collections.unmodifiableMap(ordered);
        }

        private static Map<String, ExecutableBodySourceDescriptor>
        orderedDescriptorMap(
                Map<String, ExecutableBodySourceDescriptor> source) {
            Map<String, ExecutableBodySourceDescriptor> ordered =
                    new LinkedHashMap<
                            String, ExecutableBodySourceDescriptor>();
            for (String key : orderedKeys(source)) {
                ordered.put(
                        requireText(key, "descriptor key"),
                        Objects.requireNonNull(
                                source.get(key), "body descriptor"));
            }
            return Collections.unmodifiableMap(ordered);
        }

        private static List<String> orderedKeys(Map<String, ?> source) {
            List<String> keys = new ArrayList<String>(
                    Objects.requireNonNull(source, "source").keySet());
            Collections.sort(keys, ExternalOrderKey::compareTextCodePoints);
            return keys;
        }
    }

    /** One added, replaced, or removed effective contract occurrence. */
    static class ContractOccurrenceDelta {
        private final Change change;
        private final ContractOccurrence before;
        private final ContractOccurrence after;

        ContractOccurrenceDelta(
                Change change,
                ContractOccurrence before,
                ContractOccurrence after) {
            this.change = Objects.requireNonNull(change, "change");
            requireShape(change, before, after);
            requireSameLocation(change, before, after);
            this.before = before;
            this.after = after;
        }

        Change change() {
            return change;
        }

        ContractOccurrence before() {
            return before;
        }

        ContractOccurrence after() {
            return after;
        }

        String scopePath() {
            return selected().scopePath();
        }

        String key() {
            return selected().key();
        }

        private ContractOccurrence selected() {
            return after != null ? after : before;
        }
    }

    /** Channel-specific view of a changed effective occurrence. */
    static final class ChannelOccurrenceDelta extends ContractOccurrenceDelta {
        ChannelOccurrenceDelta(
                Change change,
                ContractOccurrence before,
                ContractOccurrence after) {
            super(change, before, after);
            if ((before != null && !isChannel(before))
                    || (after != null && !isChannel(after))) {
                throw new IllegalArgumentException(
                        "Channel delta contains a non-channel occurrence");
            }
        }
    }

    /** Callable Handler-derived operation-route transition. */
    static final class OperationRouteDelta extends ContractOccurrenceDelta {
        OperationRouteDelta(
                Change change,
                ContractOccurrence before,
                ContractOccurrence after) {
            super(change, before, after);
            if ((before != null && !isHandler(before))
                    || (after != null && !isHandler(after))) {
                throw new IllegalArgumentException(
                        "Operation delta contains a non-Handler occurrence");
            }
        }

        String routeKind() {
            return "handler-derived";
        }
    }

    /** Exact effective Process Embedded declaration at one scope. */
    static final class ProcessEmbeddedDeclaration {
        private final ContractOccurrence occurrence;
        private final List<String> explicitPaths;
        private final List<String> collectionPaths;

        ProcessEmbeddedDeclaration(
                ContractOccurrence occurrence,
                List<String> explicitPaths,
                List<String> collectionPaths) {
            this.occurrence = Objects.requireNonNull(
                    occurrence, "occurrence");
            if (!EffectiveContractSnapshotConstants.Role.PROCESS_EMBEDDED
                    .equals(occurrence.role())) {
                throw new IllegalArgumentException(
                        "Process Embedded declaration has the wrong role");
            }
            this.explicitPaths = immutableText(explicitPaths);
            this.collectionPaths = immutableText(collectionPaths);
        }

        ContractOccurrence occurrence() {
            return occurrence;
        }

        List<String> explicitPaths() {
            return explicitPaths;
        }

        List<String> collectionPaths() {
            return collectionPaths;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof ProcessEmbeddedDeclaration)) {
                return false;
            }
            ProcessEmbeddedDeclaration that =
                    (ProcessEmbeddedDeclaration) other;
            return occurrence.equals(that.occurrence)
                    && explicitPaths.equals(that.explicitPaths)
                    && collectionPaths.equals(that.collectionPaths);
        }

        @Override
        public int hashCode() {
            return Objects.hash(occurrence, explicitPaths, collectionPaths);
        }
    }

    /** One complete Process Embedded declaration transition. */
    static final class ProcessEmbeddedDeclarationDelta {
        private final Change change;
        private final ProcessEmbeddedDeclaration before;
        private final ProcessEmbeddedDeclaration after;

        ProcessEmbeddedDeclarationDelta(
                Change change,
                ProcessEmbeddedDeclaration before,
                ProcessEmbeddedDeclaration after) {
            this.change = Objects.requireNonNull(change, "change");
            requireShape(change, before, after);
            if (change == Change.REPLACE
                    && (!before.occurrence().scopePath().equals(
                            after.occurrence().scopePath())
                    || !before.occurrence().key().equals(
                            after.occurrence().key()))) {
                throw new IllegalArgumentException(
                        "Process Embedded replacement changes occurrence location");
            }
            this.before = before;
            this.after = after;
        }

        Change change() {
            return change;
        }

        ProcessEmbeddedDeclaration before() {
            return before;
        }

        ProcessEmbeddedDeclaration after() {
            return after;
        }

        String scopePath() {
            return selected().occurrence().scopePath();
        }

        String key() {
            return selected().occurrence().key();
        }

        private ProcessEmbeddedDeclaration selected() {
            return after != null ? after : before;
        }
    }

    /** Exact pre-commit external-subscription occurrence transition. */
    static final class SubscriptionOccurrenceDelta {
        private final Change change;
        private final SubscriptionDelta.Entry before;
        private final SubscriptionDelta.Entry after;

        SubscriptionOccurrenceDelta(
                Change change,
                SubscriptionDelta.Entry before,
                SubscriptionDelta.Entry after) {
            this.change = Objects.requireNonNull(change, "change");
            requireShape(change, before, after);
            if (change == Change.REPLACE
                    && (!before.scopePath().equals(after.scopePath())
                    || !before.channelKey().equals(after.channelKey()))) {
                throw new IllegalArgumentException(
                        "Subscription replacement changes occurrence location");
            }
            this.before = before;
            this.after = after;
        }

        Change change() {
            return change;
        }

        SubscriptionDelta.Entry before() {
            return before;
        }

        SubscriptionDelta.Entry after() {
            return after;
        }

        String scopePath() {
            return after != null ? after.scopePath() : before.scopePath();
        }

        String key() {
            return after != null ? after.channelKey() : before.channelKey();
        }
    }

    private static boolean isChannel(ContractOccurrence occurrence) {
        return EffectiveContractSnapshotConstants.Role.PROCESSOR_CHANNEL
                .equals(occurrence.role())
                || EffectiveContractSnapshotConstants.Role.EXTERNAL_CHANNEL
                .equals(occurrence.role());
    }

    private static boolean isHandler(ContractOccurrence occurrence) {
        return EffectiveContractSnapshotConstants.Role.HANDLER.equals(
                occurrence.role());
    }

    private static void requireSameLocation(
            Change change,
            ContractOccurrence before,
            ContractOccurrence after) {
        if (change == Change.REPLACE
                && (!before.scopePath().equals(after.scopePath())
                || !before.key().equals(after.key()))) {
            throw new IllegalArgumentException(
                    "Contract replacement changes occurrence location");
        }
    }

    private static void requireShape(
            Change change,
            Object before,
            Object after) {
        boolean valid = change == Change.ADD
                ? before == null && after != null
                : change == Change.REMOVE
                ? before != null && after == null
                : before != null && after != null;
        if (!valid) {
            throw new IllegalArgumentException(
                    "Invalid " + change + " delta shape");
        }
    }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label);
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be empty");
        }
        return checked;
    }

    private static List<String> immutableText(List<String> source) {
        List<String> result = new ArrayList<String>();
        for (String value : Objects.requireNonNull(source, "source")) {
            result.add(requireText(value, "text value"));
        }
        return Collections.unmodifiableList(result);
    }
}
