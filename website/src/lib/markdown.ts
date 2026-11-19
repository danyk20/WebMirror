import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { createMarkdownProcessor } from '@astrojs/markdown-remark';
import { REPO } from './site';

let processor: ReturnType<typeof createMarkdownProcessor> | undefined;

/**
 * Renders a Markdown file from the repo root (single source of truth) to HTML.
 * The first "# Title" line is dropped (the page supplies its own h1);
 * relative repo links become absolute GitHub links.
 */
export async function renderRepoMarkdown(file: string) {
  const path = resolve(process.cwd(), '..', file) // build runs from website/;
  let md = await readFile(path, 'utf8');
  md = md.replace(/^# .*\n/, '');
  md = md.replace(/\]\((?!https?:|#|mailto:)([^)]+)\)/g, `](${REPO}/blob/main/$1)`);
  md = md.replace(/^\[([^\]]+)\]: (?!https?:)(.+)$/gm, `[$1]: ${REPO}/blob/main/$2`);
  processor ??= createMarkdownProcessor({ syntaxHighlight: false });
  const { code } = await (await processor).render(md);
  // Heading ids must start with a letter (e.g. "0.2.1 - date" -> "v021---date").
  return code.replace(/(id|href)="(#?)(\d)/g, '$1="$2v$3');
}
