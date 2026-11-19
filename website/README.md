# WebMirror website

Static site (Astro) for <https://danyk20.github.io/WebMirror/>. No UI framework, no client JavaScript, no analytics, no third-party requests.

## Run

```bash
cd website
nvm use          # Node 24 (see .nvmrc)
npm ci
npm run dev      # http://localhost:4321/WebMirror/
npm run check    # astro check (types)
npm run build    # static output in dist/
npm run preview
```

## Where content comes from

- `../PRIVACY.md` and `../CHANGELOG.md` are rendered at build time into `/privacy/` and `/changelog/` (see `src/lib/markdown.ts`). Edit the Markdown files in the repo root, not the pages.
- The Download button reads the latest GitHub release at build time (`src/lib/release.ts`; uses `GITHUB_TOKEN` if set). If the request fails, it falls back to the releases page and shows no version. Rebuild after each release.
- FAQ text: `src/data/faq.ts` (used by the FAQ page, the home teaser and the FAQPage JSON-LD).
- Setup, secure address and troubleshooting pages are adapted from the in-app Help (`HelpPage.kt`) and `docs/HTTPS-CERTIFICATE.md`. Keep them in sync when those change.
- Design tokens: `src/styles/tokens.css`. Visual assets: `src/assets/` (see its README).

## Base path

`site` and `base` are set in `astro.config.mjs`. For a custom domain, set `site` to the domain and remove `base`. Use `href()` / `abs()` from `src/lib/site.ts` for every internal link.
