package blue.language.processor.closure;

import java.util.*;

/** Stable settlement authority; never re-anchors work already produced by an original seed. */
final class SameOriginGroupIdentity {
    private final String identity;

    private SameOriginGroupIdentity(String identity) { this.identity = identity; }

    static SameOriginGroupIdentity of(Map<DocumentId, String> originalSeedByMember, List<Admission> admissions) {
        return of(originalSeedByMember, admissions, Collections.<DocumentId, String>emptyMap());
    }

    /** Final directed dependencies, resolved after quiescence; never the worker's current receipt inventory. */
    static SameOriginGroupIdentity of(Map<DocumentId, String> originalSeedByMember, List<Admission> admissions,
            Map<DocumentId, String> consumedSourceOperations) {
        return of(originalSeedByMember, admissions, consumedSourceOperations, Collections.emptyList());
    }

    static SameOriginGroupIdentity of(Map<DocumentId, String> originalSeedByMember, List<Admission> admissions,
            Map<DocumentId, String> consumedSourceOperations, List<SameOriginGroupEvidence.SourceEvidence> interpreted) {
        Map<String, Object> value = preimage(originalSeedByMember, admissions, consumedSourceOperations, interpreted);
        Set<String> seeds = new HashSet<>(originalSeedByMember.values());
        if (seeds.size() == 1 && consumedSourceOperations.isEmpty() && interpreted.isEmpty()) return new SameOriginGroupIdentity(seeds.iterator().next());
        return new SameOriginGroupIdentity(ClosureIdentityService.INSTANCE.identity(
                ClosureIdentityService.Constructor.SAME_ORIGIN_GROUP, value));
    }

    String identity() { return identity; }

    /** Only actual accepted merging sites belong here; rejected or discarded attempts do not. */
    static final class Admission {
        private final String canonicalSite;
        private final SortedSet<DocumentId> members;

        Admission(String canonicalSite, Collection<DocumentId> admittedMembers) {
            this.canonicalSite = ClosureValueSupport.requireSha256Identity(canonicalSite, "canonicalSite");
            TreeSet<DocumentId> sorted = new TreeSet<>(Objects.requireNonNull(admittedMembers, "admittedMembers"));
            if (sorted.isEmpty() || sorted.size() != admittedMembers.size()) throw invalid("Admission members must be nonempty and unique");
            this.members = Collections.unmodifiableSortedSet(sorted);
        }

        String canonicalSite() { return canonicalSite; }
        SortedSet<DocumentId> members() { return members; }
    }

    private static Map<String, Object> preimage(Map<DocumentId, String> supplied, List<Admission> admissions,
            Map<DocumentId, String> consumedSourceOperations, List<SameOriginGroupEvidence.SourceEvidence> interpreted) {
        TreeMap<DocumentId, String> byMember = new TreeMap<>(Objects.requireNonNull(supplied, "originalSeedByMember"));
        if (byMember.isEmpty()) throw invalid("Settlement group requires original seeds");
        TreeMap<String, SortedSet<DocumentId>> seedMembers = new TreeMap<>();
        for (Map.Entry<DocumentId, String> entry : byMember.entrySet()) {
            String seed = ClosureValueSupport.requireSha256Identity(entry.getValue(), "originalSeedIdentity");
            seedMembers.computeIfAbsent(seed, unused -> new TreeSet<>()).add(entry.getKey());
        }
        List<Object> seedRows = new ArrayList<>();
        Map<DocumentId, Group> current = new HashMap<>();
        for (Map.Entry<String, SortedSet<DocumentId>> entry : seedMembers.entrySet()) {
            seedRows.add(map("seedIdentity", entry.getKey(), "members", ids(entry.getValue())));
            Group initial = new Group(entry.getValue(), Collections.singleton(entry.getKey()));
            for (DocumentId member : initial.members) current.put(member, initial);
        }
        List<Object> accepted = new ArrayList<>(); Set<String> sites = new HashSet<>();
        for (Admission admission : Objects.requireNonNull(admissions, "admissions")) {
            if (!sites.add(admission.canonicalSite)) throw invalid("An atomic admission site cannot be repeated");
            // Group objects deliberately use reference identity, never worker attempt numbers.
            Set<Group> participants = Collections.newSetFromMap(new IdentityHashMap<Group, Boolean>());
            for (DocumentId member : admission.members) {
                Group group = current.get(member);
                if (group == null) throw invalid("Admission names a foreign member");
                participants.add(group);
            }
            if (participants.size() < 2) throw invalid("A non-merging site is not an accepted admission");
            TreeSet<DocumentId> unionMembers = new TreeSet<>(); TreeSet<String> unionSeeds = new TreeSet<>();
            List<Group> ordered = new ArrayList<>(participants);
            ordered.sort(Comparator.comparing(group -> group.seeds.first()));
            List<Object> participatingGroups = new ArrayList<>();
            for (Group group : ordered) {
                unionMembers.addAll(group.members); unionSeeds.addAll(group.seeds);
                participatingGroups.add(new ArrayList<>(group.seeds));
            }
            if (!unionMembers.equals(admission.members)) throw invalid("Admission splits an existing atomic group");
            accepted.add(map("canonicalSite", admission.canonicalSite, "members", ids(unionMembers),
                    "participatingSeedGroups", participatingGroups));
            Group merged = new Group(unionMembers, unionSeeds);
            for (DocumentId member : unionMembers) current.put(member, merged);
        }
        Group finalGroup = current.values().iterator().next();
        if (finalGroup.members.size() != byMember.size()) throw invalid("Disconnected seeds require separate settlement operations");
        List<Object> consumed = new ArrayList<>();
        for (Map.Entry<DocumentId, String> entry : new TreeMap<>(Objects.requireNonNull(consumedSourceOperations)).entrySet()) {
            if (byMember.containsKey(entry.getKey())) throw invalid("An internal group member is not an independent consumed source");
            consumed.add(map("documentId", entry.getKey().value(), "operationIdentity",
                    ClosureValueSupport.requireSha256Identity(entry.getValue(), "consumed source operation")));
        }
        Map<String, Object> value = map("originalSeeds", seedRows, "acceptedAdmissions", accepted, "consumedSourceOperations", consumed);
        if (!interpreted.isEmpty()) {
            List<Object> evidence = new ArrayList<>();
            for (SameOriginGroupEvidence.SourceEvidence item : SameOriginGroupEvidence.canonicalSourceEvidence(interpreted))
                evidence.add(map("kind", item.kind().name(), "identity", item.identity()));
            value.put("interpretedSourceEvidence", evidence);
        }
        return value;
    }

    /** Reconstructing the preimage rejects noncanonical order and invented participant partitions. */
    static void validateConstructor(Map<String, Object> value) {
        if (value.containsKey("interpretedSourceEvidence")) fields(value, "originalSeeds", "acceptedAdmissions", "consumedSourceOperations", "interpretedSourceEvidence");
        else fields(value, "originalSeeds", "acceptedAdmissions", "consumedSourceOperations");
        List<SameOriginGroupEvidence.SourceEvidence> interpreted = new ArrayList<>();
        if (value.containsKey("interpretedSourceEvidence")) for (Object item : list(value.get("interpretedSourceEvidence"))) {
            Map<String, Object> row = object(item); fields(row, "kind", "identity");
            interpreted.add(new SameOriginGroupEvidence.SourceEvidence(SameOriginGroupEvidence.SourceEvidence.Kind.valueOf(text(row.get("kind"))), text(row.get("identity"))));
        }
        Map<DocumentId, String> seeds = new TreeMap<>(); Set<String> seedIds = new HashSet<>();
        for (Object item : list(value.get("originalSeeds"))) {
            Map<String, Object> row = object(item); fields(row, "seedIdentity", "members");
            String seed = text(row.get("seedIdentity"));
            if (!seedIds.add(seed)) throw invalid("Duplicate original seed");
            List<?> members = list(row.get("members"));
            if (members.isEmpty()) throw invalid("Original seed must own members");
            for (Object member : members) if (seeds.put(new DocumentId(text(member)), seed) != null)
                throw invalid("Original seed ownership overlaps");
        }
        Map<DocumentId, String> consumed = new TreeMap<>();
        for (Object item : list(value.get("consumedSourceOperations"))) {
            Map<String, Object> row = object(item); fields(row, "documentId", "operationIdentity");
            if (consumed.put(new DocumentId(text(row.get("documentId"))), text(row.get("operationIdentity"))) != null)
                throw invalid("Repeated independent source disposition");
        }
        if (seedIds.size() == 1 && consumed.isEmpty() && interpreted.isEmpty()) throw invalid("Independent singleton uses its original seed identity");
        List<Admission> admissions = new ArrayList<>();
        for (Object item : list(value.get("acceptedAdmissions"))) {
            Map<String, Object> row = object(item); fields(row, "canonicalSite", "members", "participatingSeedGroups");
            List<DocumentId> members = new ArrayList<>();
            for (Object member : list(row.get("members"))) members.add(new DocumentId(text(member)));
            admissions.add(new Admission(text(row.get("canonicalSite")), members));
        }
        if (!preimage(seeds, admissions, consumed, interpreted).equals(value)) throw invalid("Noncanonical settlement group evidence");
    }

    private static final class Group {
        final SortedSet<DocumentId> members; final SortedSet<String> seeds;
        Group(Collection<DocumentId> members, Collection<String> seeds) {
            this.members = new TreeSet<>(members); this.seeds = new TreeSet<>(seeds);
        }
    }
    private static List<String> ids(Collection<DocumentId> ids) {
        List<String> values = new ArrayList<>(); for (DocumentId id : ids) values.add(id.value()); return values;
    }
    private static Map<String, Object> map(Object... entries) {
        Map<String, Object> value = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) value.put((String) entries[i], entries[i + 1]); return value;
    }
    private static void fields(Map<String, Object> value, String... names) {
        if (!value.keySet().equals(new HashSet<>(Arrays.asList(names)))) throw invalid("Invalid group field set");
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map)) throw invalid("Expected group object"); return (Map<String, Object>) value;
    }
    private static List<?> list(Object value) { if (!(value instanceof List)) throw invalid("Expected group array"); return (List<?>) value; }
    private static String text(Object value) { if (!(value instanceof String)) throw invalid("Expected group text"); return (String) value; }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
