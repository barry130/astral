const fs = require('node:fs');
const path = require('node:path');
const Module = require('node:module');
const ts = require('typescript');

const sourceRoot = path.resolve(__dirname, '../../src');

// Load actual public TypeScript exports in Node's realm. No AST extraction of
// private methods, duplicate domain logic, or React/state substitutes.
function createSourceLoader(overrides = {}) {
  const cache = new Map();
  function load(relativePath) {
    const filename = path.resolve(sourceRoot, relativePath);
    if (cache.has(filename)) return cache.get(filename).exports;
    const text = fs.readFileSync(filename, 'utf8');
    const compiled = ts.transpileModule(text, {
      compilerOptions: {
        target: ts.ScriptTarget.ES2022,
        module: ts.ModuleKind.CommonJS,
        jsx: ts.JsxEmit.ReactJSX,
        esModuleInterop: true,
      },
      fileName: filename,
    }).outputText;
    const loaded = new Module(filename, module);
    loaded.filename = filename;
    loaded.paths = Module._nodeModulePaths(path.dirname(filename));
    cache.set(filename, loaded);
    const originalRequire = loaded.require.bind(loaded);
    loaded.require = (specifier) => {
      if (Object.hasOwn(overrides, specifier)) return overrides[specifier];
      if (specifier.startsWith('@/') || specifier.startsWith('.')) {
        const base = specifier.startsWith('@/')
          ? specifier.slice(2)
          : path.relative(sourceRoot, path.resolve(path.dirname(filename), specifier));
        for (const suffix of ['.ts', '.tsx', '/index.ts', '/index.tsx']) {
          if (fs.existsSync(path.resolve(sourceRoot, `${base}${suffix}`))) {
            return load(`${base}${suffix}`);
          }
        }
      }
      return originalRequire(specifier);
    };
    loaded._compile(compiled, filename);
    return loaded.exports;
  }
  return load;
}

module.exports = { createSourceLoader };
