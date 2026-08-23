#!/usr/bin/env ruby
# frozen_string_literal: true

require "yaml"
require "json"
require "digest"
require "set"
require "fileutils"
require "optparse"
require "tmpdir"

DEFAULT_OLD_SPEC = "sha256:dfb444962a5a17b3a6519e8d148c2bf4a975a921b1fcb1277710052caaecd930"
DEFAULT_NEW_SPEC = "sha256:e88147e8d6b6e8f1b0975979363ca21d3abbec96cfafeec5e106870cf5801193"
PACKAGE_RELATIVE_PATH = "blue-conformance/src/main/resources/blue-contracts-closure-1.0"

options = {
  old_specification_identity: DEFAULT_OLD_SPEC,
  new_specification_identity: DEFAULT_NEW_SPEC,
  mode: :check
}
mode_option = nil
parser = OptionParser.new do |cli|
  cli.banner = <<~USAGE
    Usage: rebind_contracts_specification_identity.rb --repository-root PATH [options]

    Recompute every specification-derived identity in the frozen 49-fixture
    closure corpus. The default --check mode works in a disposable directory
    and never mutates the supplied repository. --stage-root retains a generated
    repository-shaped package for byte comparison. --write is explicit.
  USAGE
  cli.on("--repository-root PATH", "Repository containing the source package") do |path|
    options[:repository_root] = path
  end
  cli.on("--from ID", "Old specification identity (defaults to the frozen base)") do |identity|
    options[:old_specification_identity] = identity
  end
  cli.on("--to ID", "New specification identity (defaults to full-lifecycle admission)") do |identity|
    options[:new_specification_identity] = identity
  end
  cli.on("--check", "Validate a disposable generated candidate (default)") do
    abort "choose only one of --check, --write, or --stage-root" if mode_option
    mode_option = :check
    options[:mode] = :check
  end
  cli.on("--write", "Write the validated result into --repository-root") do
    abort "choose only one of --check, --write, or --stage-root" if mode_option
    mode_option = :write
    options[:mode] = :write
  end
  cli.on("--stage-root PATH", "Retain the generated package under an empty stage root") do |path|
    abort "choose only one of --check, --write, or --stage-root" if mode_option
    mode_option = :stage
    options[:mode] = :stage
    options[:stage_root] = path
  end
end
parser.parse!
abort parser.to_s unless ARGV.empty? && options[:repository_root]

identity_pattern = /\Asha256:[0-9a-f]{64}\z/
old_specification_identity = options.fetch(:old_specification_identity)
new_specification_identity = options.fetch(:new_specification_identity)
abort "--from must be a sha256 identity" unless identity_pattern.match?(old_specification_identity)
abort "--to must be a sha256 identity" unless identity_pattern.match?(new_specification_identity)
abort "--from and --to must differ" if old_specification_identity == new_specification_identity

SOURCE_REPOSITORY_ROOT = File.expand_path(options.fetch(:repository_root))
source_package_root = File.join(SOURCE_REPOSITORY_ROOT, PACKAGE_RELATIVE_PATH)
abort "source package does not exist: #{source_package_root}" unless File.directory?(source_package_root)

cleanup_root = nil
if options[:mode] == :write
  REPOSITORY_ROOT = SOURCE_REPOSITORY_ROOT
else
  candidate_root = if options[:mode] == :stage
    File.expand_path(options.fetch(:stage_root))
  else
    cleanup_root = Dir.mktmpdir("blue-contracts-spec-rebind-")
  end
  candidate_package_root = File.join(candidate_root, PACKAGE_RELATIVE_PATH)
  abort "candidate package already exists: #{candidate_package_root}" if File.exist?(candidate_package_root)
  FileUtils.mkdir_p(File.dirname(candidate_package_root))
  FileUtils.cp_r(source_package_root, candidate_package_root)
  REPOSITORY_ROOT = candidate_root
end
at_exit { FileUtils.remove_entry(cleanup_root) if cleanup_root && File.exist?(cleanup_root) }

PACKAGE_ROOT = File.join(
  REPOSITORY_ROOT,
  PACKAGE_RELATIVE_PATH
)
FIXTURE_ROOT = File.join(PACKAGE_ROOT, "fixtures")
CLOSURE_ROOT = File.join(FIXTURE_ROOT, "closure")

OLD_SPEC = old_specification_identity
NEW_SPEC = new_specification_identity
EXPECTED_EXECUTABLE_FIXTURES = 49
EXPECTED_MAIN_SUBSTITUTIONS = 4_970
EXPECTED_TRACE_SUBSTITUTIONS = 11_858
EXPECTED_WORK_CLAIMS = 866
EXPECTED_EVENT_WORK_CLAIMS = 811
EXPECTED_PUBLIC_EVENT_CLAIMS = 16
EXPECTED_REJECTED_WORK_CLAIMS = 4
EXPECTED_INLINE_GAS_WORK_REFERENCES = 2_900
EXPECTED_DETACHED_GAS_WORK_REFERENCES = 11_858
EXPECTED_PUBLIC_AGGREGATES = 48
EXPECTED_GAS_AGGREGATES = 48
EXPECTED_REJECTED_CHARGES = 6
EXPECTED_COMPANIONS = 37

IDENTITY_LINE = /^(\s*(?:contractsSpecificationIdentity|invocationIdentity|workIdentity|workOccurrenceId|workOccurrenceIdentity|sourceOccurrenceIdentity|eventOccurrenceIdentity|publicEventsIdentity|gasTraceIdentity|rejectedChargeIdentity|companionIdentity):\s*)(sha256:[0-9a-f]{64})(\s*)$/

def deep_copy(value)
  Marshal.load(Marshal.dump(value))
end

def canonical_value(value)
  case value
  when Hash
    value.keys.sort.each_with_object({}) do |key, result|
      result[key] = canonical_value(value.fetch(key))
    end
  when Array
    value.map { |item| canonical_value(item) }
  when String, Integer, TrueClass, FalseClass, NilClass
    value
  else
    abort "unsupported RFC-8785 fixture value #{value.class}: #{value.inspect}"
  end
end

def canonical_json(value)
  JSON.generate(canonical_value(value))
end

def identity(domain, value)
  "sha256:" + Digest::SHA256.hexdigest(
    canonical_json({"domain" => domain, "value" => value})
  )
end

def normalized_bytes(bytes)
  bytes.gsub("\r\n", "\n").gsub("\r", "\n")
end

def raw_sha256(bytes)
  Digest::SHA256.hexdigest(bytes)
end

def package_identity(yaml, field)
  value = deep_copy(yaml)
  value[field] = nil
  "sha256:" + Digest::SHA256.hexdigest(canonical_json(value))
end

def invocation_value(fixture, specification_identity)
  input = fixture.fetch("input")
  environment = input.fetch("environment")
  documents = input.fetch("documents").values
    .sort_by { |document| document.fetch("documentId") }
    .map do |document|
      document.slice(
        "documentId",
        "blueId",
        "initialized",
        "terminated",
        "publicRoot",
        "epoch",
        "componentGeneration"
      )
    end
  {
    "operation" => fixture.fetch("operation"),
    "causeIdentity" => input.fetch("cause").fetch("causeIdentity"),
    "admissionCandidateIdentity" => input.fetch("admissionCandidateIdentity"),
    "inputGraphGeneration" => input.fetch("graphGeneration"),
    "inputClosureIdentity" => input.fetch("closureIdentity"),
    "documents" => documents,
    "directDeliverySnapshotIdentity" => input.fetch("directDeliverySnapshotIdentity"),
    "occurrenceBindingSetIdentity" => input.fetch("occurrenceBindingSetIdentity"),
    "runtimeRegistryIdentity" => environment.fetch("runtimeRegistryIdentity"),
    "gasPolicyIdentity" => input.fetch("gasPolicy").fetch("policyIdentity"),
    "cyclicFinalizerIdentity" => environment.fetch("cyclicFinalizerIdentity"),
    "cyclicProofVerifierIdentity" => environment.fetch("cyclicProofVerifierIdentity"),
    "blueLanguageSpecificationIdentity" => environment.fetch("blueLanguageSpecificationIdentity"),
    "contractsSpecificationIdentity" => specification_identity,
    "managedDocumentIdentityPolicyIdentity" => environment.fetch("managedDocumentIdentityPolicy").fetch("identity"),
    "managedBindingPolicyIdentity" => environment.fetch("managedBindingPolicy").fetch("identity"),
    "exactNodeProviderDomainIdentity" => environment.fetch("exactNodeProviderDomain").fetch("identity"),
    "externalOrderPolicyIdentity" => environment.fetch("externalOrderPolicy").fetch("identity"),
    "gasManifestIdentity" => environment.fetch("gasManifestIdentity"),
    "portableLimitPolicyIdentity" => environment.fetch("portableLimitPolicy").fetch("identity")
  }
end

def assert_equal(expected, actual, label)
  return if expected == actual

  abort "#{label}: expected #{expected.inspect}, got #{actual.inspect}"
end

def validate_fixture(fixture, gas_entries, specification_identity, label)
  counts = Hash.new(0)
  input = fixture.fetch("input")
  expected = fixture.fetch("expected")
  environment = input.fetch("environment")
  assert_equal(
    specification_identity,
    environment.fetch("contractsSpecificationIdentity"),
    "#{label} input Contracts specification"
  )
  counts["specificationClaims"] += 1

  invocation = identity(
    "blue-contracts-invocation/1.0",
    invocation_value(fixture, specification_identity)
  )
  assert_equal(invocation, input.fetch("invocationIdentity"), "#{label} input invocation")
  counts["invocationClaims"] += 1
  if expected.key?("invocationIdentity")
    assert_equal(invocation, expected.fetch("invocationIdentity"), "#{label} result invocation")
    counts["invocationClaims"] += 1
  end

  work_by_ordinal = {}
  expected.fetch("workTrace", []).each do |work|
    source = work.fetch("sourceOccurrenceIdentity")
    if ["TRIGGERED_EVENT", "EMBEDDED_EVENT"].include?(work.fetch("kind"))
      event = identity(
        "blue-contracts-event-occurrence/1.0",
        {
          "invocationIdentity" => invocation,
          "eventOccurrenceOrdinal" => work.fetch("occurrenceOrdinal"),
          "eventBlueId" => work.fetch("eventBlueId")
        }
      )
      assert_equal(event, source, "#{label} event source at work #{work.fetch('ordinal')}")
      counts["eventWorkClaims"] += 1
    end
    work_identity = identity(
      "blue-contracts-work-occurrence/1.0",
      {
        "invocationIdentity" => invocation,
        "workOrdinal" => work.fetch("ordinal"),
        "workKind" => work.fetch("kind"),
        "targetManagedScopeIdentity" => work.fetch("targetManagedScopeIdentity"),
        "sourceOccurrenceIdentity" => source
      }
    )
    assert_equal(work_identity, work.fetch("workIdentity"), "#{label} work #{work.fetch('ordinal')}")
    abort "#{label} duplicate work ordinal #{work.fetch('ordinal')}" if work_by_ordinal.key?(work.fetch("ordinal"))
    work_by_ordinal[work.fetch("ordinal")] = work_identity
    counts["workClaims"] += 1
  end

  if (rejected_work = expected["rejectedWorkOccurrence"])
    source = rejected_work.fetch("sourceOccurrenceIdentity")
    if ["TRIGGERED_EVENT", "EMBEDDED_EVENT"].include?(rejected_work.fetch("kind"))
      event = identity(
        "blue-contracts-event-occurrence/1.0",
        {
          "invocationIdentity" => invocation,
          "eventOccurrenceOrdinal" => rejected_work.fetch("occurrenceOrdinal"),
          "eventBlueId" => rejected_work.fetch("eventBlueId")
        }
      )
      assert_equal(event, source, "#{label} rejected-work event source")
      counts["rejectedWorkEventClaims"] += 1
    end
    rejected_identity = identity(
      "blue-contracts-work-occurrence/1.0",
      {
        "invocationIdentity" => invocation,
        "workOrdinal" => rejected_work.fetch("ordinal"),
        "workKind" => rejected_work.fetch("kind"),
        "targetManagedScopeIdentity" => rejected_work.fetch("targetManagedScopeIdentity"),
        "sourceOccurrenceIdentity" => source
      }
    )
    assert_equal(rejected_identity, rejected_work.fetch("workIdentity"), "#{label} rejected work")
    assert_equal(
      work_by_ordinal.fetch(rejected_work.fetch("ordinal")),
      rejected_identity,
      "#{label} rejected-work trace binding"
    )
    counts["rejectedWorkClaims"] += 1
  end

  if expected.key?("publicEventsIdentity")
    public_projection = expected.fetch("publicEvents", []).map do |event|
      event_identity = identity(
        "blue-contracts-event-occurrence/1.0",
        {
          "invocationIdentity" => invocation,
          "eventOccurrenceOrdinal" => event.fetch("eventOccurrenceOrdinal"),
          "eventBlueId" => event.fetch("eventBlueId")
        }
      )
      assert_equal(event_identity, event.fetch("eventOccurrenceIdentity"), "#{label} public event")
      counts["publicEventClaims"] += 1
      event.slice(
        "publicEventOrdinal",
        "eventOccurrenceOrdinal",
        "publicRootDocumentId",
        "eventOccurrenceIdentity",
        "eventBlueId"
      )
    end
    public_identity = identity("blue-contracts-public-events/1.0", public_projection)
    assert_equal(public_identity, expected.fetch("publicEventsIdentity"), "#{label} public aggregate")
    counts["publicAggregateClaims"] += 1
  end

  if expected.key?("gasTraceIdentity")
    gas_projection = gas_entries.map do |entry|
      value = entry.reject { |key, _| key == "reason" }
      if value.key?("workOccurrenceId")
        abort "#{label} gas trace names unknown work #{value.fetch('workOccurrenceId')}" unless work_by_ordinal.value?(value.fetch("workOccurrenceId"))
        counts["gasWorkReferences"] += 1
      end
      value
    end
    gas_identity = identity("blue-contracts-gas-trace/1.0", gas_projection)
    assert_equal(gas_identity, expected.fetch("gasTraceIdentity"), "#{label} gas aggregate")
    counts["gasAggregateClaims"] += 1
  end

  if (rejected_charge = expected["rejectedCharge"])
    rejected_value = rejected_charge.reject { |key, _| key == "rejectedChargeIdentity" }
    if rejected_value.fetch("owner").fetch("kind") == "WORK"
      abort "#{label} rejected charge names unknown work" unless work_by_ordinal.value?(
        rejected_value.fetch("owner").fetch("workOccurrenceIdentity")
      )
    end
    rejected_identity = identity("blue-contracts-rejected-charge/1.0", rejected_value)
    assert_equal(
      rejected_identity,
      rejected_charge.fetch("rejectedChargeIdentity"),
      "#{label} rejected charge"
    )
    counts["rejectedChargeClaims"] += 1
  end

  if (companion = expected["platformCommitCompanion"])
    assert_equal(
      specification_identity,
      companion.fetch("contractsSpecificationIdentity"),
      "#{label} companion Contracts specification"
    )
    assert_equal(invocation, companion.fetch("invocationIdentity"), "#{label} companion invocation")
    counts["specificationClaims"] += 1
    counts["invocationClaims"] += 1
    companion_value = deep_copy(companion)
    companion_claim = companion_value.delete("companionIdentity")
    companion_identity = identity(
      "blue-contracts-platform-commit-companion/1.0",
      companion_value
    )
    assert_equal(companion_identity, companion_claim, "#{label} companion")
    counts["companionClaims"] += 1
  end

  counts
end

def merge_counts(target, source)
  source.each { |key, value| target[key] += value }
end

def build_replacements(old_fixture, new_specification_identity, old_gas_entries)
  input = old_fixture.fetch("input")
  expected = old_fixture.fetch("expected")
  old_invocation = input.fetch("invocationIdentity")
  new_invocation = identity(
    "blue-contracts-invocation/1.0",
    invocation_value(old_fixture, new_specification_identity)
  )
  replacements = {
    OLD_SPEC => new_specification_identity,
    old_invocation => new_invocation
  }
  old_work_to_new = {}

  expected.fetch("workTrace", []).each do |work|
    old_source = work.fetch("sourceOccurrenceIdentity")
    new_source = old_source
    if ["TRIGGERED_EVENT", "EMBEDDED_EVENT"].include?(work.fetch("kind"))
      new_source = identity(
        "blue-contracts-event-occurrence/1.0",
        {
          "invocationIdentity" => new_invocation,
          "eventOccurrenceOrdinal" => work.fetch("occurrenceOrdinal"),
          "eventBlueId" => work.fetch("eventBlueId")
        }
      )
      replacements[old_source] = new_source
    end
    new_work = identity(
      "blue-contracts-work-occurrence/1.0",
      {
        "invocationIdentity" => new_invocation,
        "workOrdinal" => work.fetch("ordinal"),
        "workKind" => work.fetch("kind"),
        "targetManagedScopeIdentity" => work.fetch("targetManagedScopeIdentity"),
        "sourceOccurrenceIdentity" => new_source
      }
    )
    replacements[work.fetch("workIdentity")] = new_work
    old_work_to_new[work.fetch("workIdentity")] = new_work
  end

  old_public_projection = []
  new_public_projection = []
  expected.fetch("publicEvents", []).each do |event|
    old_event = event.fetch("eventOccurrenceIdentity")
    new_event = identity(
      "blue-contracts-event-occurrence/1.0",
      {
        "invocationIdentity" => new_invocation,
        "eventOccurrenceOrdinal" => event.fetch("eventOccurrenceOrdinal"),
        "eventBlueId" => event.fetch("eventBlueId")
      }
    )
    replacements[old_event] = new_event
    old_projection = event.slice(
      "publicEventOrdinal",
      "eventOccurrenceOrdinal",
      "publicRootDocumentId",
      "eventOccurrenceIdentity",
      "eventBlueId"
    )
    old_public_projection << old_projection
    new_public_projection << old_projection.merge("eventOccurrenceIdentity" => new_event)
  end
  if expected.key?("publicEventsIdentity")
    old_public = identity("blue-contracts-public-events/1.0", old_public_projection)
    new_public = identity("blue-contracts-public-events/1.0", new_public_projection)
    replacements[old_public] = new_public unless old_public == new_public
  end

  if expected.key?("gasTraceIdentity")
    old_gas_projection = old_gas_entries.map { |entry| entry.reject { |key, _| key == "reason" } }
    new_gas_projection = old_gas_projection.map do |entry|
      next entry unless entry.key?("workOccurrenceId")

      entry.merge(
        "workOccurrenceId" => old_work_to_new.fetch(entry.fetch("workOccurrenceId"))
      )
    end
    old_gas = identity("blue-contracts-gas-trace/1.0", old_gas_projection)
    new_gas = identity("blue-contracts-gas-trace/1.0", new_gas_projection)
    replacements[old_gas] = new_gas unless old_gas == new_gas
  end

  if (rejected_charge = expected["rejectedCharge"])
    old_rejected_value = rejected_charge.reject { |key, _| key == "rejectedChargeIdentity" }
    new_rejected_value = deep_copy(old_rejected_value)
    if new_rejected_value.fetch("owner").fetch("kind") == "WORK"
      owner = new_rejected_value.fetch("owner")
      owner["workOccurrenceIdentity"] = old_work_to_new.fetch(
        owner.fetch("workOccurrenceIdentity")
      )
    end
    old_rejected = identity("blue-contracts-rejected-charge/1.0", old_rejected_value)
    new_rejected = identity("blue-contracts-rejected-charge/1.0", new_rejected_value)
    replacements[old_rejected] = new_rejected unless old_rejected == new_rejected
  end

  if (companion = expected["platformCommitCompanion"])
    old_companion_value = deep_copy(companion)
    old_companion = old_companion_value.delete("companionIdentity")
    new_companion_value = deep_copy(old_companion_value)
    new_companion_value["invocationIdentity"] = new_invocation
    new_companion_value["contractsSpecificationIdentity"] = new_specification_identity
    if replacements.key?(new_companion_value.fetch("publicEventsIdentity"))
      new_companion_value["publicEventsIdentity"] = replacements.fetch(
        new_companion_value.fetch("publicEventsIdentity")
      )
    end
    if replacements.key?(new_companion_value.fetch("gasTraceIdentity"))
      new_companion_value["gasTraceIdentity"] = replacements.fetch(
        new_companion_value.fetch("gasTraceIdentity")
      )
    end
    new_companion = identity(
      "blue-contracts-platform-commit-companion/1.0",
      new_companion_value
    )
    replacements[old_companion] = new_companion
  end

  replacements.reject { |old_value, new_value| old_value == new_value }
end

def replace_identity_lines(text, replacements, label)
  count = 0
  updated = text.each_line.map do |line|
    match = IDENTITY_LINE.match(line.chomp("\n"))
    unless match && replacements.key?(match[2])
      next line
    end
    count += 1
    newline = line.end_with?("\n") ? "\n" : ""
    "#{match[1]}#{replacements.fetch(match[2])}#{match[3]}#{newline}"
  end.join
  abort "#{label} byte count changed" unless updated.bytesize == text.bytesize
  [updated, count]
end

def update_manifest_entries(text, fixture_root, staged_files)
  lines = text.lines
  changed = 0
  index = 0
  while index < lines.length
    path_match = /^- path: (.+)\n?$/.match(lines[index])
    unless path_match
      index += 1
      next
    end
    relative = path_match[1]
    block_end = index + 1
    block_end += 1 while block_end < lines.length && !lines[block_end].start_with?("- path: ")
    absolute = File.join(fixture_root, relative)
    if staged_files.key?(absolute)
      normalized = normalized_bytes(staged_files.fetch(absolute))
      digest = raw_sha256(normalized)
      sha_index = (index...block_end).find { |candidate| lines[candidate].match?(/^  sha256: /) }
      bytes_index = (index...block_end).find { |candidate| lines[candidate].match?(/^  bytes: /) }
      abort "manifest entry lacks digest/bytes for #{relative}" unless sha_index && bytes_index
      declared_bytes = lines[bytes_index].sub(/^  bytes: /, "").to_i
      assert_equal(declared_bytes, normalized.bytesize, "manifest bytes #{relative}")
      lines[sha_index] = "  sha256: #{digest}\n"
      changed += 1
    end
    index = block_end
  end
  assert_equal(staged_files.size, changed, "changed fixture manifest entries")
  lines.join
end

def verify_manifest_files(manifest, fixture_root, staged_files = {})
  manifest.fetch("files").each do |entry|
    absolute = File.join(fixture_root, entry.fetch("path"))
    bytes = staged_files.fetch(absolute) { File.binread(absolute) }
    normalized = normalized_bytes(bytes)
    assert_equal(entry.fetch("bytes"), normalized.bytesize, "manifest bytes #{entry.fetch('path')}")
    assert_equal(entry.fetch("sha256"), raw_sha256(normalized), "manifest digest #{entry.fetch('path')}")
  end
end

fixture_paths = Dir[File.join(CLOSURE_ROOT, "*.yaml")].sort.select do |path|
  File.binread(path).include?(OLD_SPEC.delete_prefix("sha256:"))
end
assert_equal(EXPECTED_EXECUTABLE_FIXTURES, fixture_paths.size, "executable fixture count")

old_manifest_text = File.binread(File.join(FIXTURE_ROOT, "manifest.yaml"))
old_manifest = YAML.load(old_manifest_text)
assert_equal(
  old_manifest.fetch("packageIdentity"),
  package_identity(old_manifest, "packageIdentity"),
  "pre-rebind fixture package identity"
)
verify_manifest_files(old_manifest, FIXTURE_ROOT)

old_release_text = File.binread(File.join(PACKAGE_ROOT, "release-manifest.yaml"))
old_release = YAML.load(old_release_text)
assert_equal(
  old_release.fetch("releaseIdentity"),
  package_identity(old_release, "releaseIdentity"),
  "pre-rebind release identity"
)

old_registry_text = File.binread(File.join(PACKAGE_ROOT, "registry/manifest.yaml"))
old_registry = YAML.load(old_registry_text)
assert_equal(
  old_manifest.fetch("packageIdentity"),
  old_registry.fetch("fixturePackageIdentity"),
  "pre-rebind registry fixture binding"
)
assert_equal(
  old_manifest.fetch("packageIdentity"),
  old_release.fetch("fixturePackage").fetch("packageIdentity"),
  "pre-rebind release fixture binding"
)

old_counts = Hash.new(0)
staged_files = {}
staged_fixture_objects = {}
staged_trace_objects = {}
main_substitutions = 0
trace_substitutions = 0

fixture_paths.each do |fixture_path|
  fixture_text = File.binread(fixture_path)
  fixture = YAML.load(fixture_text)
  expected = fixture.fetch("expected")
  trace_relative = expected["gasTraceFile"]
  trace_path = trace_relative && File.join(CLOSURE_ROOT, trace_relative)
  trace_object = if trace_path
    YAML.load(File.binread(trace_path)).fetch("entries")
  else
    expected.fetch("gasTrace", [])
  end

  merge_counts(
    old_counts,
    validate_fixture(fixture, trace_object, OLD_SPEC, File.basename(fixture_path))
  )
  replacements = build_replacements(fixture, NEW_SPEC, trace_object)
  updated_fixture_text, fixture_substitutions = replace_identity_lines(
    fixture_text,
    replacements,
    File.basename(fixture_path)
  )
  staged_files[fixture_path] = updated_fixture_text
  staged_fixture_objects[fixture_path] = YAML.load(updated_fixture_text)
  main_substitutions += fixture_substitutions

  if trace_path
    trace_text = File.binread(trace_path)
    updated_trace_text, detached_substitutions = replace_identity_lines(
      trace_text,
      replacements,
      File.basename(trace_path)
    )
    staged_files[trace_path] = updated_trace_text
    staged_trace_objects[trace_path] = YAML.load(updated_trace_text).fetch("entries")
    trace_substitutions += detached_substitutions
  end
end

assert_equal(EXPECTED_MAIN_SUBSTITUTIONS, main_substitutions, "main fixture substitutions")
assert_equal(EXPECTED_TRACE_SUBSTITUTIONS, trace_substitutions, "detached trace substitutions")
assert_equal(50, staged_files.size, "changed fixture/trace file count")

expected_old_counts = {
  "specificationClaims" => 86,
  "invocationClaims" => 134,
  "workClaims" => EXPECTED_WORK_CLAIMS,
  "eventWorkClaims" => EXPECTED_EVENT_WORK_CLAIMS,
  "rejectedWorkEventClaims" => EXPECTED_REJECTED_WORK_CLAIMS,
  "rejectedWorkClaims" => EXPECTED_REJECTED_WORK_CLAIMS,
  "publicEventClaims" => EXPECTED_PUBLIC_EVENT_CLAIMS,
  "gasWorkReferences" => EXPECTED_INLINE_GAS_WORK_REFERENCES + EXPECTED_DETACHED_GAS_WORK_REFERENCES,
  "publicAggregateClaims" => EXPECTED_PUBLIC_AGGREGATES,
  "gasAggregateClaims" => EXPECTED_GAS_AGGREGATES,
  "rejectedChargeClaims" => EXPECTED_REJECTED_CHARGES,
  "companionClaims" => EXPECTED_COMPANIONS
}
assert_equal(expected_old_counts, old_counts, "old constructor assertion counts")

new_counts = Hash.new(0)
fixture_paths.each do |fixture_path|
  fixture = staged_fixture_objects.fetch(fixture_path)
  trace_relative = fixture.fetch("expected")["gasTraceFile"]
  trace_object = if trace_relative
    staged_trace_objects.fetch(File.join(CLOSURE_ROOT, trace_relative))
  else
    fixture.fetch("expected").fetch("gasTrace", [])
  end
  merge_counts(
    new_counts,
    validate_fixture(fixture, trace_object, NEW_SPEC, "new #{File.basename(fixture_path)}")
  )
end
assert_equal(expected_old_counts, new_counts, "new constructor assertion counts")

new_manifest_text = update_manifest_entries(old_manifest_text, FIXTURE_ROOT, staged_files)
manifest_without_identity = YAML.load(new_manifest_text)
new_package_identity = package_identity(manifest_without_identity, "packageIdentity")
new_manifest_text = new_manifest_text.sub(
  /^packageIdentity: sha256:[0-9a-f]{64}$/,
  "packageIdentity: #{new_package_identity}"
)
new_manifest = YAML.load(new_manifest_text)
assert_equal(new_package_identity, new_manifest.fetch("packageIdentity"), "staged manifest package field")
assert_equal(new_package_identity, package_identity(new_manifest, "packageIdentity"), "staged fixture package identity")
verify_manifest_files(new_manifest, FIXTURE_ROOT, staged_files)

old_package_identity = old_manifest.fetch("packageIdentity")
new_registry_text = old_registry_text.sub(
  /^fixturePackageIdentity: #{Regexp.escape(old_package_identity)}$/,
  "fixturePackageIdentity: #{new_package_identity}"
)
abort "registry fixture binding was not replaced" if new_registry_text == old_registry_text
new_registry = YAML.load(new_registry_text)
assert_equal(new_package_identity, new_registry.fetch("fixturePackageIdentity"), "staged registry binding")

release_with_package = old_release_text.sub(old_package_identity, new_package_identity)
abort "release fixture binding was not replaced" if release_with_package == old_release_text
release_with_specification = release_with_package.sub(
  /^  sha256: #{Regexp.escape(OLD_SPEC.delete_prefix("sha256:"))}$/,
  "  sha256: #{NEW_SPEC.delete_prefix("sha256:")}"
)
abort "release specification digest was not replaced" if release_with_specification == release_with_package
release_without_new_identity = YAML.load(release_with_specification)
new_release_identity = package_identity(release_without_new_identity, "releaseIdentity")
new_release_text = release_with_specification.sub(
  /^releaseIdentity: sha256:[0-9a-f]{64}$/,
  "releaseIdentity: #{new_release_identity}"
)
new_release = YAML.load(new_release_text)
assert_equal(
  new_package_identity,
  new_release.fetch("fixturePackage").fetch("packageIdentity"),
  "staged release fixture binding"
)
assert_equal(new_release_identity, new_release.fetch("releaseIdentity"), "staged release field")
assert_equal(new_release_identity, package_identity(new_release, "releaseIdentity"), "staged release identity")

# No repository write occurs until every old and proposed-new constructor,
# file digest, package identity, and release identity assertion above passes.
staged_files.each { |path, bytes| File.binwrite(path, bytes) }
File.binwrite(File.join(FIXTURE_ROOT, "manifest.yaml"), new_manifest_text)
File.binwrite(File.join(PACKAGE_ROOT, "registry/manifest.yaml"), new_registry_text)
File.binwrite(File.join(PACKAGE_ROOT, "release-manifest.yaml"), new_release_text)

result = {
  "calculator" => File.expand_path(__FILE__),
  "mode" => options.fetch(:mode).to_s,
  "sourceRepositoryRoot" => SOURCE_REPOSITORY_ROOT,
  "generatedRepositoryRoot" => REPOSITORY_ROOT,
  "sourceRepositoryMutated" => options[:mode] == :write,
  "oldSpecificationIdentity" => OLD_SPEC,
  "newSpecificationIdentity" => NEW_SPEC,
  "executableFixtures" => fixture_paths.size,
  "changedFixtureAndTraceFiles" => staged_files.size,
  "mainSubstitutions" => main_substitutions,
  "detachedTraceSubstitutions" => trace_substitutions,
  "oldAssertionCounts" => old_counts,
  "newAssertionCounts" => new_counts,
  "oldFixturePackageIdentity" => old_package_identity,
  "newFixturePackageIdentity" => new_package_identity,
  "oldReleaseIdentity" => old_release.fetch("releaseIdentity"),
  "newReleaseIdentity" => new_release_identity,
  "changedManifestFileDigests" => staged_files.size
}
puts JSON.pretty_generate(result)
