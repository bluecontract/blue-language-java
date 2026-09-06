#!/usr/bin/env ruby
# Mechanically installs explicitly approved, source-hash-bound review snapshots.
# This is a one-off local snapshot installer, not a release or semantic generator:
# independent review must precede approval.
require 'json'
require 'yaml'
require 'digest'
require 'pathname'

def require_valid(condition, message)
  raise ArgumentError, message unless condition
end

def canonical(value)
  case value
  when Hash
    require_valid(value.keys.all? { |key| key.is_a?(String) && key.ascii_only? }, 'Manifest keys must stay ASCII strings')
    value.keys.sort.each_with_object({}) { |key, result| result[key] = canonical(value[key]) }
  when Array
    value.map { |entry| canonical(entry) }
  when Float
    raise ArgumentError, 'Manifest numeric identities require integer-only inputs'
  else
    value
  end
end

def identity(value, null_fields)
  selected = value.dup
  null_fields.each { |field| selected[field] = nil }
  'sha256:' + Digest::SHA256.hexdigest(JSON.generate(canonical(selected)))
end

def yaml_text(value)
  text = YAML.dump(value).sub(/\A---\n/, '').gsub(/:[ \t]+$/, ': null').gsub(/^(\s*)-[ \t]+$/, '\1- null')
  require_valid(YAML.load(text) == value, 'Snapshot formatting changed its YAML value')
  text
end

def replace_exact_binding_literals(text, old_value, new_value, expected_count, label)
  quoted = '"' + old_value + '"'
  require_valid(text.scan(Regexp.new(Regexp.escape(quoted))).size == expected_count,
                "Unexpected original literal count in #{label}")
  text.gsub(quoted, '"' + new_value + '"')
end

# Pure, closed companion transformation. The caller supplies reads so this can
# be proved against Git HEAD without running any installer writes.
def reviewed_binding_candidates(read_source, old_fixture, new_fixture, old_release, new_release)
  reviewed_predecessors = [
    ['sha256:837e369b443b1c5ebab7f52d290e9d45fe385e40f91a683ac81a8ef7abb2b51c',
     'sha256:130218cd088651b64b13ffe2a0bd1ae4c0220c000a543f3e8346fec08459dbb3'],
    ['sha256:86e7d82cbd5e806dac84bff03e9a29c00846fdf88dbbbc09d909c37932e071e6',
     'sha256:2385edf920fd11dc9651cd0d1a91fe5467bc2d34d3988881848f57ea9777b46b']
  ]
  require_valid(reviewed_predecessors.include?([old_fixture, old_release]),
                'Binding refresh is only defined for the original or reviewed post-36 snapshot')
  [new_fixture, new_release].each do |value|
    require_valid(value.match?(/\Asha256:[0-9a-f]{64}\z/), 'Invalid resulting package identity')
  end
  candidates = {}
  declarations = [
    ['blue-conformance/src/main/java/blue/language/conformance/api/BlueContractsConformanceReport.java',
     'CONTRACTS_FIXTURE_PACKAGE_IDENTITY', old_fixture, new_fixture],
    ['blue-conformance/src/main/java/blue/language/conformance/api/BlueContractsFixturePackage.java',
     'CONTRACTS_RELEASE_IDENTITY', old_release, new_release],
    ['blue-conformance/src/main/java/blue/language/conformance/contracts/closure/ClosureFixtureInventory.java',
     'PACKAGE_IDENTITY', old_fixture, new_fixture]
  ]
  declarations.each do |path, symbol, old_value, new_value|
    source = read_source.call(path)
    declaration = /(\b#{Regexp.escape(symbol)}\s*=\s*")([^"]+)(";)/
    matches = source.scan(declaration)
    require_valid(matches.size == 1 && matches.first[1] == old_value,
                  "Original Java binding differs: #{path}:#{symbol}")
    candidates[path] = source.sub(declaration) { Regexp.last_match[1] + new_value + Regexp.last_match[3] }
  end

  test_path = 'src/test/java/blue/language/conformance/contracts/BlueContractsConformanceReportTest.java'
  test_source = read_source.call(test_path)
  test_source = replace_exact_binding_literals(test_source, old_fixture, new_fixture, 2, test_path)
  candidates[test_path] = replace_exact_binding_literals(test_source, old_release, new_release, 2, test_path)

  baseline_path = 'api/semantic-baseline-1.0.json'
  baseline_source = read_source.call(baseline_path)
  baseline = JSON.parse(baseline_source)
  bindings = [
    [%w[release contractsReleaseIdentity], old_release, new_release],
    [%w[packages contractsFixtures], old_fixture, new_fixture],
    [%w[packages contractsRelease], old_release, new_release],
    [%w[gas oraclePackageIdentity], old_fixture, new_fixture]
  ]
  bindings.each do |path, old_value, new_value|
    parent = path[0...-1].reduce(baseline) { |node, key| node.fetch(key) }
    require_valid(parent.fetch(path.last) == old_value, "Original baseline binding differs: #{path.join('.')}")
    parent[path.last] = new_value
  end
  baseline_source = replace_exact_binding_literals(baseline_source, old_fixture, new_fixture, 2, baseline_path)
  baseline_source = replace_exact_binding_literals(baseline_source, old_release, new_release, 2, baseline_path)
  require_valid(JSON.parse(baseline_source) == baseline, 'Baseline replacement changed an unselected field')
  candidates[baseline_path] = baseline_source
  candidates
end

require_valid(ARGV.size == 2 || (ARGV.size == 3 && ARGV[2] == '--check'),
              'Expected review report directory, explicit approval JSON and optional --check')
reports = File.realpath(ARGV[0])
approval = JSON.parse(File.binread(ARGV[1]))
require_valid(approval['kind'] == 'approved-local-poc-fixture-deltas', 'Not an explicit local POC approval')
root = File.realpath(approval.fetch('packageRoot'))
require_valid(root.end_with?('/blue-conformance/src/main/resources/blue-contracts-closure-1.0'), 'Unexpected package root')
require_valid(approval.fetch('reports').size > 0, 'No approved reports')
pending = {}

approval.fetch('reports').each do |entry|
  id = entry.fetch('id')
  require_valid(id.match?(/\A(?:c-clo|fl-adm)-[a-z0-9-]+\z/), 'Unsafe fixture ID')
  raw_report = File.binread(File.join(reports, id + '.json'))
  require_valid(Digest::SHA256.hexdigest(raw_report) == entry.fetch('sha256'), "Unapproved report: #{id}")
  report = JSON.parse(raw_report)
  require_valid(report['id'] == id && report['path'] == "closure/#{id}.yaml", 'Report location mismatch')
  path = File.join(root, 'fixtures', report.fetch('path'))
  require_valid(File.realpath(path).start_with?(root + '/'), 'Fixture path escapes package')
  require_valid(!pending.key?(path), 'Repeated approved fixture')
  raw_fixture = File.binread(path)
  require_valid(Digest::SHA256.hexdigest(raw_fixture) == report.fetch('sourceSha256'), "Fixture changed since review: #{id}")
  fixture = YAML.load(raw_fixture)
  expected = fixture.fetch('expected')
  actual = report.fetch('actualAfter')
  %w[attemptOutcome status].each do |field|
    require_valid(expected.fetch(field) == actual.fetch(field), "Unapproved outcome change: #{id}")
  end
  # Preserve custom observations and other harness-specific assertions that
  # the generic full-result serializer does not own. Do not add new fields.
  updated = expected.each_with_object({}) do |(key, old), result|
    result[key] = actual.key?(key) ? actual[key] : old
  end
  if expected.key?('gasTraceFile')
    relative_trace = expected.fetch('gasTraceFile')
    require_valid(relative_trace.match?(/\Atraces\/[a-z0-9-]+\.yaml\z/), 'Unsafe trace path')
    trace_path = File.join(root, 'fixtures/closure', relative_trace)
    require_valid(File.realpath(trace_path).start_with?(root + '/'), 'Trace path escapes package')
    trace = YAML.load_file(trace_path)
    require_valid(trace.fetch('fixture') == id, 'Trace belongs to another fixture')
    require_valid(trace.fetch('entries') == report.fetch('expectedBefore').fetch('gasTrace'), 'Trace changed since review')
    if trace.fetch('entries') != actual.fetch('gasTrace')
      trace['entries'] = actual.fetch('gasTrace')
      pending[trace_path] = yaml_text(trace)
    end
  end
  next if updated == expected
  require_valid(raw_fixture.scan(/^expected:[ \t]*\r?$/).size == 1, 'Expected must have one exact section')
  position = raw_fixture.index(/^expected:[ \t]*\r?$/)
  require_valid(!position.nil?, 'Missing exact expected section')
  suffix = raw_fixture.index(/^[A-Za-z][A-Za-z0-9_-]*:/, position + 'expected:'.length) || raw_fixture.length
  candidate = raw_fixture[0...position] + yaml_text('expected' => updated) + raw_fixture[suffix..-1]
  candidate_input = YAML.load(candidate).reject { |key, _| key == 'expected' }
  require_valid(candidate_input == fixture.reject { |key, _| key == 'expected' }, 'Attempted fixture input change')
  pending[path] = candidate
end

manifest_path = File.join(root, 'fixtures/manifest.yaml')
registry_path = File.join(root, 'registry/manifest.yaml')
release_path = File.join(root, 'release-manifest.yaml')
manifest = YAML.load_file(manifest_path)
original_c34_row = manifest.fetch('files').find do |row|
  row['path'] == 'closure/c-clo-34-separate-document-steps.yaml'
end
require_valid(!original_c34_row.nil?, 'Missing original C34 inventory row')
original_c34_row = original_c34_row.dup
registry = YAML.load_file(registry_path)
release = YAML.load_file(release_path)
old_identity = manifest.fetch('packageIdentity')
require_valid(registry.fetch('fixturePackageIdentity') == old_identity, 'Registry refers to another fixture package')
require_valid(release.fetch('fixturePackage').fetch('packageIdentity') == old_identity, 'Release refers to another fixture package')
require_valid(identity(manifest, ['packageIdentity']) == old_identity, 'Input fixture manifest identity mismatch')
require_valid(identity(registry, %w[packageIdentity fixturePackageIdentity]) == registry.fetch('packageIdentity'), 'Input registry identity mismatch')
require_valid(identity(release, ['releaseIdentity']) == release.fetch('releaseIdentity'), 'Input release identity mismatch')
manifest_text = File.binread(manifest_path)
pending.each do |path, bytes|
  relative = Pathname.new(path).relative_path_from(Pathname.new(File.join(root, 'fixtures'))).to_s
  row = manifest.fetch('files').find { |value| value['path'] == relative }
  require_valid(!row.nil?, "File outside existing inventory: #{relative}")
  require_valid(Digest::SHA256.hexdigest(File.binread(path)) == row.fetch('sha256'), "Original inventory hash mismatch: #{relative}")
  old = "- path: #{relative}\n  role: #{row.fetch('role')}\n  sha256: #{row.fetch('sha256')}\n  bytes: #{row.fetch('bytes')}"
  row['sha256'] = Digest::SHA256.hexdigest(bytes)
  row['bytes'] = bytes.bytesize
  replacement = "- path: #{relative}\n  role: #{row.fetch('role')}\n  sha256: #{row.fetch('sha256')}\n  bytes: #{row.fetch('bytes')}"
  require_valid(manifest_text.include?(old), 'Inventory formatting does not match the reviewed row')
  manifest_text = manifest_text.sub(old, replacement)
end
new_identity = identity(manifest, ['packageIdentity'])
manifest_text = manifest_text.sub("packageIdentity: #{old_identity}", "packageIdentity: #{new_identity}")
registry_text = File.binread(registry_path).sub("fixturePackageIdentity: #{old_identity}", "fixturePackageIdentity: #{new_identity}")
release_text = File.binread(release_path).sub("packageIdentity: #{old_identity}", "packageIdentity: #{new_identity}")
release_candidate = YAML.load(release_text)
release_text = release_text.sub("releaseIdentity: #{release.fetch('releaseIdentity')}",
                                 "releaseIdentity: #{identity(release_candidate, ['releaseIdentity'])}")
final_manifest = YAML.load(manifest_text)
final_registry = YAML.load(registry_text)
final_release = YAML.load(release_text)
require_valid(final_manifest.fetch('packageIdentity') == new_identity &&
              identity(final_manifest, ['packageIdentity']) == new_identity, 'Output fixture identity mismatch')
require_valid(final_registry.fetch('fixturePackageIdentity') == new_identity &&
              identity(final_registry, %w[packageIdentity fixturePackageIdentity]) == final_registry.fetch('packageIdentity'),
              'Output registry identity mismatch')
require_valid(final_release.fetch('fixturePackage').fetch('packageIdentity') == new_identity &&
              identity(final_release, ['releaseIdentity']) == final_release.fetch('releaseIdentity'), 'Output release identity mismatch')
pending[manifest_path] = manifest_text
pending[registry_path] = registry_text
pending[release_path] = release_text

# Validate every candidate before the first write. Only generated expectations,
# their trace files and exact existing inventory bindings can be changed here.
pending.each { |_, bytes| YAML.load(bytes) }

# Stage the exact owning source/test pins too; do not replace historical examples
# or discover arbitrary files by searching for an old identity. No write occurs
# until all original symbol values, counts and selected JSON paths are verified.
package_suffix = '/blue-conformance/src/main/resources/blue-contracts-closure-1.0'
repository_root = File.realpath(root.delete_suffix(package_suffix))
read_binding = lambda do |relative|
  path = File.realpath(File.join(repository_root, relative))
  require_valid(path == File.join(repository_root, relative), "Binding source path is not exact: #{relative}")
  File.binread(path)
end
reviewed_binding_candidates(read_binding, old_identity, new_identity,
                            release.fetch('releaseIdentity'), final_release.fetch('releaseIdentity')).each do |relative, bytes|
  path = File.join(repository_root, relative)
  require_valid(!pending.key?(path), 'Binding source overlaps another generated target')
  pending[path] = bytes
end

# One inventory control intentionally pins this complete fixture's bytes, not
# just the package identity. Keep that exact assertion in sync as well.
c34_test_path = 'blue-conformance/src/test/java/blue/language/conformance/contracts/closure/ClosureConformanceHarnessTest.java'
c34_source = read_binding.call(c34_test_path)
c34_row = final_manifest.fetch('files').find { |row| row['path'] == original_c34_row.fetch('path') }
c34_candidate = replace_exact_binding_literals(c34_source, original_c34_row.fetch('sha256'),
                                                c34_row.fetch('sha256'), 1, c34_test_path)
c34_byte_assertion = /(assertEquals\()(\d+)(L,\s*separateDocuments\.bytes\(\)\);)/
c34_matches = c34_candidate.scan(c34_byte_assertion)
require_valid(c34_matches.size == 1 && c34_matches.first[1].to_i == original_c34_row.fetch('bytes'),
              'Original C34 byte assertion differs from its inventory')
c34_candidate = c34_candidate.sub(c34_byte_assertion) do
  Regexp.last_match[1] + c34_row.fetch('bytes').to_s + Regexp.last_match[3]
end
pending[File.join(repository_root, c34_test_path)] = c34_candidate if c34_candidate != c34_source
if ARGV[2] == '--check'
  puts "Validated #{approval.fetch('reports').size} approved reports and #{pending.size} exact candidate files. No files written."
else
  pending.each { |path, bytes| File.binwrite(path, bytes) }
  puts "Applied #{approval.fetch('reports').size} approved reports; #{pending.size} local snapshot and exact-binding files updated. No release published."
end
