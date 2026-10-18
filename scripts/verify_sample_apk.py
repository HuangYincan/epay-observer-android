#!/usr/bin/env python3
"""SPEC-031: inspect the private sampling artifact, independently of its source manifest."""
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
assert manifest.attrib['package'] == 'dev.epay.observer.sample.debug'
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
assert len(activities) == 1 and activities[0].get(android + 'name') == 'dev.epay.observer.sample.SampleActivity'
services = app.findall('service')
assert len(services) == 1
assert services[0].get(android + 'name') == 'dev.epay.observer.sample.AlipayListener'
assert services[0].get(android + 'permission') == 'android.permission.BIND_NOTIFICATION_LISTENER_SERVICE'
assert services[0].get(android + 'exported') == 'true'
assert services[0].find('intent-filter/action').get(android + 'name') == 'android.service.notification.NotificationListenerService'

# Include generated lambdas/nested classes as well as authored top-level classes.
# Unused stdlib helpers are not our content collector's call sites.
classes = [line.split('\t')[-1] for line in analyze('dex', 'packages', '--defined-only').splitlines()
           if line.startswith('C d ') and line.split('\t')[-1].startswith('dev.epay.observer.sample.')]
assert 'dev.epay.observer.sample.AlipayListener' in classes
for name in classes:
    code = analyze('dex', 'code', '--class', name)
    for forbidden in ['->getActiveNotifications(', '->getSnoozedNotifications(',
                      '->getKey(', '->getPostTime(', '->getTag(', '->getExtras(', '->getChannelId(',
                      'Landroid/util/Log;', 'Ljava/net/', 'Ljavax/net/', '->getExternalFilesDir(',
                      '->getExternalStorageDirectory(', 'Ljava/lang/System;->out:', 'Ljava/lang/System;->err:',
                      'Lkotlin/io/ConsoleKt;', 'Landroid/webkit/', 'Lokhttp3/',
                      'Landroid/database/', '->getSharedPreferences(', '->getIntent(', 'Ljava/lang/reflect/']:
        assert forbidden not in code, (name, forbidden)
    # A launchable status screen has no path to open the content gate. Only androidTest can do so.
    assert 'Ldev/epay/observer/sample/SampleGate;->start(' not in code, name
    for forbidden in ['->getFilesDir(', '->getCacheDir(', 'Ljava/io/FileWriter;', 'Ljava/io/PrintWriter;']:
        assert forbidden not in code, (name, forbidden)
    assert 'Landroid/service/notification/StatusBarNotification;->getId(' not in code
    for method in ['getCharSequence(', '->getNotification(']:
        if method in code:
            assert name.startswith('dev.epay.observer.sample.AlipayListener'), name
    if 'Ljava/io/FileOutputStream;' in code:
        assert name.startswith('dev.epay.observer.sample.SampleRuntime'), name
    for forbidden in ['Ljava/io/FileInputStream;', '->getContentResolver(', '->getAll(', '->keySet(', '->getParcelable(']:
        assert forbidden not in code, (name, forbidden)
rules = ET.fromstring(analyze('resources', 'xml', '--file', 'res/xml/data_extraction_rules.xml'))
excluded = {'root', 'file', 'database', 'sharedpref', 'external', 'device_root', 'device_file', 'device_database', 'device_sharedpref'}
for kind in ['cloud-backup', 'device-transfer']:
    block = rules.find(kind)
    assert block is not None and not block.findall('include')
    assert {e.get('domain') for e in block.findall('exclude') if e.get('path') == '.'} == excluded
print('Sample APK isolation passed: no permissions/network, protected listener, bounded private content path.')
