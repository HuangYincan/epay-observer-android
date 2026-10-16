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
names = [require_container(meta, suffix) for suffix in ['gateway', 'newapi', 'redis', 'postgres', 'runtime']]
subprocess.run(['docker', 'stop', *names], check=True)
if a.remove:
    subprocess.run(['docker', 'rm', '-v', *names], check=True)
    owner = subprocess.check_output(['docker', 'network', 'inspect', '--format', '{{index .Labels "epay-observer.lab"}}', meta['prefix']], text=True).strip()
    if owner != meta['prefix']:
        raise ValueError('network ownership label mismatch')
    subprocess.run(['docker', 'network', 'rm', meta['prefix']], check=True)
print('Task resources stopped; private evidence and keys remain in the supplied directory.')
