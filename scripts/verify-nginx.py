"""Run nginx -t on both shipped layouts using isolated paths and a temporary certificate."""
import argparse
from pathlib import Path
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parent.parent
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--nginx', required=True)
parser.add_argument('--openssl', required=True)
args = parser.parse_args()
with tempfile.TemporaryDirectory(prefix='astral-nginx-fixture-') as directory:
    directory = Path(directory)
    (directory / 'logs').mkdir()
    (directory / 'temp').mkdir()
    cert, key = directory / 'fixture.pem', directory / 'fixture.key'
    subprocess.run([args.openssl, 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1', '-subj',
                    '/CN=fixture.test', '-keyout', str(key), '-out', str(cert)], check=True, capture_output=True)
    snippets = directory / 'headers.conf'
    snippets.write_text((ROOT / 'deploy/nginx/security-headers.conf').read_text(encoding='utf-8'), encoding='utf-8')
    for variant in ['sites', 'legacy']:
        if variant == 'sites':
            content = (ROOT / 'deploy/nginx/00-http-level.conf').read_text(encoding='utf-8') + '\n' + (ROOT / 'deploy/nginx/10-astral-sites.conf').read_text(encoding='utf-8')
        else:
            content = (ROOT / 'deploy/nginx-reverse-proxy.example.conf').read_text(encoding='utf-8')
        content = content.replace('/etc/nginx/snippets/astral-security-headers.conf', snippets.as_posix())
        content = re.sub(r'(ssl_certificate\s+)[^;]+;', lambda match: match[1] + cert.as_posix() + ';', content)
        content = re.sub(r'(ssl_certificate_key\s+)[^;]+;', lambda match: match[1] + key.as_posix() + ';', content)
        content = content.replace('/var/log/nginx/', (directory / 'logs').as_posix() + '/')
        config = directory / (variant + '.conf')
        config.write_text('events {}\nhttp {\n' + content + '\n}\n', encoding='utf-8')
        subprocess.run([args.nginx, '-t', '-p', directory.as_posix() + '/', '-c', config.as_posix()], check=True)
