import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import tempfile
import unittest

SCRIPT_DIR = Path(__file__).resolve().parent


class SourcePackageTest(unittest.TestCase):
    def test_both_entrypoints_package_worktree_source_without_local_secrets(self):
        with tempfile.TemporaryDirectory(prefix='astral-pack-fixture-') as directory:
            root = Path(directory) / 'source'
            scripts = root / 'scripts'
            scripts.mkdir(parents=True)
            for name in ('pack.py', 'pack.sh', 'pack.bat'):
                shutil.copyfile(SCRIPT_DIR / name, scripts / name)
            subprocess.run(['git', 'init', '-q', str(root)], check=True)
            safe = ['src/main.java', 'astral-blog/.env.example', 'cloudflare/storage-worker/wrangler.toml.example', 'new-source.ts']
            blocked = ['.env', 'telegram-server/.env', 'cloudflare/storage-worker/.dev.vars',
                       'telegram-server/cloudflared/tunnel.json', 'astral-blog/node_modules/pkg/index.js',
                       'astral-blog/.next/server.js', 'target/build.jar', 'deploy/private.pem', 'nested/.env.production']
            for path in safe + blocked:
                file = root / path
                file.parent.mkdir(parents=True, exist_ok=True)
                file.write_text('fixture sentinel', encoding='utf-8')
            subprocess.run(['git', '-C', str(root), 'add', '.'], check=True)
            (root / 'src/main.java').write_text('modified working source', encoding='utf-8')
            output = Path(directory) / 'output'
            bash = shutil.which('bash')
            if os.name == 'nt':
                bash = str(Path(shutil.which('git')).parent.parent / 'bin/bash.exe')
            commands = [[bash, 'scripts/pack.sh', '-o', str(output), '-n', 'unix']]
            if os.name == 'nt':
                commands.append(['cmd', '/c', 'scripts\\pack.bat', '-o', str(output), '-n', 'windows'])
            for command in commands:
                subprocess.run(command, cwd=root, check=True, capture_output=True, text=True,
                               env={**os.environ, 'PYTHON': sys.executable})
                with tarfile.open(output / (command[-1] + '.tar.gz')) as archive:
                    names = archive.getnames()
                    for path in safe:
                        self.assertIn('source/' + path, names)
                    for path in blocked:
                        self.assertNotIn('source/' + path, names)
                    self.assertEqual(b'modified working source', archive.extractfile('source/src/main.java').read())


if __name__ == '__main__':
    unittest.main()
