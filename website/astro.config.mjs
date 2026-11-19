import { defineConfig } from 'astro/config';
import sitemap from '@astrojs/sitemap';

// Change `site` (and drop `base`) when a custom domain is added.
export default defineConfig({
  site: 'https://danyk20.github.io',
  base: '/WebMirror',
  output: 'static',
  trailingSlash: 'always',
  integrations: [sitemap()],
  build: { format: 'directory' },
});
