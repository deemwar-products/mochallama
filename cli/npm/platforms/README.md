# mochallama per-platform npm packages

These are the four platform-specific companion packages for
`@deemwarhq/mochallama`. Each one bundles a self-contained `jlink` runtime
image (a trimmed JRE + the native llama.cpp libraries for that platform) under
its `image/` directory and is published to npm as
`@deemwarhq/mochallama-<platkey>`:

| dir            | npm name                            | os      | cpu    | native key       |
|----------------|-------------------------------------|---------|--------|------------------|
| `darwin-x64`   | `@deemwarhq/mochallama-darwin-x64`  | darwin  | x64    | `darwin-x86_64`  |
| `darwin-arm64` | `@deemwarhq/mochallama-darwin-arm64`| darwin  | arm64  | `darwin-aarch64` |
| `linux-x64`    | `@deemwarhq/mochallama-linux-x64`   | linux   | x64    | `linux-x86_64`   |
| `win32-x64`    | `@deemwarhq/mochallama-win32-x64`   | win32   | x64    | `windows-x86_64` |

## How they're used

The main `@deemwarhq/mochallama` package lists all four as
`optionalDependencies`. Thanks to each package's `os`/`cpu` fields, `npm`
installs **only** the one matching the host — the rest are skipped. At runtime
`bin/mochallama.js` (in the main package) resolves the matching package via
`createRequire(__filename).resolve(...)` and spawns its
`image/bin/mochallama` (`image\bin\mochallama.bat` on Windows).

## The `image/` directory

`image/` is **not** committed (only an empty `.gitkeep` placeholder is). It is
populated by CI from that platform's `:cli:jlink` output before `npm pack` /
`npm publish`:

```
cp -R cli/build/image/.  cli/npm/platforms/<platkey>/image/
cd cli/npm/platforms/<platkey> && npm pack
```

Locally on this Intel Mac the only platform that can be fully built and tested
is `darwin-x64`. `task cli:npm:pack` wires that path up automatically. The
Linux, Apple-silicon and Windows images are produced by the GitHub Actions
release matrix (`.github/workflows/release.yml`).
