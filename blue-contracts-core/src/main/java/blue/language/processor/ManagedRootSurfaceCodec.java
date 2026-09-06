package blue.language.processor;

import blue.language.model.wire.BlueLanguageConstants;
import blue.language.processor.closure.FrozenNodeEvidenceCodec;
import blue.language.snapshot.FrozenNode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.util.*;

/** Internal immutable surface transport; this data alone grants no managed-state authority. */
public final class ManagedRootSurfaceCodec {
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private ManagedRootSurfaceCodec() { }

    public static String encode(ManagedRootSubscriptionSurface surface, FrozenNodeEvidenceCodec.Encoder encoder) {
        List<Object> channels = new ArrayList<>(), contracts = new ArrayList<>(), subscriptions = new ArrayList<>();
        for (ManagedRootChannelOccurrence c : surface.channelOccurrences()) channels.add(m("key", c.rawChannelKey(), "order", c.order(),
                "external", c.externalSource(), BlueLanguageConstants.OBJECT_TYPE, c.effectiveTypeBlueId(), "contribution", c.effectiveRuntimeContributionBlueId(),
                "header", c.subscriptionHeaderBlueId(), "sources", c.sourceContributionNodeBlueIds(), "dependencies", c.deterministicDependencyNodeBlueIds(),
                "node", encoder.node(Objects.requireNonNull(surface.channelNodes().get(c.rawChannelKey())))));
        for (EffectiveContractSnapshot c : surface.effectiveRootContracts()) {
            Map<String, String> headers = new TreeMap<>();
            for (Map.Entry<String, FrozenNode> h : c.headerFields().entrySet()) headers.put(h.getKey(), encoder.node(h.getValue()));
            List<Object> bodies = new ArrayList<>();
            for (Map.Entry<String, ExecutableBodySourceDescriptor> body : c.executableBodySourceDescriptorsByField().entrySet()) {
                ExecutableBodySourceDescriptor d = body.getValue();
                bodies.add(m("field", body.getKey(), BlueLanguageConstants.OBJECT_BLUE_ID, d.bodyNodeBlueId(), "source", d.owningSourceContributionNodeBlueId(),
                        "pointer", d.sourcePointer(), "reference", d.pureReference()));
            }
            contracts.add(m("scope", c.scopePath(), "key", c.key(), BlueLanguageConstants.OBJECT_TYPE, c.effectiveTypeBlueId(), "role", c.role(), "order", c.order(),
                    "sources", c.sourceContributionNodeBlueIds(), "dispatch", c.dispatchFields(), "headers", headers,
                    "bodyFields", c.executableBodyFields(), "bodyIds", c.executableBodyNodeBlueIds(), "bodyByField", c.executableBodyNodeBlueIdsByField(),
                    "bodies", bodies, "dependencies", c.deterministicDependencyNodeBlueIds()));
        }
        for (SubscriptionDelta.Entry s : surface.externalSubscriptions()) subscriptions.add(m("scope", s.scopePath(), "key", s.channelKey(),
                BlueLanguageConstants.OBJECT_TYPE, s.effectiveTypeBlueId(), "sources", s.sourceContributionNodeBlueIds(), "order", s.order(), "keys", s.subscriptionKeys(),
                "domain", s.checkpointDomainBlueId(), "dependencies", dependency(s.dependencies())));
        return encoder.blob(bytes(m("format", "blue-root-surface-poc-1", "channels", channels, BlueLanguageConstants.OBJECT_CONTRACTS, contracts, "subscriptions", subscriptions)));
    }

    public static ManagedRootSubscriptionSurface decode(String authenticatedSurface, FrozenNodeEvidenceCodec.Decoder decoder) {
        JsonNode root = json(decoder.blob(authenticatedSurface));
        if (!"blue-root-surface-poc-1".equals(t(root, "format"))) throw invalid("Unexpected Root surface format");
        List<ManagedRootChannelOccurrence> channels = new ArrayList<>();
        Map<String, FrozenNode> nodes = new LinkedHashMap<>();
        for (JsonNode c : a(root, "channels")) {
            String key = t(c, "key"); FrozenNode node = decoder.node(t(c, "node"));
            if (node == null || !node.blueId().equals(t(c, "contribution")) || nodes.put(key, node) != null)
                throw invalid("Root channel contribution is absent, repeated, or differs from its exact identity");
            channels.add(new ManagedRootChannelOccurrence(key, integer(c, "order"), bool(c, "external"), t(c, BlueLanguageConstants.OBJECT_TYPE),
                    t(c, "contribution"), t(c, "header"), strings(c, "sources"), strings(c, "dependencies")));
        }
        List<EffectiveContractSnapshot> contracts = new ArrayList<>(); Set<String> keys = new HashSet<>();
        for (JsonNode c : a(root, BlueLanguageConstants.OBJECT_CONTRACTS)) {
            String key = t(c, "key"), scope = t(c, "scope"), type = t(c, BlueLanguageConstants.OBJECT_TYPE);
            if (!keys.add(key) || !blue.language.model.wire.JsonPointer.ROOT.equals(scope)) throw invalid("Root effective contract inventory is repeated or non-Root");
            EffectiveContractSnapshot.Builder b = EffectiveContractSnapshot.builder(scope, key).effectiveTypeBlueId(type)
                    .role(t(c, "role")).order(integer(c, "order"));
            List<String> sources = strings(c, "sources"); sources.forEach(b::sourceContribution);
            object(c, "dispatch").fields().forEachRemaining(e -> b.dispatchField(e.getKey(), e.getValue().textValue()));
            object(c, "headers").fields().forEachRemaining(e -> b.headerField(e.getKey(), decoder.node(e.getValue().textValue())));
            JsonNode bodyByField = object(c, "bodyByField");
            for (String field : strings(c, "bodyFields")) b.executableBody(field, t(bodyByField, field));
            for (JsonNode d : a(c, "bodies")) b.executableBodySourceDescriptor(t(d, "field"), new ExecutableBodySourceDescriptor(
                    scope, key, type, t(d, "field"), t(d, BlueLanguageConstants.OBJECT_BLUE_ID), sources, t(d, "source"), t(d, "pointer"), bool(d, "reference")));
            strings(c, "dependencies").forEach(b::deterministicDependency);
            EffectiveContractSnapshot built = b.build();
            if (!built.executableBodyFields().equals(strings(c, "bodyFields")) || !built.executableBodyNodeBlueIds().equals(strings(c, "bodyIds")))
                throw invalid("Root executable identity descriptor is incomplete");
            contracts.add(built);
        }
        Set<String> expectedChannels = new HashSet<>(); Set<String> expectedExternal = new HashSet<>();
        Map<String, EffectiveContractSnapshot> byKey = new HashMap<>();
        for (EffectiveContractSnapshot c : contracts) {
            byKey.put(c.key(), c);
            if (EffectiveContractSnapshotConstants.Role.EXTERNAL_CHANNEL.equals(c.role())) expectedExternal.add(c.key());
            if (EffectiveContractSnapshotConstants.Role.EXTERNAL_CHANNEL.equals(c.role()) || EffectiveContractSnapshotConstants.Role.PROCESSOR_CHANNEL.equals(c.role())) expectedChannels.add(c.key());
        }
        if (!expectedChannels.equals(nodes.keySet())) throw invalid("Incomplete retained Root Channel inventory");
        for (ManagedRootChannelOccurrence channel : channels) {
            EffectiveContractSnapshot c = byKey.get(channel.rawChannelKey());
            if (c.order() != channel.order() || !c.effectiveTypeBlueId().equals(channel.effectiveTypeBlueId())
                    || !c.sourceContributionNodeBlueIds().equals(channel.sourceContributionNodeBlueIds())
                    || !c.deterministicDependencyNodeBlueIds().equals(channel.deterministicDependencyNodeBlueIds())
                    || !ChannelMemberSnapshot.from(c).headerIdentityBlueId().equals(channel.subscriptionHeaderBlueId())
                    || channel.externalSource() != expectedExternal.contains(c.key())) throw invalid("Root Channel descriptor differs from effective header");
        }
        List<SubscriptionDelta.Entry> subscriptions = new ArrayList<>(); Set<String> external = new HashSet<>();
        for (JsonNode s : a(root, "subscriptions")) {
            String key = t(s, "key"); if (!external.add(key)) throw invalid("Repeated external Root subscription");
            EffectiveContractSnapshot c = byKey.get(key);
            if (c == null || !blue.language.model.wire.JsonPointer.ROOT.equals(t(s, "scope")) || c.order() != integer(s, "order")
                    || !c.effectiveTypeBlueId().equals(t(s, BlueLanguageConstants.OBJECT_TYPE)) || !c.sourceContributionNodeBlueIds().equals(strings(s, "sources")))
                throw invalid("External Root subscription differs from effective header");
            subscriptions.add(new SubscriptionDelta.Entry(blue.language.model.wire.JsonPointer.ROOT, key, t(s, BlueLanguageConstants.OBJECT_TYPE), strings(s, "sources"), integer(s, "order"),
                    strings(s, "keys"), t(s, "domain"), dependency(s.get("dependencies")), null, null, null));
        }
        if (!external.equals(expectedExternal)) throw invalid("Incomplete external Root subscription inventory");
        return new ManagedRootSubscriptionSurface(channels, subscriptions, contracts, nodes);
    }

    private static Object dependency(ExternalChannelDependencySnapshot d) {
        List<Object> entries = new ArrayList<>(), families = new ArrayList<>(), channels = new ArrayList<>();
        for (ExternalChannelDependencySnapshot.Entry e : d.entries()) entries.add(m("key", e.channelKey(), "order", e.order(), BlueLanguageConstants.OBJECT_TYPE, e.effectiveTypeBlueId(),
                "sources", e.sourceContributionNodeBlueIds(), "dependencies", e.deterministicDependencyNodeBlueIds(), "domain", e.checkpointDomainBlueId()));
        for (ExternalChannelDependencySnapshot.TypeFamily f : d.typeFamilies()) {
            List<Object> members = new ArrayList<>();
            for (ExternalChannelDependencySnapshot.Member e : f.members()) members.add(m("key", e.channelKey(), "order", e.order(), BlueLanguageConstants.OBJECT_TYPE, e.effectiveTypeBlueId(),
                    "sources", e.sourceContributionNodeBlueIds(), "dependencies", e.deterministicDependencyNodeBlueIds()));
            families.add(m("excluding", f.excludingChannelKey(), BlueLanguageConstants.OBJECT_TYPE, f.effectiveTypeBlueId(), "mode", f.matchMode().name(), "members", members));
        }
        for (ExternalChannelDependencySnapshot.ChannelEntry e : d.channelEntries()) channels.add(m("key", e.channelKey(), "order", e.order(), BlueLanguageConstants.OBJECT_TYPE, e.effectiveTypeBlueId(), "role", e.role(),
                "sources", e.sourceContributionNodeBlueIds(), "dependencies", e.deterministicDependencyNodeBlueIds(), "header", e.headerIdentityBlueId()));
        return m("intrinsic", d.intrinsicNodeBlueIds(), "entries", entries, "families", families, "wholeExternal", d.wholeSameScopeExternalSurface(),
                "channels", channels, "wholeCatalog", d.wholeSameScopeChannelCatalog(), "catalogKeys", d.channelCatalogContractKeys());
    }
    private static ExternalChannelDependencySnapshot dependency(JsonNode d) {
        List<ExternalChannelDependencySnapshot.Entry> entries = new ArrayList<>();
        for (JsonNode e : a(d, "entries")) entries.add(new ExternalChannelDependencySnapshot.Entry(t(e, "key"), integer(e, "order"), t(e, BlueLanguageConstants.OBJECT_TYPE), strings(e, "sources"), strings(e, "dependencies"), t(e, "domain")));
        List<ExternalChannelDependencySnapshot.TypeFamily> families = new ArrayList<>();
        for (JsonNode f : a(d, "families")) {
            List<ExternalChannelDependencySnapshot.Member> members = new ArrayList<>();
            for (JsonNode e : a(f, "members")) members.add(new ExternalChannelDependencySnapshot.Member(t(e, "key"), integer(e, "order"), t(e, BlueLanguageConstants.OBJECT_TYPE), strings(e, "sources"), strings(e, "dependencies")));
            families.add(new ExternalChannelDependencySnapshot.TypeFamily(t(f, "excluding"), t(f, BlueLanguageConstants.OBJECT_TYPE), ExternalChannelDependencySnapshot.TypeMatchMode.valueOf(t(f, "mode")), members));
        }
        List<ExternalChannelDependencySnapshot.ChannelEntry> channels = new ArrayList<>();
        for (JsonNode e : a(d, "channels")) channels.add(new ExternalChannelDependencySnapshot.ChannelEntry(t(e, "key"), integer(e, "order"), t(e, BlueLanguageConstants.OBJECT_TYPE), t(e, "role"), strings(e, "sources"), strings(e, "dependencies"), t(e, "header")));
        return new ExternalChannelDependencySnapshot(strings(d, "intrinsic"), entries, families, bool(d, "wholeExternal"), channels, bool(d, "wholeCatalog"), strings(d, "catalogKeys"));
    }
    private static Map<String, Object> m(Object... values) { Map<String, Object> result = new TreeMap<>(); for (int i = 0; i < values.length; i += 2) result.put((String) values[i], values[i + 1]); return result; }
    private static byte[] bytes(Object value) { try { return JSON.writeValueAsBytes(value); } catch (Exception e) { throw invalid("Cannot encode Root surface"); } }
    private static JsonNode json(byte[] bytes) { try { return JSON.readTree(bytes); } catch (Exception e) { throw invalid("Malformed Root surface"); } }
    private static String t(JsonNode n, String key) { JsonNode v = n.get(key); if (v == null || !v.isTextual()) throw invalid("Missing Root surface text: " + key); return v.textValue(); }
    private static JsonNode a(JsonNode n, String key) { JsonNode v = n.get(key); if (v == null || !v.isArray()) throw invalid("Missing Root surface array: " + key); return v; }
    private static JsonNode object(JsonNode n, String key) { JsonNode v = n.get(key); if (v == null || !v.isObject()) throw invalid("Missing Root surface object: " + key); return v; }
    private static List<String> strings(JsonNode n, String key) { List<String> values = new ArrayList<>(); for (JsonNode v : a(n, key)) { if (!v.isTextual()) throw invalid("Non-text Root surface member"); values.add(v.textValue()); } return values; }
    private static int integer(JsonNode n, String key) { JsonNode v = n.get(key); if (v == null || !v.isIntegralNumber() || !v.canConvertToInt()) throw invalid("Invalid Root surface integer"); return v.intValue(); }
    private static boolean bool(JsonNode n, String key) { JsonNode v = n.get(key); if (v == null || !v.isBoolean()) throw invalid("Invalid Root surface flag"); return v.booleanValue(); }
    private static InvalidExecutionEvidenceException invalid(String message) { return new InvalidExecutionEvidenceException(message); }
}
