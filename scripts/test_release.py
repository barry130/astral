import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent.parent


class ReleaseTest(unittest.TestCase):
    def test_each_change_set_publishes_the_complete_tag_and_promotes_latest_last(self):
        bash = str(Path(shutil.which('git')).parent.parent / 'bin/bash.exe') if os.name == 'nt' else shutil.which('bash')
        with tempfile.TemporaryDirectory(prefix='astral-release-fixture-') as directory:
            directory = Path(directory)
            docker = directory / 'docker'
            docker.write_text('#!/usr/bin/env bash\nprintf "%s\\0" "$@" >> "$RELEASE_LOG"\nprintf "\\n" >> "$RELEASE_LOG"\n'
                              'if [[ "${FAIL_PUSH:-}" == "1" && "$1" == "push" && "$2" == *astral-blog:* ]]; then exit 1; fi\n', encoding='utf-8')
            docker.chmod(0o755)
            log = directory / 'calls'
            env = {**os.environ, 'PATH': str(directory) + os.pathsep + os.environ['PATH'],
                   'IMAGE_PREFIX': 'registry.test/fixture', 'BLOG_SITE_URL': 'https://blog.fixture.test',
                   'BLOG_ADMIN_URL': 'https://admin.fixture.test', 'RELEASE_LOG': str(log)}
            for change in ['frontend-only', 'backend-only', 'blog-only']:
                log.write_bytes(b'')
                subprocess.run([bash, 'scripts/build-release.sh'], cwd=ROOT, env={**env, 'RELEASE_TAG': change}, check=True, capture_output=True)
                calls = [line.decode().rstrip('\0').split('\0') for line in log.read_bytes().splitlines()]
                builds = [call for call in calls if call[0] == 'build']
                self.assertEqual(3, len(builds))
                self.assertIn('NEXT_PUBLIC_SITE_URL=https://blog.fixture.test', builds[2])
                self.assertIn('NEXT_PUBLIC_ADMIN_URL=https://admin.fixture.test', builds[2])
                pushed = [call[1] for call in calls if call[0] == 'push']
                self.assertEqual([f'registry.test/fixture/astral-{service}:{change}' for service in ['backend', 'frontend', 'blog']], pushed[:3])
                self.assertTrue(all(':latest' in image for image in pushed[3:]))
            log.write_bytes(b'')
            failure = subprocess.run([bash, 'scripts/build-release.sh'], cwd=ROOT,
                                     env={**env, 'RELEASE_TAG': 'failed', 'FAIL_PUSH': '1'}, capture_output=True)
            self.assertNotEqual(0, failure.returncode)
            self.assertNotIn(b':latest', log.read_bytes())


if __name__ == '__main__':
    unittest.main()
