#!/usr/bin/env node
'use strict';

// Cross-platform launcher (esbuild-style). The main @deemwarhq/mochallama
// package carries NO native payload — instead it declares the four
// per-platform packages as optionalDependencies. npm installs only the one
// matching the host os/cpu (the others are skipped by their os/cpu fields).
//
// At runtime we resolve that platform package, find its bundled jlink runtime
// image launcher, and spawn it — forwarding argv, stdio and the exit code.
//
// Bare invocation (`npx @deemwarhq/mochallama` with no args) drops straight
// into `chat` so users can start talking to a model immediately. Explicit
// subcommands (models, chat, --help, --model ...) pass through untouched.

const path = require('path');
const fs = require('fs');
const { spawnSync } = require('child_process');
const { createRequire } = require('module');

// Map Node's platform/arch onto our package platform key.
function platformKey() {
  const p = process.platform; // 'darwin' | 'linux' | 'win32' | ...
  const a = process.arch; // 'x64' | 'arm64' | ...
  if (p === 'darwin' && a === 'x64') return 'darwin-x64';
  if (p === 'darwin' && a === 'arm64') return 'darwin-arm64';
  if (p === 'linux' && a === 'x64') return 'linux-x64';
  if (p === 'win32' && a === 'x64') return 'win32-x64';
  return null;
}

function fail(msg) {
  console.error('[mochallama] ' + msg);
  process.exit(1);
}

const key = platformKey();
if (!key) {
  fail(
    `unsupported platform: ${process.platform}/${process.arch}\n` +
      'mochallama ships for: darwin-x64, darwin-arm64, linux-x64, win32-x64.'
  );
}

const pkgName = `@deemwarhq/mochallama-${key}`;

// Resolve the platform package's package.json so we can locate its image dir.
// createRequire(__filename) ensures resolution happens relative to THIS file's
// node_modules tree (works for global installs and npx temp installs alike).
const req = createRequire(__filename);
let pkgJsonPath;
try {
  pkgJsonPath = req.resolve(`${pkgName}/package.json`);
} catch (e) {
  fail(
    `platform package ${pkgName} is not installed.\n` +
      'It should have been pulled in automatically as an optional dependency.\n' +
      "If you used --no-optional or --omit=optional, reinstall without it, e.g.:\n" +
      `  npm i -g @deemwarhq/mochallama`
  );
}

const pkgRoot = path.dirname(pkgJsonPath);
const isWindows = process.platform === 'win32';
const launcher = path.join(
  pkgRoot,
  'image',
  'bin',
  isWindows ? 'mochallama.bat' : 'mochallama'
);

if (!fs.existsSync(launcher)) {
  fail(
    `bundled runtime image not found inside ${pkgName} at:\n  ${launcher}\n` +
      'The platform package appears to be incomplete (missing its jlink image).'
  );
}

// Default to `chat` when invoked with no args so `npx @deemwarhq/mochallama`
// starts a chat session immediately. Any explicit arg (models, chat, --help,
// --model, etc.) is forwarded verbatim.
const forwarded = process.argv.slice(2);
const args = forwarded.length === 0 ? ['chat'] : forwarded;

const result = spawnSync(launcher, args, { stdio: 'inherit' });

if (result.error) {
  fail('failed to launch: ' + result.error.message);
}

// Mirror the launcher's exit status (or 1 if it was killed by a signal).
process.exit(result.status === null ? 1 : result.status);
