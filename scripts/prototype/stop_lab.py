#!/usr/bin/env python3
"""Stop only labeled task containers; --remove also removes their disposable data."""
import argparse
import subprocess
from pathlib import Path
from lab_state import load_runtime, require_container
p = argparse.ArgumentParser()
p.add_argument('directory', type=Path)
p.add_argument('--remove', action='store_true', help='remove this lab containers, their anonymous volumes and labeled network; keep private artifacts')
a = p.parse_args()
meta = load_runtime(a.directory)
names = [name for suffix in ['gateway', 'newapi', 'redis', 'postgres', 'runtime'] if (name := require_container(meta, suffix, missing_ok=True))]
if names:
    subprocess.run(['docker', 'stop', *names], check=True)
if a.remove:
    if names:
        subprocess.run(['docker', 'rm', '-v', *names], check=True)
    networks = subprocess.check_output(['docker', 'network', 'ls', '--format', '{{.Name}}'], text=True).splitlines()
    if meta['prefix'] not in networks:
        print('No task network remains.')
        raise SystemExit(0)
    owner = subprocess.check_output(['docker', 'network', 'inspect', '--format', '{{index .Labels "epay-observer.lab"}}', meta['prefix']], text=True).strip()
    if owner != meta['prefix']:
        raise ValueError('network ownership label mismatch')
    subprocess.run(['docker', 'network', 'rm', meta['prefix']], check=True)
print('Task resources stopped; private evidence and keys remain in the supplied directory.')
