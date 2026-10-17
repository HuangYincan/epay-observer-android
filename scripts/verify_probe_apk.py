#!/usr/bin/env python3
"""SPEC-028: inspect the local probe artifact, independently of its source manifest."""
import argparse
import os
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('apk', type=Path)
args = parser.parse_args()
sdk = Path(os.environ.get('ANDROID_HOME') or os.environ['ANDROID_SDK_ROOT'])
analyzers = []
for properties in (sdk / 'cmdline-tools').glob('*/source.properties'):
    values = dict(line.split('=', 1) for line in properties.read_text().splitlines() if '=' in line)
    candidate = properties.parent / 'bin/apkanalyzer'
    if values.get('Pkg.Revision', '').strip() == '23.0' and candidate.is_file():
        analyzers.append(candidate)
assert analyzers, 'Existing Command-line Tools 23.0 required'
analyzer = analyzers[0]

def analyze(*arguments):
    return subprocess.check_output([str(analyzer), *arguments, str(args.apk)], text=True)

manifest = ET.fromstring(analyze('manifest', 'print'))
android = '{http://schemas.android.com/apk/res/android}'
assert manifest.attrib['package'] == 'dev.epay.observer.probe.debug'
assert not manifest.findall('uses-permission')
assert not manifest.findall('permission')
assert {p.get(android + 'name') for p in manifest.findall('queries/package')} == {'com.eg.android.AlipayGphone'}
app = manifest.find('application')
assert app is not None
for attr in ['allowBackup', 'fullBackupContent', 'usesCleartextTraffic']:
    assert app.get(android + attr) == 'false', attr
assert app.get(android + 'debuggable') == 'true'
assert app.get(android + 'dataExtractionRules')
assert not app.findall('provider') and not app.findall('receiver')
activities = app.findall('activity')
assert len(activities) == 1 and activities[0].get(android + 'name') == 'dev.epay.observer.probe.ProbeActivity'
services = app.findall('service')
assert len(services) == 1
assert services[0].get(android + 'name') == 'dev.epay.observer.probe.AlipayListener'
assert services[0].get(android + 'permission') == 'android.permission.BIND_NOTIFICATION_LISTENER_SERVICE'
assert services[0].get(android + 'exported') == 'true'
assert services[0].find('intent-filter/action').get(android + 'name') == 'android.service.notification.NotificationListenerService'

# Include generated lambdas/nested classes as well as authored top-level classes.
# Unused stdlib helpers are not our content collector's call sites.
classes = [line.split('\t')[-1] for line in analyze('dex', 'packages', '--defined-only').splitlines()
           if line.startswith('C d ') and line.split('\t')[-1].startswith('dev.epay.observer.probe.')]
assert 'dev.epay.observer.probe.AlipayListener' in classes
for name in classes:
    code = analyze('dex', 'code', '--class', name)
    for forbidden in ['->getNotification(', '->getActiveNotifications(', '->getSnoozedNotifications(',
                      '->getKey(', '->getPostTime(', '->getTag(', '->getExtras(', '->getChannelId(',
                      'Landroid/app/Notification;', 'Landroid/util/Log;', 'Ljava/net/', 'Ljava/io/',
                      'Landroid/database/', '->getSharedPreferences(', '->getIntent(', 'Ljava/lang/reflect/']:
        assert forbidden not in code, (name, forbidden)
    assert 'Landroid/service/notification/StatusBarNotification;->getId(' not in code
rules = ET.fromstring(analyze('resources', 'xml', '--file', 'res/xml/data_extraction_rules.xml'))
excluded = {'root', 'file', 'database', 'sharedpref', 'external', 'device_root', 'device_file', 'device_database', 'device_sharedpref'}
for kind in ['cloud-backup', 'device-transfer']:
    block = rules.find(kind)
    assert block is not None and not block.findall('include')
    assert {e.get('domain') for e in block.findall('exclude') if e.get('path') == '.'} == excluded
print('Probe APK isolation passed: no permissions, one protected listener, no content/network/storage call sites.')
