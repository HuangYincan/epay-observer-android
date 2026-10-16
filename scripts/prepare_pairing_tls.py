#!/usr/bin/env python3
"""Create a two-day, loopback-only CA/server identity in a private task directory.

Uses the existing openssl executable. Keys never enter the repository or APK.
"""
import argparse
import hashlib
import os
from pathlib import Path
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument('directory', type=Path)
args = parser.parse_args()
os.umask(0o077)
args.directory.mkdir(parents=True, exist_ok=False)

def openssl(*arguments):
    subprocess.run(['openssl', *arguments], cwd=args.directory, check=True, capture_output=True)

openssl('req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '2', '-sha256',
        '-subj', '/CN=Observer isolated fixture CA', '-keyout', 'ca.key', '-out', 'ca.pem',
        '-addext', 'basicConstraints=critical,CA:TRUE', '-addext', 'keyUsage=critical,keyCertSign,cRLSign')
openssl('req', '-newkey', 'rsa:2048', '-nodes', '-sha256', '-subj', '/CN=localhost',
        '-keyout', 'server.key', '-out', 'server.csr')
(args.directory / 'server.ext').write_text(
    'subjectAltName=DNS:localhost,IP:127.0.0.1\nbasicConstraints=critical,CA:FALSE\n'
    'keyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\n')
openssl('x509', '-req', '-in', 'server.csr', '-CA', 'ca.pem', '-CAkey', 'ca.key',
        '-CAcreateserial', '-days', '2', '-sha256', '-extfile', 'server.ext', '-out', 'server.pem')
print('Fixture CA SHA-256:', hashlib.sha256((args.directory / 'ca.pem').read_bytes()).hexdigest())
