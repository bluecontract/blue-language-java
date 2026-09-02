package blue.language.processor;

import blue.language.model.wire.BlueLanguageConstants;
import blue.language.model.wire.JsonPointer;
import blue.language.processor.model.ChannelContract;
import blue.language.processor.model.EmbeddedCollectionEventChannel;
import blue.language.processor.model.EmbeddedNodeChannel;
import blue.language.processor.util.PointerUtils;
import blue.language.processor.util.ProcessorContractConstants;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Structural validation and decoded-pointer matching for collection events. */
final class EmbeddedCollectionEventChannelSupport {

    private EmbeddedCollectionEventChannelSupport() {
    }

    /**
     * Validates the complete processor-owned header against the effective
     * Process Embedded declaration in the same receiving scope.
     */
    static void validateHeader(
            EmbeddedCollectionEventChannel channel,
            EmbeddedScopeDeclaration declaration,
            String scopePath,
            GasSchedule schedule,
            ContractRecognitionMeter meter) {
        EmbeddedCollectionEventChannel checked = Objects.requireNonNull(
                channel, "channel");
        String path = checked.getCollectionPath();
        String normalized;
        try {
            normalized = PointerUtils.assertValidRuntimePointer(path);
        } catch (IllegalArgumentException invalid) {
            throw invalidHeader(
                    "Embedded Collection Event Channel collectionPath must "
                            + "be a normalized absolute Runtime Pointer",
                    scopePath);
        }
        if (JsonPointer.ROOT.equals(normalized)
                || !normalized.equals(path)) {
            throw invalidHeader(
                    "Embedded Collection Event Channel collectionPath must "
                            + "be normalized and non-root: " + path,
                    scopePath);
        }
        List<String> segments = JsonPointer.split(normalized);
        requireLimit(
                GasScheduleConstants.PortableLimit.RUNTIME_POINTER_SEGMENTS,
                segments.size(), schedule);
        requireLimit(
                GasScheduleConstants.PortableLimit.RUNTIME_POINTER_UTF8_BYTES,
                normalized.getBytes(StandardCharsets.UTF_8).length,
                schedule);
        for (String segment : segments) {
            if (BlueLanguageConstants.isLanguageReservedField(segment)) {
                throw invalidHeader(
                        "Embedded Collection Event Channel collectionPath "
                                + "traverses Language-reserved field '"
                                + segment + "': " + path,
                        scopePath);
            }
        }
        if (ProcessorContractConstants.KEY_CONTRACTS.equals(
                segments.get(0))) {
            throw invalidHeader(
                    "Embedded Collection Event Channel collectionPath must "
                            + "not point into /contracts: " + path,
                    scopePath);
        }
        boolean declared = false;
        if (declaration != null) {
            for (String candidate : declaration.collectionPaths()) {
                if (meter != null) {
                    meter.embeddedPathEntryRead(scopePath, candidate);
                }
                List<String> candidateSegments = JsonPointer.split(candidate);
                int compared = 0;
                boolean equal = candidateSegments.size() == segments.size();
                int count = Math.min(
                        candidateSegments.size(), segments.size());
                for (int index = 0; index < count; index++) {
                    compared++;
                    if (!candidateSegments.get(index).equals(
                            segments.get(index))) {
                        equal = false;
                        break;
                    }
                }
                if (meter != null && compared > 0) {
                    meter.embeddedPathSegmentsValidated(
                            scopePath, candidate, compared);
                }
                if (equal) {
                    declared = true;
                    break;
                }
            }
        }
        if (!declared) {
            throw invalidHeader(
                    "Embedded Collection Event Channel collectionPath must "
                            + "equal one effective Process Embedded "
                            + "collectionPaths declaration: " + path,
                    scopePath);
        }
    }

    /** Compares normalized decoded pointer segments and records bounded work. */
    static Match match(
            String collectionPath,
            String sourcePath,
            boolean includeDescendants) {
        String normalizedCollection =
                PointerUtils.assertValidRuntimePointer(collectionPath);
        String normalizedSource =
                PointerUtils.assertValidRuntimePointer(sourcePath);
        if (!normalizedCollection.equals(collectionPath)
                || !normalizedSource.equals(sourcePath)
                || JsonPointer.ROOT.equals(normalizedCollection)
                || JsonPointer.ROOT.equals(normalizedSource)) {
            throw new IllegalArgumentException(
                    "Collection-event paths must be normalized non-root "
                            + "Runtime Pointers");
        }
        List<String> collection = JsonPointer.split(normalizedCollection);
        List<String> source = JsonPointer.split(normalizedSource);
        int additionalSegments = source.size() - collection.size();
        if (additionalSegments <= 0
                || (!includeDescendants && additionalSegments != 1)) {
            return new Match(false, 0);
        }
        int compared = 0;
        for (int index = 0; index < collection.size(); index++) {
            compared++;
            if (!collection.get(index).equals(source.get(index))) {
                return new Match(false, compared);
            }
        }
        boolean matches = includeDescendants
                || source.size() == collection.size() + 1;
        return new Match(matches, compared);
    }

    /** Selects both internal embedded-event Channel types in Channel order. */
    static List<ContractBundle.ChannelBinding> orderedChannels(
            ContractBundle bundle) {
        List<ContractBundle.ChannelBinding> result =
                new ArrayList<ContractBundle.ChannelBinding>();
        for (ContractBundle.ChannelBinding binding
                : Objects.requireNonNull(bundle, "bundle")
                .channelsOfType(ChannelContract.class)) {
            if (binding.contract() instanceof EmbeddedNodeChannel
                    || binding.contract()
                    instanceof EmbeddedCollectionEventChannel) {
                result.add(binding);
            }
        }
        return Collections.unmodifiableList(result);
    }

    private static void requireLimit(
            String name,
            long observed,
            GasSchedule schedule) {
        long limit = Objects.requireNonNull(schedule, "schedule")
                .portableLimit(name);
        if (observed > limit) {
            throw new PortableLimitExceededException(
                    ProcessorErrorCategory.InvalidContractBinding,
                    name,
                    observed,
                    limit);
        }
    }

    private static MustUnderstandFailureException invalidHeader(
            String message,
            String scopePath) {
        return new MustUnderstandFailureException(
                message + " in scope " + scopePath,
                ProcessorErrorCategory.InvalidContractBinding);
    }

    /** Immutable structural match result including portable comparison work. */
    static final class Match {
        private final boolean matches;
        private final int comparedSegments;

        private Match(boolean matches, int comparedSegments) {
            this.matches = matches;
            this.comparedSegments = comparedSegments;
        }

        boolean matches() {
            return matches;
        }

        int comparedSegments() {
            return comparedSegments;
        }
    }
}
