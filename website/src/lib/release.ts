/**
 * Latest GitHub release, read once at build time. Never throws: on any failure the
 * fallback points to the releases page and no version is shown.
 */
export interface ReleaseInfo {
  version: string | null;
  tag: string | null;
  publishedAt: string | null;
  apkName: string | null;
  apkSize: number | null;
  /** Direct APK link, or the releases page when unknown. */
  url: string;
}

const FALLBACK: ReleaseInfo = {
  version: null,
  tag: null,
  publishedAt: null,
  apkName: null,
  apkSize: null,
  url: 'https://github.com/danyk20/WebMirror/releases/latest',
};

async function load(): Promise<ReleaseInfo> {
  try {
    const headers: Record<string, string> = {
      Accept: 'application/vnd.github+json',
      'User-Agent': 'webmirror-website-build',
    };
    const token = process.env.GITHUB_TOKEN;
    if (token) headers.Authorization = `Bearer ${token}`;
    const res = await fetch('https://api.github.com/repos/danyk20/WebMirror/releases/latest', {
      headers,
      signal: AbortSignal.timeout(10_000),
    });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const data: any = await res.json();
    const tag: string = String(data.tag_name ?? '');
    const apk = (data.assets ?? []).find((a: any) => typeof a?.name === 'string' && a.name.toLowerCase().endsWith('.apk'));
    if (!tag || !apk?.browser_download_url) throw new Error('no tag or apk');
    return {
      version: tag.replace(/^v/i, ''),
      tag,
      publishedAt: data.published_at ?? null,
      apkName: apk.name,
      apkSize: typeof apk.size === 'number' ? apk.size : null,
      url: apk.browser_download_url,
    };
  } catch (e) {
    console.warn(`[release] using fallback download link (${(e as Error).message})`);
    return FALLBACK;
  }
}

let cached: Promise<ReleaseInfo> | undefined;
export function getRelease(): Promise<ReleaseInfo> {
  return (cached ??= load());
}

export function formatSize(bytes: number | null): string | null {
  if (bytes == null) return null;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}
