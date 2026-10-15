"""Offline M0 evidence; validates committed vectors, never sends requests."""
import base64
import hashlib
import hmac
import json
from pathlib import Path
import subprocess
import sys

docs = Path(__file__).resolve().parents[2]
vectors = json.loads((docs / 'testing/protocol-v1/vectors.json').read_text())['vectors']
lines = []
for v in vectors:
    key = bytes.fromhex(v['key_hex'])
    body = base64.b64decode(v['body_base64'], validate=True)
    digest = hashlib.sha256(body).hexdigest()
    canonical = '\n'.join(['epay-observer-v1', v['method'], v['path'], v['key_id'],
                           v['sent_at_ms'], v['request_id'], digest]).encode('utf-8')
    assert digest == v['body_sha256']
    assert canonical == base64.b64decode(v['signing_input_base64'])
    sign = lambda data: hmac.new(key, data, hashlib.sha256).hexdigest()
    assert hmac.compare_digest(sign(canonical), v['signature_hex'])
    for changed in [canonical + b'\n', canonical.replace(b'\n', b'\r\n'),
                    canonical.replace(b'/events\n', b'/events/\n'),
                    canonical.replace(digest.encode(), hashlib.sha256(body + b' ').hexdigest().encode())]:
        assert not hmac.compare_digest(sign(changed), v['signature_hex'])
    lines.append('\t'.join([v['id'], v['key_hex'], v['body_base64'], v['method'], v['path'],
                            v['key_id'], v['sent_at_ms'], v['request_id'], v['body_sha256'],
                            v['signing_input_base64'], v['signature_hex']]))
assert vectors[0]['body_base64'] == vectors[2]['body_base64']
assert vectors[0]['signature_hex'] != vectors[2]['signature_hex']
assert '合成流水' in base64.b64decode(vectors[1]['body_base64']).decode()
print('Python: 3/3 vectors; 12 tamper/format checks; retry body stability verified', flush=True)
java = sys.argv[1] if len(sys.argv) > 1 else '/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/java'
subprocess.run([java, str(Path(__file__).with_name('VerifyVectors.java'))],
               input='\n'.join(lines)+'\n', text=True, check=True)
print('HTTP statuses, replay database, authorization, Android/Go endpoints: NOT RUN')
