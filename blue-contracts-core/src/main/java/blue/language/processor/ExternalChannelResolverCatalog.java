package blue.language.processor;

import blue.language.mapping.NodeToObjectConverter;
import blue.language.processor.model.ChannelContract;
import blue.language.processor.model.Contract;
import blue.language.snapshot.FrozenNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Immutable same-scope contract catalog used by one function resolver. */
final class ExternalChannelResolverCatalog {

    private final ContractProcessorRegistry registry;
    private final NodeToObjectConverter converter;
    private final ContractBundle bundle;
    private final List<String> effectiveContractKeys;

    ExternalChannelResolverCatalog(
            ContractProcessorRegistry registry,
            NodeToObjectConverter converter,
            ContractBundle bundle,
            List<String> effectiveContractKeys) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.converter = Objects.requireNonNull(converter, "converter");
        this.bundle = Objects.requireNonNull(bundle, "bundle");
        this.effectiveContractKeys =
                ExternalChannelFunctionRules
                        .immutableEffectiveContractKeys(
                                effectiveContractKeys != null
                                        ? effectiveContractKeys
                                        : snapshotKeys(bundle));
    }

    List<String> effectiveContractKeys() {
        return effectiveContractKeys;
    }

    boolean effectiveContractPresent(String key) {
        return effectiveContractKeys.contains(key);
    }

    EffectiveContractSnapshot effectiveContractSnapshot(String key) {
        return bundle.effectiveContractSnapshot(key);
    }

    EffectiveContractSnapshot requireExternalSnapshot(String key) {
        EffectiveContractSnapshot snapshot =
                bundle.effectiveContractSnapshot(key);
        if (snapshot == null) {
            throw new IllegalStateException(
                    "Missing same-scope External Channel dependency: "
                            + key);
        }
        if (!EffectiveContractSnapshotConstants
                .Role.EXTERNAL_CHANNEL.equals(snapshot.role())) {
            throw new IllegalStateException(
                    "Same-scope dependency is not an External Channel: "
                            + key);
        }
        return snapshot;
    }

    FrozenNode requireContractNode(EffectiveContractSnapshot snapshot) {
        FrozenNode content = bundle.contractNode(snapshot.key());
        if (content == null) {
            throw new IllegalStateException(
                    "External Channel effective content is unavailable at "
                            + snapshot.scopePath() + "/"
                            + snapshot.key());
        }
        return content;
    }

    ChannelContract freshChannel(EffectiveContractSnapshot snapshot) {
        Contract converted = converter.convertWithType(
                requireContractNode(snapshot).toNode(),
                Contract.class,
                false,
                bundle.canonicalTypeIdentities());
        if (!(converted instanceof ChannelContract)) {
            throw new IllegalStateException(
                    "External Channel could not be converted at "
                            + snapshot.scopePath() + "/"
                            + snapshot.key());
        }
        ChannelContract channel = (ChannelContract) converted;
        channel.setKey(snapshot.key());
        channel.setTypeBlueId(snapshot.effectiveTypeBlueId());
        return channel;
    }

    @SuppressWarnings("rawtypes")
    ExternalChannelSubscriptionFunctions subscriptionFunctions(
            EffectiveContractSnapshot snapshot) {
        ChannelContract probe = freshChannel(snapshot);
        ChannelProcessor processor =
                registry.lookupChannel(probe).orElse(null);
        ExternalChannelSubscriptionFunctions functions =
                processor != null
                        ? processor.externalSubscriptionFunctions()
                        : null;
        if (functions == null) {
            throw new IllegalStateException(
                    "External Channel runtime type does not expose supported "
                            + "immutable subscription functions: "
                            + snapshot.effectiveTypeBlueId());
        }
        return functions;
    }

    List<EffectiveContractSnapshot> externalSnapshots(String excludedKey) {
        List<EffectiveContractSnapshot> snapshots = new ArrayList<>();
        long externalCount = 0L;
        for (EffectiveContractSnapshot snapshot
                : bundle.effectiveContractSnapshots()) {
            if (EffectiveContractSnapshotConstants
                    .Role.EXTERNAL_CHANNEL.equals(snapshot.role())) {
                externalCount++;
                if (!snapshot.key().equals(excludedKey)) {
                    snapshots.add(snapshot);
                }
            }
        }
        long memberLimit = ExternalChannelFunctionRules.portableLimit(
                GasScheduleConstants.PortableLimit
                        .EXTERNAL_CHANNELS_PER_SCOPE);
        if (externalCount > memberLimit) {
            throw new IllegalStateException(
                    "Same-scope External Channel dependency surface exceeds "
                            + memberLimit);
        }
        snapshots.sort(snapshotComparator());
        return snapshots;
    }

    /** Returns Channel headers without evaluating subscription functions. */
    List<EffectiveContractSnapshot> channelSnapshots() {
        List<EffectiveContractSnapshot> snapshots = new ArrayList<>();
        for (EffectiveContractSnapshot snapshot
                : bundle.effectiveContractSnapshots()) {
            if (isChannelRole(snapshot.role())) {
                snapshots.add(snapshot);
            }
        }
        long memberLimit = ExternalChannelFunctionRules.portableLimit(
                GasScheduleConstants.PortableLimit
                        .EFFECTIVE_CONTRACTS_PER_SCOPE);
        if (snapshots.size() > memberLimit) {
            throw new IllegalStateException(
                    "Same-scope Channel header catalog exceeds "
                            + memberLimit);
        }
        snapshots.sort(snapshotComparator());
        return snapshots;
    }

    List<ExternalChannelDependencySnapshot.ChannelEntry>
    channelDependencyEntries() {
        List<ExternalChannelDependencySnapshot.ChannelEntry> entries =
                new ArrayList<>();
        for (EffectiveContractSnapshot snapshot : channelSnapshots()) {
            entries.add(channelDependencyEntry(channelSnapshot(snapshot)));
        }
        return Collections.unmodifiableList(entries);
    }

    ChannelMemberSnapshot channelSnapshot(String key) {
        EffectiveContractSnapshot snapshot =
                bundle.effectiveContractSnapshot(key);
        if (snapshot == null) {
            if (effectiveContractKeys.contains(key)) {
                throw new IllegalStateException(
                        "Same-scope contract is not a Channel: " + key);
            }
            return null;
        }
        if (!isChannelRole(snapshot.role())) {
            throw new IllegalStateException(
                    "Same-scope contract is not a Channel: " + key);
        }
        return channelSnapshot(snapshot);
    }

    ChannelMemberSnapshot channelSnapshot(
            EffectiveContractSnapshot snapshot) {
        return ChannelMemberSnapshot.from(
                snapshot, bundle.canonicalTypeIdentities());
    }

    ExternalChannelDependencySnapshot.ChannelEntry channelDependencyEntry(
            ChannelMemberSnapshot snapshot) {
        return new ExternalChannelDependencySnapshot.ChannelEntry(
                snapshot.channelKey(),
                snapshot.order(),
                snapshot.effectiveTypeBlueId(),
                snapshot.role(),
                snapshot.sourceContributionNodeBlueIds(),
                snapshot.deterministicDependencyNodeBlueIds(),
                snapshot.headerIdentityBlueId());
    }

    ExternalChannelDependencySnapshot.ChannelEntry declaredChannelEntry(
            ExternalChannelDependencySnapshot dependencies,
            String key) {
        for (ExternalChannelDependencySnapshot.ChannelEntry entry
                : dependencies.channelEntries()) {
            if (key.equals(entry.channelKey())) {
                return entry;
            }
        }
        return null;
    }

    ChannelMemberSnapshot handlerChannelForDispatch(
            EffectiveContractSnapshot source,
            String handlerChannelKey,
            ExternalChannelDependencySnapshot dependencies) {
        ChannelMemberSnapshot target = channelSnapshot(handlerChannelKey);
        if (target == null) {
            throw new IllegalStateException(
                    "External Channel handler target is absent from the "
                            + "same-scope Channel catalog: "
                            + handlerChannelKey);
        }
        if (source.key().equals(handlerChannelKey)) {
            return target;
        }
        ExternalChannelDependencySnapshot.ChannelEntry targetEntry =
                channelDependencyEntry(target);
        boolean covered = false;
        for (ExternalChannelDependencySnapshot.ChannelEntry entry
                : dependencies.channelEntries()) {
            if (targetEntry.equals(entry)) {
                covered = true;
                break;
            }
        }
        if (!covered) {
            throw new IllegalStateException(
                    "External Channel handler target was not declared as a "
                            + "same-scope Channel dependency at "
                            + source.scopePath() + "/" + source.key()
                            + ": " + handlerChannelKey);
        }
        return target;
    }

    boolean isChannelRole(String role) {
        return EffectiveContractSnapshotConstants
                .Role.EXTERNAL_CHANNEL.equals(role)
                || EffectiveContractSnapshotConstants
                .Role.PROCESSOR_CHANNEL.equals(role);
    }

    private static List<String> snapshotKeys(ContractBundle bundle) {
        List<String> keys = new ArrayList<>();
        for (EffectiveContractSnapshot snapshot
                : bundle.effectiveContractSnapshots()) {
            keys.add(snapshot.key());
        }
        return keys;
    }

    private Comparator<EffectiveContractSnapshot> snapshotComparator() {
        return new Comparator<EffectiveContractSnapshot>() {
            @Override
            public int compare(
                    EffectiveContractSnapshot left,
                    EffectiveContractSnapshot right) {
                int order = Integer.compare(left.order(), right.order());
                if (order != 0) {
                    return order;
                }
                int key = ExternalOrderKey.compareTextCodePoints(
                        left.key(), right.key());
                if (key != 0) {
                    return key;
                }
                return ExternalOrderKey.compareTextCodePoints(
                        left.effectiveTypeBlueId(),
                        right.effectiveTypeBlueId());
            }
        };
    }
}
