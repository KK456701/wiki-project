export function troubleshootingCaseSummary(markdown: string): string {
  const firstLine = markdown
    .split(/\r?\n/)
    .map((line) => line.replace(/^\s*(?:>|[-*+] |\d+\.\s*)/, '').trim())
    .find(Boolean);
  if (!firstLine) return '暂无问题描述';
  const plain = firstLine
    .replace(/[`*_#]/g, '')
    .replace(/^现象[:：]\s*/, '')
    .trim();
  const sentence = plain.match(/^.*?[。！？](?:\s|$)/)?.[0]?.trim() ?? plain;
  return sentence.length > 90 ? `${sentence.slice(0, 90)}…` : sentence;
}
