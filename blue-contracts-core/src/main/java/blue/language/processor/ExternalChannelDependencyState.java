package blue.language.processor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable normalized state behind one dependency snapshot facade. */
final class ExternalChannelDependencyState {

    private final List<String> intrinsicNodeBlueIds;
    private final List<ExternalChannelDependencySnapshot.Entry> entries;
    private final List<ExternalChannelDependencySnapshot.TypeFamily>
            typeFamilies;
    private final boolean wholeSameScopeExternalSurface;
    private final List<ExternalChannelDependencySnapshot.ChannelEntry>
            channelEntries;
    private final boolean wholeSameScopeChannelCatalog;
    private final List<String> channelCatalogContractKeys;
    private final List<String> deterministicDependencyNodeBlueIds;

    ExternalChannelDependencyState(
            List<String> intrinsicNodeBlueIds,
            List<ExternalChannelDependencySnapshot.Entry> entries,
            List<ExternalChannelDependencySnapshot.TypeFamily> typeFamilies,
            boolean wholeSameScopeExternalSurface,
            List<ExternalChannelDependencySnapshot.ChannelEntry>
                    channelEntries,
            boolean wholeSameScopeChannelCatalog,
            List<String> channelCatalogContractKeys) {
        this.intrinsicNodeBlueIds =
                ExternalChannelDependencyValidation.immutableText(
                        intrinsicNodeBlueIds, "intrinsic dependency");
        this.entries =
                ExternalChannelDependencyValidation.immutableEntries(entries);
        this.typeFamilies = ExternalChannelDependencyValidation
                .immutableTypeFamilies(typeFamilies);
        this.wholeSameScopeExternalSurface =
                wholeSameScopeExternalSurface;
        this.channelEntries = ExternalChannelDependencyValidation
                .immutableChannelEntries(channelEntries);
        this.wholeSameScopeChannelCatalog = wholeSameScopeChannelCatalog;
        this.channelCatalogContractKeys = wholeSameScopeChannelCatalog
                ? ExternalChannelDependencyValidation.immutableCatalogKeys(
                        channelCatalogContractKeys)
                : ExternalChannelDependencyValidation.requireNoCatalogKeys(
                        channelCatalogContractKeys);
        verifyWholeCatalogMembership();
        this.deterministicDependencyNodeBlueIds =
                buildDeterministicIdentities();
    }

    List<String> intrinsicNodeBlueIds() {
        return intrinsicNodeBlueIds;
    }

    List<ExternalChannelDependencySnapshot.Entry> entries() {
        return entries;
    }

    List<ExternalChannelDependencySnapshot.TypeFamily> typeFamilies() {
        return typeFamilies;
    }

    boolean wholeSameScopeExternalSurface() {
        return wholeSameScopeExternalSurface;
    }

    List<ExternalChannelDependencySnapshot.ChannelEntry> channelEntries() {
        return channelEntries;
    }

    boolean wholeSameScopeChannelCatalog() {
        return wholeSameScopeChannelCatalog;
    }

    List<String> channelCatalogContractKeys() {
        return channelCatalogContractKeys;
    }

    List<String> deterministicDependencyNodeBlueIds() {
        return deterministicDependencyNodeBlueIds;
    }

    boolean isEmpty() {
        return intrinsicNodeBlueIds.isEmpty()
                && entries.isEmpty()
                && typeFamilies.isEmpty()
                && !wholeSameScopeExternalSurface
                && channelEntries.isEmpty()
                && !wholeSameScopeChannelCatalog
                && channelCatalogContractKeys.isEmpty();
    }

    boolean covers(ExternalChannelDependencyState demanded) {
        if (demanded == null || demanded.isEmpty()) {
            return true;
        }
        if (demanded.wholeSameScopeExternalSurface
                && !wholeSameScopeExternalSurface) {
            return false;
        }
        if (demanded.wholeSameScopeChannelCatalog
                && !wholeSameScopeChannelCatalog) {
            return false;
        }
        if (!intrinsicNodeBlueIds.containsAll(
                demanded.intrinsicNodeBlueIds)) {
            return false;
        }
        if (!containsEntries(demanded.entries)
                || !containsFamilies(demanded.typeFamilies)
                || !containsChannels(demanded.channelEntries)) {
            return false;
        }
        return !demanded.wholeSameScopeChannelCatalog
                || (channelEntries.equals(demanded.channelEntries)
                && channelCatalogContractKeys.equals(
                        demanded.channelCatalogContractKeys));
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof ExternalChannelDependencyState)) {
            return false;
        }
        ExternalChannelDependencyState state =
                (ExternalChannelDependencyState) other;
        return intrinsicNodeBlueIds.equals(state.intrinsicNodeBlueIds)
                && entries.equals(state.entries)
                && typeFamilies.equals(state.typeFamilies)
                && wholeSameScopeExternalSurface
                == state.wholeSameScopeExternalSurface
                && channelEntries.equals(state.channelEntries)
                && wholeSameScopeChannelCatalog
                == state.wholeSameScopeChannelCatalog
                && channelCatalogContractKeys.equals(
                        state.channelCatalogContractKeys);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                intrinsicNodeBlueIds,
                entries,
                typeFamilies,
                wholeSameScopeExternalSurface,
                channelEntries,
                wholeSameScopeChannelCatalog,
                channelCatalogContractKeys);
    }

    private void verifyWholeCatalogMembership() {
        if (wholeSameScopeChannelCatalog
                && !channelCatalogContractKeys.containsAll(
                        ExternalChannelDependencyValidation.channelEntryKeys(
                                channelEntries))) {
            throw new IllegalArgumentException(
                    "Channel catalog raw-key membership omits a Channel entry");
        }
    }

    private List<String> buildDeterministicIdentities() {
        List<String> identities = new ArrayList<>(intrinsicNodeBlueIds);
        for (ExternalChannelDependencySnapshot.Entry entry : entries) {
            identities.add(entry.identityBlueId());
        }
        for (ExternalChannelDependencySnapshot.TypeFamily family
                : typeFamilies) {
            identities.add(family.identityBlueId());
        }
        if (wholeSameScopeExternalSurface) {
            identities.add(ExternalChannelDependencyIdentities.surface(
                    identities));
        }
        for (ExternalChannelDependencySnapshot.ChannelEntry entry
                : channelEntries) {
            identities.add(entry.identityBlueId());
        }
        if (wholeSameScopeChannelCatalog) {
            identities.add(ExternalChannelDependencyIdentities.channelCatalog(
                    channelEntries, channelCatalogContractKeys));
        }
        return Collections.unmodifiableList(identities);
    }

    private boolean containsEntries(
            List<ExternalChannelDependencySnapshot.Entry> demanded) {
        Map<String, ExternalChannelDependencySnapshot.Entry> available =
                new LinkedHashMap<>();
        for (ExternalChannelDependencySnapshot.Entry entry : entries) {
            available.put(entry.channelKey(), entry);
        }
        for (ExternalChannelDependencySnapshot.Entry entry : demanded) {
            if (!entry.equals(available.get(entry.channelKey()))) {
                return false;
            }
        }
        return true;
    }

    private boolean containsFamilies(
            List<ExternalChannelDependencySnapshot.TypeFamily> demanded) {
        Map<String, ExternalChannelDependencySnapshot.TypeFamily> available =
                new LinkedHashMap<>();
        for (ExternalChannelDependencySnapshot.TypeFamily family
                : typeFamilies) {
            available.put(family.selectorKey(), family);
        }
        for (ExternalChannelDependencySnapshot.TypeFamily family : demanded) {
            if (!family.equals(available.get(family.selectorKey()))) {
                return false;
            }
        }
        return true;
    }

    private boolean containsChannels(
            List<ExternalChannelDependencySnapshot.ChannelEntry> demanded) {
        Map<String, ExternalChannelDependencySnapshot.ChannelEntry> available =
                new LinkedHashMap<>();
        for (ExternalChannelDependencySnapshot.ChannelEntry entry
                : channelEntries) {
            available.put(entry.channelKey(), entry);
        }
        for (ExternalChannelDependencySnapshot.ChannelEntry entry : demanded) {
            if (!entry.equals(available.get(entry.channelKey()))) {
                return false;
            }
        }
        return true;
    }
}
