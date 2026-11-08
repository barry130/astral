'use strict';
const fs = require('node:fs');
const path = require('node:path');
const { createHash } = require('node:crypto');
const inputs = [
  'package.json',
  'pnpm-lock.yaml',
  'next.config.ts',
  'tsconfig.json',
  'postcss.config.mjs',
];
function authoredManifest(root) {
  const manifest = {};
  const hash = (file) => {
    manifest[path.relative(root, file).replaceAll('\\', '/')] = createHash('sha256')
      .update(fs.readFileSync(file))
      .digest('hex');
  };
  function tree(directory) {
    if (!fs.existsSync(directory)) return;
    for (const entry of fs
      .readdirSync(directory, { withFileTypes: true })
      .sort((a, b) => a.name.localeCompare(b.name))) {
      const file = path.join(directory, entry.name);
      if (entry.isDirectory()) tree(file);
      else if (entry.isFile()) hash(file);
      else throw new Error(`Unsupported authored symlink/special file: ${file}`);
    }
  }
  tree(path.join(root, 'src'));
  tree(path.join(root, 'public'));
  for (const name of inputs) hash(path.join(root, name));
  return manifest;
}
module.exports = { authoredManifest, inputs };
