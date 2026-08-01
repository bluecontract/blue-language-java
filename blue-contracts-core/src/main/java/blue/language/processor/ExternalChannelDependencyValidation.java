package blue.language.processor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Validates and defensively freezes external dependency evidence. */
final class ExternalChannelDependencyValidation {

    private ExternalChannelDependencyValidation() {
    }

    static List<String> immutableText(
            List<String> supplied,
            String label) {
        Objects.requireNonNull(supplied, label);
        List<String> copy = new ArrayList<>(supplied.size());
        Set<String> unique = new LinkedHashSet<>();
        for (String value : supplied) {
            if (value == null || value.isEmpty() || !unique.add(value)) {
                throw new IllegalArgumentException(
                        "Invalid or duplicate " + label + ": " + value);
            }
            copy.add(value);
        }
        return Collections.unmodifiableList(copy);
    }

    static String requireText(String value, String label) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(
                    label + " must be non-empty");
        }
        return value;
    }

    static List<ExternalChannelDependencySnapshot.Entry> immutableEntries(
            List<ExternalChannelDependencySnapshot.Entry> supplied) {
        Objects.requireNonNull(supplied, "entries");
        List<ExternalChannelDependencySnapshot.Entry> copy =
                new ArrayList<>(supplied.size());
        Set<String> keys = new LinkedHashSet<>();
        for (ExternalChannelDependencySnapshot.Entry entry : supplied) {
            ExternalChannelDependencySnapshot.Entry exact =
                    Objects.requireNonNull(entry, "dependency entry");
            if (!keys.add(exact.channelKey())) {
                throw new IllegalArgumentException(
                        "Duplicate External Channel dependency key: "
                                + exact.channelKey());
            }
            copy.add(exact);
        }
        return Collections.unmodifiableList(copy);
    }

    static List<ExternalChannelDependencySnapshot.TypeFamily>
    immutableTypeFamilies(
            List<ExternalChannelDependencySnapshot.TypeFamily> supplied) {
        Objects.requireNonNull(supplied, "typeFamilies");
        List<ExternalChannelDependencySnapshot.TypeFamily> copy =
                new ArrayList<>(supplied.size());
        Set<String> selectors = new LinkedHashSet<>();
        for (ExternalChannelDependencySnapshot.TypeFamily family : supplied) {
            ExternalChannelDependencySnapshot.TypeFamily exact =
                    Objects.requireNonNull(family, "type family");
            if (!selectors.add(exact.selectorKey())) {
                throw new IllegalArgumentException(
                        "Duplicate External Channel dependency type-family "
                                + "selector: " + exact.selectorKey());
            }
            copy.add(exact);
        }
        return Collections.unmodifiableList(copy);
    }

    static List<ExternalChannelDependencySnapshot.ChannelEntry>
    immutableChannelEntries(
            List<ExternalChannelDependencySnapshot.ChannelEntry> supplied) {
        Objects.requireNonNull(supplied, "channelEntries");
        List<ExternalChannelDependencySnapshot.ChannelEntry> copy =
                new ArrayList<>(supplied.size());
        Set<String> keys = new LinkedHashSet<>();
        for (ExternalChannelDependencySnapshot.ChannelEntry entry : supplied) {
            ExternalChannelDependencySnapshot.ChannelEntry exact =
                    Objects.requireNonNull(
                            entry, "Channel dependency entry");
            if (!keys.add(exact.channelKey())) {
                throw new IllegalArgumentException(
                        "Duplicate Channel dependency key: "
                                + exact.channelKey());
            }
            copy.add(exact);
        }
        return Collections.unmodifiableList(copy);
    }

    static List<String> immutableCatalogKeys(List<String> supplied) {
        List<String> keys = new ArrayList<>(immutableText(
                supplied, "Channel catalog contract key"));
        keys.sort(ExternalOrderKey::compareTextCodePoints);
        return Collections.unmodifiableList(keys);
    }

    static List<String> requireNoCatalogKeys(List<String> supplied) {
        Objects.requireNonNull(supplied, "channelCatalogContractKeys");
        if (!supplied.isEmpty()) {
            throw new IllegalArgumentException(
                    "Channel catalog contract keys require a whole "
                            + "same-scope Channel catalog declaration");
        }
        return Collections.emptyList();
    }

    static List<String> channelEntryKeys(
            List<ExternalChannelDependencySnapshot.ChannelEntry> supplied) {
        Objects.requireNonNull(supplied, "channelEntries");
        List<String> keys = new ArrayList<>(supplied.size());
        for (ExternalChannelDependencySnapshot.ChannelEntry entry : supplied) {
            keys.add(Objects.requireNonNull(
                    entry, "Channel dependency entry").channelKey());
        }
        keys.sort(ExternalOrderKey::compareTextCodePoints);
        return keys;
    }

    static List<ExternalChannelDependencySnapshot.Member> immutableMembers(
            List<ExternalChannelDependencySnapshot.Member> supplied,
            String inferredExactTypeBlueId) {
        Objects.requireNonNull(supplied, "members");
        List<ExternalChannelDependencySnapshot.Member> copy =
                new ArrayList<>(supplied.size());
        Set<String> keys = new LinkedHashSet<>();
        for (ExternalChannelDependencySnapshot.Member member : supplied) {
            ExternalChannelDependencySnapshot.Member exact =
                    Objects.requireNonNull(member, "family member");
            if (!keys.add(exact.channelKey())) {
                throw new IllegalArgumentException(
                        "Duplicate External Channel family member: "
                                + exact.channelKey());
            }
            if (exact.effectiveTypeBlueId() == null) {
                if (inferredExactTypeBlueId == null) {
                    throw new IllegalArgumentException(
                            "Assignable External Channel family member must "
                                    + "declare its actual effective type: "
                                    + exact.channelKey());
                }
                exact = exact.withEffectiveTypeBlueId(
                        inferredExactTypeBlueId);
            }
            copy.add(exact);
        }
        return Collections.unmodifiableList(copy);
    }

    static String requireChannelRole(String role) {
        String exact = requireText(role, "role");
        if (!EffectiveContractSnapshotConstants.Role.EXTERNAL_CHANNEL
                .equals(exact)
                && !EffectiveContractSnapshotConstants.Role.PROCESSOR_CHANNEL
                .equals(exact)) {
            throw new IllegalArgumentException(
                    "Unsupported Channel runtime role: " + exact);
        }
        return exact;
    }
}
