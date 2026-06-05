import { defineConfig } from 'vitepress'

export default defineConfig({
  title: 'mochallama',
  description: 'Local LLM for Spring Boot via Project Panama FFM and llama.cpp',
  base: '/mochallama/',
  cleanUrls: true,
  lastUpdated: true,
  // Keep internal/working docs in the repo but off the public site.
  srcExclude: [
    '**/promotion/**',
    '**/research/**',
    'specs/00-overview.md',
    'specs/02-bridge-abi.md',
    'specs/03-decisions.md',
    'specs/04-deferred.md',
    'specs/05-release-and-publish.md',
    'specs/06-examples.md',
    'specs/07-example-verification-report.md',
    'specs/08-product-brief.md',
    'specs/PUBLISHING.md',
    'specs/HOW-TO-PUBLISH-AND-TEST.md',
    'specs/inprogress.md',
  ],
  themeConfig: {
    nav: [
      { text: 'Why',        link: '/why' },
      { text: 'Quickstart', link: '/quickstart' },
      { text: 'Examples',   link: '/examples/' },
      { text: 'Compare',    link: '/compare' },
      { text: 'Reference',  link: '/specs/01-architecture' },
      { text: 'GitHub',     link: 'https://github.com/deemwar-products/mochallama' },
    ],
    sidebar: {
      // SIDEBAR A — the adoption funnel: top-level Start pages + Examples.
      // Keyed on both '/examples/' and '/' so a reader in the funnel sees the
      // whole path. More specific prefixes must come first.
      '/examples/': [
        {
          text: 'Start',
          items: [
            { text: 'Quickstart',     link: '/quickstart' },
            { text: 'Why mochallama', link: '/why' },
            { text: 'Compare',        link: '/compare' },
          ],
        },
        {
          text: 'Examples',
          items: [
            { text: 'Overview',            link: '/examples/' },
            // CLI first — the zero-install hook.
            { text: 'CLI',                 link: '/examples/03-cli' },
            { text: 'curl',                link: '/examples/00-curl' },
            { text: 'OpenAI SDK',          link: '/examples/01-openai-sdk' },
            // Spring AI is folded in as a note within the Spring Boot example.
            { text: 'Spring Boot',         link: '/examples/02-spring-boot' },
            { text: 'Tools & Streaming',   link: '/examples/04-tools-and-streaming' },
          ],
        },
      ],
      '/specs/': [
        {
          text: 'Reference',
          items: [
            { text: 'Architecture',      link: '/specs/01-architecture' },
            { text: 'Streaming & Tools', link: '/specs/streaming-and-tools' },
            { text: 'Tool Calling',      link: '/specs/tool-calling-support' },
            { text: 'Models & profiles', link: '/specs/models' },
            { text: 'Metrics',           link: '/specs/observability' },
          ],
        },
      ],
      // Catch-all for the top-level Start pages (/why, /quickstart, /compare).
      // Keep this LAST so '/examples/' and '/specs/' win their prefixes.
      '/': [
        {
          text: 'Start',
          items: [
            { text: 'Quickstart',     link: '/quickstart' },
            { text: 'Why mochallama', link: '/why' },
            { text: 'Compare',        link: '/compare' },
          ],
        },
        {
          text: 'Examples',
          items: [
            { text: 'Overview',            link: '/examples/' },
            { text: 'CLI',                 link: '/examples/03-cli' },
            { text: 'curl',                link: '/examples/00-curl' },
            { text: 'OpenAI SDK',          link: '/examples/01-openai-sdk' },
            { text: 'Spring Boot',         link: '/examples/02-spring-boot' },
            { text: 'Tools & Streaming',   link: '/examples/04-tools-and-streaming' },
          ],
        },
      ],
    },
    socialLinks: [
      { icon: 'github', link: 'https://github.com/deemwar-products/mochallama' },
    ],
    footer: {
      message: 'Released under the MIT License.',
      copyright: 'Built by <a href="https://deemwar.com" target="_blank" rel="noreferrer">deemwar.com</a> · © deemwar',
    },
    search: {
      provider: 'local',
    },
  },
})
