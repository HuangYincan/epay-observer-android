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
parser.add_argument('--variant', choices=['productionRelease', 'fixtureDebug'], required=True)
args = parser.parse_args()
sdk = Path(os.environ.get('ANDROID_HOME') or os.environ['ANDROID_SDK_ROOT'])
analyzer = sdk / 'cmdline-tools/latest/bin/apkanalyzer'
manifest = ET.fromstring(subprocess.check_output([str(analyzer), 'manifest', 'print', str(args.apk)], text=True))
android = '{http://schemas.android.com/apk/res/android}'
application = manifest.find('application')
assert application is not None
production = args.variant == 'productionRelease'
expected_id = 'dev.epay.observer' if production else 'dev.epay.observer.fixtureclient.debug'
assert manifest.attrib['package'] == expected_id
assert application.get(android + 'allowBackup') == 'false'
assert application.get(android + 'fullBackupContent') == 'false'
assert application.get(android + 'usesCleartextTraffic') == 'false'
assert application.get(android + 'networkSecurityConfig')
assert application.get(android + 'dataExtractionRules')
assert (application.get(android + 'debuggable', 'false') == 'true') == (not production)
sdk_element = manifest.find('uses-sdk')
assert sdk_element.get(android + 'minSdkVersion') == '29'
assert sdk_element.get(android + 'targetSdkVersion') == '37'
permissions = {p.get(android + 'name') for p in manifest.findall('uses-permission')}
assert permissions == {'android.permission.INTERNET'}, permissions
assert not application.findall('service')
assert not application.findall('receiver')
assert not application.findall('provider')
activities = application.findall('activity')
assert len(activities) == 1
assert activities[0].get(android + 'name') == 'dev.epay.observer.MainActivity'
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

network = packaged_xml('networkSecurityConfig')
assert not network.findall('debug-overrides')
assert network.find('base-config').get('cleartextTrafficPermitted') == 'false'
assert [node.get('src') for node in network.findall('.//certificates')] == ['system']
for config in network.findall('.//domain-config'):
    assert config.get('cleartextTrafficPermitted') == 'false'
domains = {''.join(node.itertext()).strip() for node in network.findall('.//domain')}
assert 'localhost' in domains, f'Packaged localhost rule missing: {ET.tostring(network, encoding="unicode")}'
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
        for marker in ['dev.epay.observer.fixture', 'fixture-key-a', 'fixture-wx-v1', '隔离测试环境', '合成流水-仅用于字节验证']:
            for encoding in ['utf-8', 'utf-16-le']:
                assert marker.encode(encoding) not in contents, f'Production contains {marker}'
    else:
        assert any('隔离测试环境'.encode(encoding) in contents for encoding in ['utf-8', 'utf-16-le'])

print(f'{args.variant}: packaged ID, SDK, components, backup rules, system TLS trust, localhost cleartext denial, debug flag and source-set isolation OK')
