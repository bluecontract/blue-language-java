package blue.language;

import blue.language.utils.UncheckedObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class BlueConformanceReport {

    public static final String FIXTURE_MANIFEST_RESOURCE = "blue-language-1.0/fixtures/manifest.yaml";
    private static final Set<String> REQUIRED_FIXTURE_IDS = requiredFixtureIds();

    private final String specVersion;
    private final Map<String, String> coreRegistryBlueIds;
    private final String fixturePackageIdentity;
    private final List<String> fixtureIds;
    private final List<String> passedFixtureIds;
    private final List<String> failedFixtureIds;
    private final List<BlueConformanceFailure> failures;
    private final Map<String, BlueFixtureCategory> fixtureCategories;

    public BlueConformanceReport(String specVersion,
                                 Map<String, String> coreRegistryBlueIds,
                                 String fixturePackageIdentity,
                                 List<String> passedFixtureIds) {
        this(specVersion, coreRegistryBlueIds, fixturePackageIdentity, Collections.emptyList(), passedFixtureIds, Collections.emptyList(), Collections.emptyMap());
    }

    public BlueConformanceReport(String specVersion,
                                 Map<String, String> coreRegistryBlueIds,
                                 String fixturePackageIdentity,
                                 List<String> fixtureIds,
                                 List<String> passedFixtureIds,
                                 List<String> failedFixtureIds,
                                 Map<String, BlueFixtureCategory> fixtureCategories) {
        this(specVersion, coreRegistryBlueIds, fixturePackageIdentity, fixtureIds, passedFixtureIds, failedFixtureIds, fixtureCategories, Collections.emptyList());
    }

    public BlueConformanceReport(String specVersion,
                                 Map<String, String> coreRegistryBlueIds,
                                 String fixturePackageIdentity,
                                 List<String> fixtureIds,
                                 List<String> passedFixtureIds,
                                 List<String> failedFixtureIds,
                                 Map<String, BlueFixtureCategory> fixtureCategories,
                                 List<BlueConformanceFailure> failures) {
        this.specVersion = specVersion;
        this.coreRegistryBlueIds = Collections.unmodifiableMap(new LinkedHashMap<>(coreRegistryBlueIds));
        this.fixturePackageIdentity = fixturePackageIdentity;
        this.fixtureIds = Collections.unmodifiableList(new ArrayList<>(fixtureIds));
        this.passedFixtureIds = Collections.unmodifiableList(new ArrayList<>(passedFixtureIds));
        List<String> effectiveFailedFixtureIds = new ArrayList<>(failedFixtureIds);
        if (!failures.isEmpty()) {
            effectiveFailedFixtureIds.clear();
            for (BlueConformanceFailure failure : failures) {
                effectiveFailedFixtureIds.add(failure.getFixtureId());
            }
        }
        this.failedFixtureIds = Collections.unmodifiableList(effectiveFailedFixtureIds);
        this.failures = Collections.unmodifiableList(new ArrayList<>(failures));
        this.fixtureCategories = Collections.unmodifiableMap(new LinkedHashMap<>(fixtureCategories));
    }

    public String getSpecVersion() {
        return specVersion;
    }

    public Map<String, String> getCoreRegistryBlueIds() {
        return coreRegistryBlueIds;
    }

    public String getFixturePackageIdentity() {
        return fixturePackageIdentity;
    }

    public List<String> getFixtureIds() {
        return fixtureIds;
    }

    public List<String> getPassedFixtureIds() {
        return passedFixtureIds;
    }

    public List<String> getFailedFixtureIds() {
        return failedFixtureIds;
    }

    public List<BlueConformanceFailure> getFailures() {
        return failures;
    }

    public Map<String, BlueFixtureCategory> getFixtureCategories() {
        return fixtureCategories;
    }

    public boolean isReleaseGradeFixtureIdentity() {
        return isReleaseGradeFixtureIdentity(fixturePackageIdentity);
    }

    public boolean hasRequiredFixtureCoverage() {
        return new HashSet<>(fixtureIds).containsAll(REQUIRED_FIXTURE_IDS);
    }

    public boolean hasExactRequiredFixtureSet() {
        return new LinkedHashSet<>(fixtureIds).equals(REQUIRED_FIXTURE_IDS);
    }

    public static Set<String> requiredFixtureIdsForBlueLanguage10() {
        return Collections.unmodifiableSet(REQUIRED_FIXTURE_IDS);
    }

    public static String loadFixturePackageIdentity(String fallback) {
        Map<?, ?> manifest = loadFixtureManifest();
        if (manifest == null) {
            return fallback;
        }
        Object identity = manifest.get("fixturePackageIdentity");
        return identity == null || identity.toString().trim().isEmpty()
                ? fallback
                : identity.toString();
    }

    public static List<String> loadFixtureIds() {
        Map<?, ?> manifest = loadFixtureManifest();
        if (manifest == null) {
            return Collections.emptyList();
        }
        Object fixtures = manifest.get("fixtures");
        if (!(fixtures instanceof List)) {
            return Collections.emptyList();
        }
        List<?> fixtureList = (List<?>) fixtures;
        List<String> ids = new ArrayList<>();
        for (Object fixture : fixtureList) {
            if (fixture instanceof Map) {
                Object id = ((Map<?, ?>) fixture).get("id");
                if (id != null) {
                    ids.add(id.toString());
                }
            }
        }
        return ids;
    }

    public static Map<String, BlueFixtureCategory> loadFixtureCategories() {
        Map<?, ?> manifest = loadFixtureManifest();
        if (manifest == null) {
            return Collections.emptyMap();
        }
        Object fixtures = manifest.get("fixtures");
        if (!(fixtures instanceof List)) {
            return Collections.emptyMap();
        }
        Map<String, BlueFixtureCategory> categories = new LinkedHashMap<>();
        for (Object fixture : (List<?>) fixtures) {
            if (fixture instanceof Map) {
                Map<?, ?> fixtureMap = (Map<?, ?>) fixture;
                Object id = fixtureMap.get("id");
                Object category = fixtureMap.get("category");
                if (id != null && category != null) {
                    categories.put(id.toString(), BlueFixtureCategory.fromLabel(category.toString()));
                }
            }
        }
        return categories;
    }

    public static String computeFixturePackageIdentity() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("manifest.yaml\n".getBytes(StandardCharsets.UTF_8));
            digest.update(normalizeManifestForIdentity(readFixtureResource(FIXTURE_MANIFEST_RESOURCE)));
            Map<?, ?> manifest = loadFixtureManifest();
            if (manifest == null) {
                throw new IllegalStateException("Blue Language fixture manifest not found");
            }
            Object fixtures = manifest.get("fixtures");
            if (!(fixtures instanceof List)) {
                throw new IllegalStateException("Blue Language fixture manifest has no fixture list");
            }
            for (Object fixture : (List<?>) fixtures) {
                if (!(fixture instanceof Map)) {
                    throw new IllegalStateException("Blue Language fixture manifest contains a non-map fixture entry");
                }
                Object path = ((Map<?, ?>) fixture).get("path");
                if (path == null || path.toString().trim().isEmpty()) {
                    throw new IllegalStateException("Blue Language fixture manifest entry is missing path");
                }
                String fixturePath = path.toString();
                digest.update(("\n--- " + fixturePath + "\n").getBytes(StandardCharsets.UTF_8));
                digest.update(normalizeLineEndings(readFixtureResource("blue-language-1.0/fixtures/" + fixturePath)));
            }
            return "sha256:" + toHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest is unavailable", e);
        }
    }

    public static boolean fixturePackageIdentityMatchesFixtureFiles() {
        String identity = loadFixturePackageIdentity(null);
        return identity != null && identity.equals(computeFixturePackageIdentity());
    }

    public static boolean isReleaseGradeFixtureIdentity(String identity) {
        if (identity == null || identity.trim().isEmpty()) {
            return false;
        }
        String trimmed = identity.trim();
        if (trimmed.contains("local-dev")
                || trimmed.contains("pending")
                || trimmed.contains("unavailable")) {
            return false;
        }
        if (trimmed.startsWith("sha256:")) {
            return trimmed.substring("sha256:".length()).matches("[0-9a-f]{64}");
        }
        return trimmed.startsWith("blueId:") && trimmed.length() > "blueId:".length();
    }

    private static Map<?, ?> loadFixtureManifest() {
        try (InputStream inputStream = BlueConformanceReport.class.getClassLoader()
                .getResourceAsStream(FIXTURE_MANIFEST_RESOURCE)) {
            if (inputStream == null) {
                return null;
            }
            return UncheckedObjectMapper.YAML_MAPPER.readValue(inputStream, Map.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static byte[] readFixtureResource(String resource) {
        try (InputStream inputStream = BlueConformanceReport.class.getClassLoader()
                .getResourceAsStream(resource)) {
            if (inputStream == null) {
                throw new IllegalStateException("Missing Blue Language fixture resource: " + resource);
            }
            return readAll(inputStream);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read Blue Language fixture resource: " + resource, e);
        }
    }

    private static byte[] readAll(InputStream inputStream) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = inputStream.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static byte[] normalizeManifestForIdentity(byte[] bytes) {
        String normalized = new String(normalizeLineEndings(bytes), StandardCharsets.UTF_8)
                .replaceFirst("(?m)^fixturePackageIdentity:.*$", "fixturePackageIdentity: \"\"");
        return normalized.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] normalizeLineEndings(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8)
                .replace("\r\n", "\n")
                .replace("\r", "\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static String toHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format("%02x", value & 0xff));
        }
        return result.toString();
    }

    private static Set<String> requiredFixtureIds() {
        return set(
                "B_scalar_sugar_equivalence",
                "B_list_sugar_equivalence",
                "B_root_scalar",
                "B_root_list",
                "B_root_empty_object",
                "B_root_pure_reference",
                "B_root_null_rejected",
                "B_plain_blueid_validation",
                "B_empty_list",
                "B_object_field_null_removal",
                "B_empty_placeholder",
                "B_null_list_element_rejected",
                "B_empty_object_list_element_rejected",
                "B_malformed_empty_rejected",
                "B_large_integer_quoted_explicit_integer",
                "B_unquoted_large_integer_rejected",
                "B_integer_1_vs_double_1_0",
                "B_double_1e0",
                "B_invalid_this_placeholder_rejected",
                "B_type_alias_rejected_in_direct_blueid_input",
                "B_previous_invalid_blueid_rejected",
                "B_pos_rejected",
                "B_replace_rejected",
                "R_blue_imports_type_itemType_keyType_valueType",
                "R_blue_imports",
                "R_source_null_list_to_empty",
                "R_source_empty_object_list_to_empty",
                "R_schema_value_shapes",
                "R_schema_large_integer_minimum_with_type_alias",
                "R_enum_integer_vs_double",
                "R_canonical_overlay_no_previous_no_pos",
                "R_inherited_append_only_policy",
                "R_inherited_item_type",
                "R_inherited_keyType_valueType",
                "R_provider_reference_canonicalizes_back",
                "R_type_aliases_removed_from_canonical_overlay",
                "R_contracts_merge_as_content",
                "R_top_level_type_name_description_not_inherited",
                "R_type_derived_field_removed",
                "R_instance_field_kept",
                "R_provider_reference_with_overlay_keeps_overlay",
                "R_contracts_canonicalization_deterministic",
                "R_child_field_labels_materialize_until_overridden",
                "R_canonicalization_deterministic_for_same_resolved_view",
                "F_provider_wrong_blueid_rejected",
                "F_provider_missing_content_fails",
                "F_expand_preserves_node_blueid",
                "F_expand_nested_reference_preserves_node_blueid",
                "F_expand_wrong_nested_provider_content_fails",
                "F_expand_missing_nested_content_fails",
                "F_collapse_preserves_node_blueid",
                "F_collapse_nested_subtree_preserves_node_blueid",
                "F_collapse_does_not_produce_mixed_blueid",
                "C_circular_reference_set_ids",
                "C_this_placeholder_rejected_outside_cyclic_api",
                "C_zero_blueid_rejected_in_final_input",
                "C_three_document_cycle_stable_order",
                "C_duplicate_preliminary_ids_deterministic_or_rejected");
    }

    private static Set<String> set(String... values) {
        return Arrays.stream(values).collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
