#!/usr/bin/env python3
"""Verify SPEC-042's collector exists only in the protected, networkless test APK."""
import argparse
import os
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('apk', type=Path)
args = parser.parse_args()
if not os.environ.get('JAVA_HOME'):
    local_jbr = Path('/Applications/Android Studio.app/Contents/jbr/Contents/Home')
    if local_jbr.is_dir(): os.environ['JAVA_HOME'] = str(local_jbr)
sdk = Path(os.environ.get('ANDROID_HOME', '/Users/acccan/Library/Android/sdk'))
analyzers = [p / 'bin/apkanalyzer' for p in (sdk / 'cmdline-tools').iterdir()
             if (p / 'source.properties').is_file() and
             'Pkg.Revision=23.0' in (p / 'source.properties').read_text().replace(' ', '')]
assert analyzers, 'Existing Command-line Tools 23.0 required'
def analyze(*arguments):
    return subprocess.check_output([str(analyzers[0]), *arguments, str(args.apk)], text=True)
manifest = ET.fromstring(analyze('manifest', 'print'))
a = '{http://schemas.android.com/apk/res/android}'
assert manifest.get('package') == 'dev.epay.observer.sample.debug.test'
# AndroidX test runner contributes REORDER_TASKS; no network/storage permission.
assert {p.get(a+'name') for p in manifest.findall('uses-permission')} == {'android.permission.REORDER_TASKS'}
app = manifest.find('application')
assert app is not None and app.get(a + 'debuggable') == 'true'
for field in ['allowBackup','fullBackupContent','usesCleartextTraffic']:
    assert app.get(a + field) == 'false', field
service = [s for s in app.findall('service') if s.get(a+'name') == 'dev.epay.observer.sample.ReceiptChatListener']
assert len(service) == 1 and service[0].get(a+'permission') == 'android.permission.BIND_NOTIFICATION_LISTENER_SERVICE'
code = analyze('dex', 'code', '--class', 'dev.epay.observer.sample.ReceiptChatListener')
for forbidden in ['->getKey(', '->getId(', '->getTag(', '->getPostTime(', '->getSnoozedNotifications(',
                  'Landroid/util/Log;', 'Ljava/net/', 'Ljavax/net/', 'Lokhttp3/', 'Landroid/webkit/',
                  '->getExternalFilesDir(', '->getExternalStorageDirectory(',
                  'Ljava/lang/System;->out:', 'Ljava/lang/System;->err:']:
    assert forbidden not in code, forbidden
print('Comparison test APK: protected listener, no network/storage permission or notification identity reads.')
