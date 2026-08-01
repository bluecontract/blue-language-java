package blue.language.processor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Accumulates the exact deterministic dependency proof for one evaluation. */
final class ExternalChannelDependencyCapture {

    private final List<String> intrinsic;
    private final Map<String, ExternalChannelDependencySnapshot.Entry>
            entries = new LinkedHashMap<>();
    private final Map<String, ExternalChannelDependencySnapshot.TypeFamily>
            typeFamilies = new LinkedHashMap<>();
    private final Map<String, ExternalChannelDependencySnapshot.ChannelEntry>
            channelEntries = new LinkedHashMap<>();
    private List<String> channelCatalogContractKeys =
            Collections.emptyList();
    private boolean wholeSurface;
    private boolean wholeChannelCatalog;

    ExternalChannelDependencyCapture(List<String> intrinsic) {
        this.intrinsic = new ArrayList<>(intrinsic);
    }

    void record(ExternalChannelFunctionResolver.Header header) {
        EffectiveContractSnapshot snapshot = header.snapshotInternal();
        record(new ExternalChannelDependencySnapshot.Entry(
                snapshot.key(),
                snapshot.order(),
                snapshot.effectiveTypeBlueId(),
                snapshot.sourceContributionNodeBlueIds(),
                header.dependencies()
                        .deterministicDependencyNodeBlueIds(),
                header.checkpointDomainBlueId()));
        for (ExternalChannelDependencySnapshot.Entry dependency
                : header.dependencies().entries()) {
            record(dependency);
        }
        for (ExternalChannelDependencySnapshot.TypeFamily family
                : header.dependencies().typeFamilies()) {
            record(family);
        }
        for (ExternalChannelDependencySnapshot.ChannelEntry entry
                : header.dependencies().channelEntries()) {
            record(entry);
        }
        wholeSurface |= header.dependencies()
                .wholeSameScopeExternalSurface();
        wholeChannelCatalog |= header.dependencies()
                .wholeSameScopeChannelCatalog();
        if (header.dependencies().wholeSameScopeChannelCatalog()) {
            recordChannelCatalogKeys(
                    header.dependencies().channelCatalogContractKeys());
        }
    }

    void record(ExternalChannelDependencySnapshot.Entry entry) {
        ExternalChannelDependencySnapshot.Entry prior =
                entries.get(entry.channelKey());
        if (prior != null && !prior.equals(entry)) {
            throw new IllegalStateException(
                    "Conflicting same-scope External Channel dependency "
                            + "snapshot for " + entry.channelKey());
        }
        if (prior == null) {
            entries.put(entry.channelKey(), entry);
        }
    }

    void typeFamily(
            String excludingChannelKey,
            String effectiveTypeBlueId,
            ExternalChannelDependencySnapshot.TypeMatchMode matchMode,
            List<EffectiveContractSnapshot> matching) {
        List<ExternalChannelDependencySnapshot.Member> members =
                new ArrayList<>(matching.size());
        for (EffectiveContractSnapshot snapshot : matching) {
            members.add(new ExternalChannelDependencySnapshot.Member(
                    snapshot.key(),
                    snapshot.order(),
                    snapshot.effectiveTypeBlueId(),
                    snapshot.sourceContributionNodeBlueIds(),
                    snapshot.deterministicDependencyNodeBlueIds()));
        }
        record(new ExternalChannelDependencySnapshot.TypeFamily(
                excludingChannelKey,
                effectiveTypeBlueId,
                matchMode,
                members));
    }

    void wholeSurface() {
        wholeSurface = true;
    }

    void record(ExternalChannelDependencySnapshot.ChannelEntry entry) {
        ExternalChannelDependencySnapshot.ChannelEntry prior =
                channelEntries.get(entry.channelKey());
        if (prior != null && !prior.equals(entry)) {
            throw new IllegalStateException(
                    "Conflicting same-scope Channel header dependency "
                            + "snapshot for " + entry.channelKey());
        }
        if (prior == null) {
            channelEntries.put(entry.channelKey(), entry);
        }
    }

    void channelCatalog(
            List<ExternalChannelDependencySnapshot.ChannelEntry> entries,
            List<String> contractKeys) {
        for (ExternalChannelDependencySnapshot.ChannelEntry entry : entries) {
            record(entry);
        }
        recordChannelCatalogKeys(contractKeys);
        wholeChannelCatalog = true;
    }

    ExternalChannelDependencySnapshot snapshot() {
        if (intrinsic.isEmpty()
                && entries.isEmpty()
                && typeFamilies.isEmpty()
                && !wholeSurface
                && channelEntries.isEmpty()
                && !wholeChannelCatalog) {
            return ExternalChannelDependencySnapshot.none();
        }
        return new ExternalChannelDependencySnapshot(
                intrinsic,
                new ArrayList<>(entries.values()),
                new ArrayList<>(typeFamilies.values()),
                wholeSurface,
                new ArrayList<>(channelEntries.values()),
                wholeChannelCatalog,
                wholeChannelCatalog
                        ? channelCatalogContractKeys
                        : Collections.<String>emptyList());
    }

    private void record(
            ExternalChannelDependencySnapshot.TypeFamily family) {
        String selector = family.excludingChannelKey()
                + ProcessorIdentityConstants.SELECTOR_COMPONENT_DELIMITER
                + family.matchMode().name()
                + ProcessorIdentityConstants.SELECTOR_COMPONENT_DELIMITER
                + family.effectiveTypeBlueId();
        ExternalChannelDependencySnapshot.TypeFamily prior =
                typeFamilies.get(selector);
        if (prior != null && !prior.equals(family)) {
            throw new IllegalStateException(
                    "Conflicting same-scope External Channel type-family "
                            + "snapshot for "
                            + family.effectiveTypeBlueId()
                            + " excluding "
                            + family.excludingChannelKey());
        }
        if (prior == null) {
            typeFamilies.put(selector, family);
        }
    }

    private void recordChannelCatalogKeys(List<String> contractKeys) {
        List<String> exact = ExternalChannelFunctionRules
                .immutableEffectiveContractKeys(contractKeys);
        if (!channelCatalogContractKeys.isEmpty()
                && !channelCatalogContractKeys.equals(exact)) {
            throw new IllegalStateException(
                    "Conflicting same-scope Channel catalog raw-key "
                            + "membership");
        }
        channelCatalogContractKeys = exact;
    }
}
