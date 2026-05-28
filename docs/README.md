# mochallama docs

VitePress site that publishes the design specs, research, and promotion notes
under `docs/specs/`, `docs/research/`, and `docs/promotion/`.

## Dev

```sh
cd docs
npm install
npm run dev          # serves at http://localhost:5173
```

## Build

```sh
cd docs
npm run build        # outputs to docs/.vitepress/dist
npm run preview      # serves the built site locally
```

## Layout

- `index.md` — landing page (hero + feature cards + quickstart).
- `.vitepress/config.mjs` — site config (title, nav, sidebar, base path).
- `specs/`, `research/`, `promotion/` — content directories. Owned by other
  agents / authors; do not edit them from here.

The site is configured with `base: '/mochallama/'` so it can be published to
GitHub Pages at `github.com/deemwar-products/mochallama`. Change the `base` in
`.vitepress/config.mjs` if the repo is renamed or the deploy target moves.
