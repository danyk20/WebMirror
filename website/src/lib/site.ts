export const SITE = 'https://danyk20.github.io';
export const REPO = 'https://github.com/danyk20/WebMirror';
export const AUTHOR = 'https://github.com/danyk20';
export const ISSUES = `${REPO}/issues/new/choose`;
export const RELEASES = `${REPO}/releases`;
export const SITE_NAME = 'WebMirror';

/** Site-relative path (with the base) for use in href/src. `p` starts with "/". */
export function href(p: string): string {
  const base = import.meta.env.BASE_URL.replace(/\/+$/, '');
  return `${base}${p}`;
}

/** Absolute URL for canonical, Open Graph and JSON-LD. */
export function abs(p: string): string {
  return `${SITE}${href(p)}`;
}
