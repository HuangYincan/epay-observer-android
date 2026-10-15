#!/usr/bin/env python3
"""BUILD-03: inspect the packaged artifact, not only source manifests."""
import argparse
import os
import re
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument('apk', type=Path)
parser.add_argument('--variant', choices=['productionRelease', 'fixtureDebug', 'fixtureSender'], required=True)
args = parser.parse_args()
sdk = Path(os.environ.get('ANDROID_HOME') or os.environ['ANDROID_SDK_ROOT'])
analyzers = []
for properties in sorted((sdk / 'cmdline-tools').glob('*/source.properties')):
    values = dict(line.split('=', 1) for line in properties.read_text().splitlines() if '=' in line)
    candidate = properties.parent / 'bin/apkanalyzer'
    if values.get('Pkg.Revision', '').strip() == '23.0' and candidate.is_file():
        analyzers.append(candidate)
assert analyzers, 'Command-line Tools 23.0 is required; a directory named latest may contain an older version'
analyzer = analyzers[0]
manifest = ET.fromstring(subprocess.check_output([str(analyzer), 'manifest', 'print', str(args.apk)], text=True))
android = '{http://schemas.android.com/apk/res/android}'
application = manifest.find('application')
assert application is not None
production = args.variant == 'productionRelease'
sender = args.variant == 'fixtureSender'
expected_id = 'dev.epay.observer.fixture' if sender else ('dev.epay.observer' if production else 'dev.epay.observer.fixtureclient.debug')
assert manifest.attrib['package'] == expected_id
assert application.get(android + 'allowBackup') == 'false'
assert application.get(android + 'fullBackupContent') == 'false'
assert application.get(android + 'usesCleartextTraffic') == 'false'
assert bool(application.get(android + 'networkSecurityConfig')) == (not sender)
assert application.get(android + 'dataExtractionRules')
assert (application.get(android + 'debuggable', 'false') == 'true') == (not production)
sdk_element = manifest.find('uses-sdk')
assert sdk_element.get(android + 'minSdkVersion') == '29'
assert sdk_element.get(android + 'targetSdkVersion') == '37'
permissions = {p.get(android + 'name') for p in manifest.findall('uses-permission')}
assert permissions == ({'android.permission.POST_NOTIFICATIONS'} if sender else {'android.permission.INTERNET'}), permissions
services = application.findall('service')
if sender:
    assert not services
else:
    assert len(services) == 1
    assert services[0].get(android + 'name') == 'dev.epay.observer.capture.ObserverListener'
    assert services[0].get(android + 'exported') == 'true'
    assert services[0].get(android + 'permission') == 'android.permission.BIND_NOTIFICATION_LISTENER_SERVICE'
    assert services[0].find('intent-filter/action').get(android + 'name') == 'android.service.notification.NotificationListenerService'
queries = {p.get(android + 'name') for p in manifest.findall('queries/package')}
assert queries == (set() if production or sender else {'dev.epay.observer.fixture'})
assert not application.findall('receiver')
assert not application.findall('provider')
activities = application.findall('activity')
assert len(activities) == 1
assert activities[0].get(android + 'name') == ('dev.epay.observer.fixture.FixtureActivity' if sender else 'dev.epay.observer.MainActivity')
if sender:
    assert activities[0].get(android + 'launchMode') == '1' # singleTop in apkanalyzer output
assert activities[0].get(android + 'exported') == 'true'

# Release resource shrinking renames res/xml files; resolve the manifest's IDs.
resource_table = subprocess.check_output(
    [str(sdk / 'build-tools/36.0.0/aapt2'), 'dump', 'resources', str(args.apk)], text=True,
)

def packaged_xml(attribute):
    reference = application.get(android + attribute).split('/')[-1]
    match = re.search(r'resource ' + re.escape(reference) + r'\b[^\n]*\n\s+\(\) \(file\) (\S+)', resource_table)
    assert match, f'Cannot resolve {attribute} from packaged manifest'
    decoded = subprocess.check_output(
        [str(analyzer), 'resources', 'xml', '--file', match[1], str(args.apk)], text=True,
    )
    evidence = Path('app/build/reports/apk-inspection') / args.variant
    evidence.mkdir(parents=True, exist_ok=True)
    (evidence / f'{attribute}.xml').write_text(decoded)
    return ET.fromstring(decoded)

if not sender:
    network = packaged_xml('networkSecurityConfig')
    assert not network.findall('debug-overrides')
    assert network.find('base-config').get('cleartextTrafficPermitted') == 'false'
    assert [node.get('src') for node in network.findall('.//certificates')] == ['system']
    for config in network.findall('.//domain-config'):
        assert config.get('cleartextTrafficPermitted') == 'false'
    domains = {''.join(node.itertext()).strip() for node in network.findall('.//domain')}
    assert domains == {'localhost', 'ip6-localhost', '127.0.0.1', '::1', '[::1]'}, \
        f'Packaged domain rules changed or could not be decoded: {ET.tostring(network, encoding="unicode")}'
backup = packaged_xml('dataExtractionRules')
for section in ['cloud-backup', 'device-transfer']:
    rules = backup.find(section)
    assert rules is not None and not rules.findall('include')
    excluded = {(node.get('domain'), node.get('path')) for node in rules.findall('exclude')}
    assert all((domain, '.') in excluded for domain in [
        'root', 'file', 'database', 'sharedpref', 'external',
        'device_root', 'device_file', 'device_database', 'device_sharedpref',
    ])

with zipfile.ZipFile(args.apk) as apk:
    contents = b''.join(apk.read(name) for name in apk.namelist())
    if production:
        for marker in ['dev.epay.observer.fixture', 'fixture-key-a', 'fixture-wx-v1', '隔离测试环境', '合成流水-仅用于字节验证', 'synthetic-v1', 'identical-pair', 'fixture-key', 'synthetic-group']:
            for encoding in ['utf-8', 'utf-16-le']:
                assert marker.encode(encoding) not in contents, f'Production contains {marker}'
    elif not sender:
        assert any('隔离测试环境'.encode(encoding) in contents for encoding in ['utf-8', 'utf-16-le'])

print(f'{args.variant}: packaged ID, SDK, components, permissions, backup rules, network boundary, debug flag and source-set isolation OK')
