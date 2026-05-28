import { defineConfig } from 'vitepress'

export default defineConfig({
  title: 'mochallama',
  description: 'Local LLM for Spring Boot via Project Panama FFM and llama.cpp',
  base: '/mochallama/',
  cleanUrls: true,
  lastUpdated: true,
  srcExclude: ['**/promotion/**'],
  themeConfig: {
    nav: [
      { text: 'Examples', link: '/examples/' },
      { text: 'Specs',    link: '/specs/00-overview' },
      { text: 'Models',   link: '/specs/models' },
      { text: 'Metrics',  link: '/specs/observability' },
      { text: 'Research', link: '/research/00-landscape' },
      { text: 'GitHub',   link: 'https://github.com/deemwar-products/mochallama' },
    ],
    sidebar: {
      '/examples/': [
        {
          text: 'Examples',
          items: [
            { text: 'Overview',            link: '/examples/' },
            { text: 'curl',                link: '/examples/00-curl' },
            { text: 'OpenAI SDK (Python)', link: '/examples/01-openai-sdk' },
            { text: 'Spring Boot',         link: '/examples/02-spring-boot' },
            { text: 'CLI',                 link: '/examples/03-cli' },
            { text: 'Tools & Streaming',   link: '/examples/04-tools-and-streaming' },
          ],
        },
      ],
      '/specs/': [
        {
          text: 'Specs',
          items: [
            { text: 'Overview',        link: '/specs/00-overview' },
            { text: 'Architecture',    link: '/specs/01-architecture' },
            { text: 'Bridge ABI',      link: '/specs/02-bridge-abi' },
            { text: 'Design Decisions', link: '/specs/03-decisions' },
            { text: 'Deferred Work',   link: '/specs/04-deferred' },
            { text: 'Model Profiles',  link: '/specs/models' },
            { text: 'Streaming & Tools', link: '/specs/streaming-and-tools' },
            { text: 'Metrics',         link: '/specs/observability' },
          ],
        },
      ],
      '/research/': [
        {
          text: 'Research',
          items: [
            { text: 'Landscape',       link: '/research/00-landscape' },
            { text: 'Positioning',     link: '/research/01-positioning' },
            { text: 'Related Reading', link: '/research/02-related-reading' },
          ],
        },
      ],
    },
    socialLinks: [
      { icon: 'github', link: 'https://github.com/deemwar-products/mochallama' },
    ],
    footer: {
      message: 'Released under the project license.',
      copyright: 'Copyright (c) mochallama contributors',
    },
    search: {
      provider: 'local',
    },
  },
})
