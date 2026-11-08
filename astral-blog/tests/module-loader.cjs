'use strict';

// Compile the actual authored modules in Node's realm. Only the external Next
// request-cookie boundary is replaced; React, ReactDOM and NextResponse are real.
const fs = require('node:fs');
const path = require('node:path');
const Module = require('node:module');
const { AsyncLocalStorage } = require('node:async_hooks');
const ts = require('typescript');

const root = path.resolve(__dirname, '..');
const requestCookies = new AsyncLocalStorage();
const resolve = Module._resolveFilename;
const load = Module._load;

Module._resolveFilename = function (name, parent, ...rest) {
  return resolve.call(
    this,
    name.startsWith('@/') ? path.join(root, 'src', name.slice(2)) : name,
    parent,
    ...rest,
  );
};
Module._load = function (name, parent, ...rest) {
  if (name === 'next/headers') {
    return {
      cookies: async () => {
        const values = requestCookies.getStore() ?? {};
        return {
          get: (key) =>
            Object.hasOwn(values, key) ? { name: key, value: values[key] } : undefined,
          getAll: () => Object.entries(values).map(([name, value]) => ({ name, value })),
        };
      },
    };
  }
  return load.call(this, name, parent, ...rest);
};
for (const extension of ['.ts', '.tsx']) {
  const previous = Module._extensions[extension];
  Module._extensions[extension] = (module, filename) => {
    if (!filename.startsWith(path.join(root, 'src') + path.sep)) {
      if (previous) return previous(module, filename);
      throw new Error(`Refusing to compile non-blog source: ${filename}`);
    }
    const output = ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
      fileName: filename,
      compilerOptions: {
        target: ts.ScriptTarget.ES2022,
        module: ts.ModuleKind.CommonJS,
        jsx: ts.JsxEmit.ReactJSX,
        esModuleInterop: true,
      },
      reportDiagnostics: true,
    });
    const errors = (output.diagnostics ?? []).filter(
      (item) => item.category === ts.DiagnosticCategory.Error,
    );
    if (errors.length)
      throw new Error(
        ts.formatDiagnosticsWithColorAndContext(errors, {
          getCurrentDirectory: () => root,
          getCanonicalFileName: (name) => name,
          getNewLine: () => '\n',
        }),
      );
    module._compile(output.outputText, filename);
  };
}

module.exports = {
  root,
  runWithCookies: (values, callback) => requestCookies.run(values, callback),
  requireSource: (name) => require(path.join(root, 'src', name)),
  json: (data, status = 200, headers = {}) =>
    new Response(
      JSON.stringify(
        data && typeof data === 'object' && !Array.isArray(data)
          ? { msg: 'fixture', data: null, timestamp: Date.now(), ...data }
          : data,
      ),
      {
        status,
        headers: { 'content-type': 'application/json', ...headers },
      },
    ),
  forbidNetwork: () => {
    global.fetch = async () => {
      throw new Error('Unexpected network request in offline regression');
    };
  },
};
