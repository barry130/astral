#!/usr/bin/env python3
"""Package current source from Git's file list, without local credentials or build output."""
import argparse
from datetime import datetime
import os
from pathlib import Path, PurePosixPath
import subprocess
import tarfile


BLOCKED_DIRECTORIES = {
    '.git', '.idea', '.vscode', '.claude', '.qwen', '.codex', '.agents', '.next',
    'node_modules', 'target', 'dist', 'out', 'data', 'logs', 'release', '.wrangler',
    'cloudflared', 'secrets', 'credentials', '__pycache__', '.venv', 'venv',
}
BLOCKED_SUFFIXES = ('.log', '.local', '.tsbuildinfo', '.mv.db', '.trace.db', '.key',
                    '.pem', '.p12', '.pfx', '.jks', '.keystore', '.tar.gz', '.zip')


def source_file(name):
    path = PurePosixPath(name)
    if path.is_absolute() or '..' in path.parts or any(p.lower() in BLOCKED_DIRECTORIES for p in path.parts):
        return False
    base = path.name.lower()
    if base.endswith(('.example', '.template')):
        return True
    if base == '.env' or base.startswith(('.env.', '.dev.vars', 'credentials.', 'id_rsa', 'id_ed25519')):
        return False
    return not (base.endswith(BLOCKED_SUFFIXES) or '.local.' in base
                or base in {'application-local.yml', 'application-local.yaml', 'wrangler.toml'})


def package(root, output, name):
    root, output = root.resolve(), output.resolve()
    if output == root or root in output.parents:
        raise ValueError('Output directory must be outside the source tree')
    if not name or Path(name).name != name or '/' in name or '\\' in name:
        raise ValueError('Archive name must be a single filename')
    raw = subprocess.check_output(['git', '-C', str(root), 'ls-files', '-z', '--cached', '--others', '--exclude-standard'])
    names = sorted({os.fsdecode(p) for p in raw.split(b'\0') if p})
    output.mkdir(parents=True, exist_ok=True)
    archive = output / (name + '.tar.gz')
    with archive.open('xb') as stream, tarfile.open(fileobj=stream, mode='w:gz') as bundle:
        for name in names:
            if not source_file(name):
                continue
            source = root / name
            if not source.is_file() or source.is_symlink() or root not in source.resolve().parents:
                continue
            bundle.add(source, arcname=root.name + '/' + name, recursive=False)
    return archive


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('destination', nargs='?')
    parser.add_argument('-o', '--out')
    parser.add_argument('-n', '--name', default='astral-' + datetime.now().strftime('%Y%m%d-%H%M%S-%f'))
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    print(package(root, Path(args.out or args.destination or root.parent), args.name))


if __name__ == '__main__':
    main()
