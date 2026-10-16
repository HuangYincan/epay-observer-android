"""Validate task metadata before operating on named Docker resources."""
import json
from pathlib import Path
import re
import subprocess


def load_runtime(directory):
    directory = Path(directory).resolve()
    if not str(directory).startswith(('/tmp/epay-observer-', '/private/tmp/epay-observer-')):
        raise ValueError('private task directory required')
    metadata = json.loads((directory / 'runtime.json').read_text())
    if not re.fullmatch(r'epay-observer-[a-z0-9-]{1,30}', metadata['prefix']):
        raise ValueError('invalid dedicated container prefix')
    return metadata


def require_container(metadata, suffix):
    if suffix not in {'runtime', 'postgres', 'redis', 'gateway', 'newapi'}:
        raise ValueError('unknown task service')
    name = metadata['prefix'] + '-' + suffix
    result = subprocess.check_output(['docker', 'inspect', '--format', '{{index .Config.Labels "epay-observer.lab"}}', name], text=True).strip()
    if result != metadata['prefix']:
        raise ValueError('container lacks this lab ownership label: ' + name)
    return name
