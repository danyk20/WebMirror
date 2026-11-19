# Site assets

- `screens/home.png|settings.png|help.png` – app screenshots (1080x2340), copies of `docs/images/*.png`; optimised by `astro:assets`.
- `og.svg` – source of `public/og.png` (1200x630 social preview, home screenshot embedded as a data URI).
- Components: `src/components/HeroMockup.astro` (animated hero, CSS only, still frame with `prefers-reduced-motion`) and `DeviceMockup.astro` (phone / car frame around a screenshot).

## Regenerate (run from the repository root)

```sh
# social preview (full colour; ~140 KB, avoids gradient banding)
rsvg-convert -w 1200 -h 630 website/src/assets/og.svg -o /tmp/og.png
magick /tmp/og.png -define png:compression-level=9 website/public/og.png

# icons from docs/images/icon*.svg
cp docs/images/icon-rounded.svg website/public/favicon.svg
rsvg-convert -w 180 -h 180 docs/images/icon.svg -o website/public/apple-touch-icon.png   # full-bleed, no transparency
magick website/public/apple-touch-icon.png -alpha off website/public/apple-touch-icon.png
rsvg-convert -w 192 -h 192 docs/images/icon-rounded.svg -o website/public/icon-192.png
rsvg-convert -w 512 -h 512 docs/images/icon-rounded.svg -o website/public/icon-512.png
for s in 16 32 48; do rsvg-convert -w $s -h $s docs/images/icon-rounded.svg -o /tmp/f$s.png; done
magick /tmp/f16.png /tmp/f32.png /tmp/f48.png website/public/favicon.ico
```
