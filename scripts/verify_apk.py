#!/usr/bin/env python3
"""BUILD-03: inspect the packaged artifact, not only source manifests."""
import argparse
import hashlib
import os
import re
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument('apk', type=Path)
parser.add_argument('--variant', choices=['productionRelease', 'fixtureDebug', 'fixtureSender'], required=True)
parser.add_argument('--fixture-ca-sha256', help='Explicit opt-in certificate digest for a local fixtureDebug APK only')
args = parser.parse_args()
assert not args.fixture_ca_sha256 or args.variant == 'fixtureDebug'
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
assert permissions == ({'android.permission.POST_NOTIFICATIONS'} if sender else {
    'android.permission.INTERNET', 'android.permission.ACCESS_NETWORK_STATE',
    'android.permission.WAKE_LOCK', 'android.permission.RECEIVE_BOOT_COMPLETED',
    expected_id + '.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION',
}), permissions
declared_permissions = manifest.findall('permission')
if sender:
    assert not declared_permissions
else:
    assert len(declared_permissions) == 1
    assert declared_permissions[0].get(android + 'name') == expected_id + '.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'
    assert declared_permissions[0].get(android + 'protectionLevel') in {'signature', '0x2'}
services = application.findall('service')
if sender:
    assert not services
else:
    assert len(services) == 3
    by_name = {s.get(android + 'name'): s for s in services}
    assert set(by_name) == {'dev.epay.observer.capture.ObserverListener', 'androidx.room.MultiInstanceInvalidationService',
                            'androidx.work.impl.background.systemjob.SystemJobService'}
    job_service = by_name['androidx.work.impl.background.systemjob.SystemJobService']
    assert job_service.get(android + 'exported') == 'true'
    assert job_service.get(android + 'permission') == 'android.permission.BIND_JOB_SERVICE'
    assert job_service.get(android + 'directBootAware') == 'false'
    room_service = by_name['androidx.room.MultiInstanceInvalidationService']
    assert room_service.get(android + 'exported') == 'false'
    services = [by_name['dev.epay.observer.capture.ObserverListener']]
    assert services[0].get(android + 'name') == 'dev.epay.observer.capture.ObserverListener'
    assert services[0].get(android + 'exported') == 'true'
    assert services[0].get(android + 'permission') == 'android.permission.BIND_NOTIFICATION_LISTENER_SERVICE'
    assert services[0].find('intent-filter/action').get(android + 'name') == 'android.service.notification.NotificationListenerService'
queries = {p.get(android + 'name') for p in manifest.findall('queries/package')}
assert queries == (set() if production or sender else {'dev.epay.observer.fixture'})
receivers = application.findall('receiver')
if sender:
    assert not receivers
else:
    by_name = {r.get(android + 'name'): r for r in receivers}
    assert set(by_name) == {'androidx.work.impl.utils.ForceStopRunnable$BroadcastReceiver',
                            'androidx.work.impl.background.systemalarm.RescheduleReceiver',
                            'androidx.profileinstaller.ProfileInstallReceiver'}
    profile = by_name['androidx.profileinstaller.ProfileInstallReceiver']
    assert profile.get(android + 'exported') == 'true' and profile.get(android + 'permission') == 'android.permission.DUMP'
    assert {a.get(android + 'name') for a in profile.findall('intent-filter/action')} == {
        'androidx.profileinstaller.action.INSTALL_PROFILE', 'androidx.profileinstaller.action.SKIP_FILE',
        'androidx.profileinstaller.action.SAVE_PROFILE', 'androidx.profileinstaller.action.BENCHMARK_OPERATION',
    }
    assert all(r.get(android + 'exported') == 'false' and r.get(android + 'directBootAware') == 'false'
               for r in receivers if r is not profile)
    reschedule = by_name['androidx.work.impl.background.systemalarm.RescheduleReceiver']
    assert {a.get(android + 'name') for a in reschedule.findall('intent-filter/action')} == {'android.intent.action.BOOT_COMPLETED'}
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
    certificates = [node.get('src') for node in network.findall('.//certificates')]
    if args.fixture_ca_sha256:
        assert re.fullmatch(r'[0-9a-f]{64}', args.fixture_ca_sha256)
        assert len(certificates) == 2 and certificates[0] == 'system' and certificates[1].startswith('@')
        custom = [node for node in network.findall('domain-config') if node.find('trust-anchors') is not None]
        assert len(custom) == 1
        assert [(''.join(node.itertext()).strip(), node.get('includeSubdomains')) for node in custom[0].findall('domain')] == [('localhost', 'false')]
        with zipfile.ZipFile(args.apk) as artifact:
            ca = artifact.read('res/raw/observer_fixture_ca.pem')
            assert hashlib.sha256(ca).hexdigest() == args.fixture_ca_sha256
            assert b'PRIVATE KEY' not in ca
    else:
        assert certificates == ['system']
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
        assert b'fixture-local-network-v1' not in contents
        assert not any('observer_fixture_ca' in name for name in apk.namelist())
        for marker in ['dev.epay.observer.fixture', 'fixture-key-a', 'fixture-wx-v1', 'fixture-ali-v1', '微信形态 · 合成收款', '支付宝形态 · 合成到账', '隔离测试环境', '合成流水-仅用于字节验证', 'synthetic-v1', 'identical-pair', 'fixture-key', 'synthetic-group']:
            for encoding in ['utf-8', 'utf-16-le']:
                assert marker.encode(encoding) not in contents, f'Production contains {marker}'
    elif not sender:
        assert b'fixture-local-network-v1' in contents
        assert any('隔离测试环境'.encode(encoding) in contents for encoding in ['utf-8', 'utf-16-le'])

print(f'{args.variant}: packaged ID, SDK, components, permissions, backup rules, network boundary, debug flag and source-set isolation OK')
