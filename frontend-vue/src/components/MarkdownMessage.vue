<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{ content: string }>()

type InlineKind = 'text' | 'strong' | 'code'
interface InlineToken { kind: InlineKind; text: string }
interface MarkdownBlock {
  type: 'heading' | 'quote' | 'table' | 'list' | 'code' | 'paragraph' | 'divider'
  level?: number
  tokens?: InlineToken[]
  lines?: InlineToken[][]
  headers?: InlineToken[][]
  rows?: InlineToken[][][]
  items?: InlineToken[][]
  ordered?: boolean
  language?: string
  text?: string
}

const blocks = computed(() => parseMarkdown(props.content || ''))

/**
 * 只解析 Agent 回答需要的 Markdown 子集，并始终通过 Vue 文本插值输出。
 * 不使用 v-html，因此模型即使返回 HTML 或脚本也只会作为普通文本显示。
 */
function parseMarkdown(source: string): MarkdownBlock[] {
  const lines = source.replace(/\r\n/g, '\n').split('\n')
  const result: MarkdownBlock[] = []
  let index = 0
  while (index < lines.length) {
    const line = lines[index]
    if (!line.trim()) {
      index += 1
      continue
    }
    if (line.trim().startsWith('```')) {
      const language = line.trim().slice(3).trim()
      const code: string[] = []
      index += 1
      while (index < lines.length && !lines[index].trim().startsWith('```')) {
        code.push(lines[index])
        index += 1
      }
      if (index < lines.length) index += 1
      result.push({ type: 'code', language, text: code.join('\n') })
      continue
    }
    const heading = /^(#{1,3})\s+(.+)$/.exec(line.trim())
    if (heading) {
      result.push({
        type: 'heading',
        level: heading[1].length,
        tokens: parseInline(heading[2]),
      })
      index += 1
      continue
    }
    if (/^---+$/.test(line.trim())) {
      result.push({ type: 'divider' })
      index += 1
      continue
    }
    if (line.trimStart().startsWith('>')) {
      const quote: InlineToken[][] = []
      while (index < lines.length && lines[index].trimStart().startsWith('>')) {
        quote.push(parseInline(lines[index].trimStart().replace(/^>\s?/, '')))
        index += 1
      }
      result.push({ type: 'quote', lines: quote })
      continue
    }
    if (isTableStart(lines, index)) {
      const headers = splitTableRow(lines[index]).map(parseInline)
      const rows: InlineToken[][][] = []
      index += 2
      while (index < lines.length && lines[index].includes('|') && lines[index].trim()) {
        rows.push(splitTableRow(lines[index]).map(parseInline))
        index += 1
      }
      result.push({ type: 'table', headers, rows })
      continue
    }
    const listMatch = /^(\s*)([-*+]|\d+\.)\s+(.+)$/.exec(line)
    if (listMatch) {
      const ordered = /\d+\./.test(listMatch[2])
      const items: InlineToken[][] = []
      while (index < lines.length) {
        const item = /^(\s*)([-*+]|\d+\.)\s+(.+)$/.exec(lines[index])
        if (!item || /\d+\./.test(item[2]) !== ordered) break
        items.push(parseInline(item[3]))
        index += 1
      }
      result.push({ type: 'list', ordered, items })
      continue
    }
    const paragraph: string[] = [line.trim()]
    index += 1
    while (index < lines.length && lines[index].trim() && !startsNewBlock(lines, index)) {
      paragraph.push(lines[index].trim())
      index += 1
    }
    result.push({ type: 'paragraph', tokens: parseInline(paragraph.join(' ')) })
  }
  return result
}

function startsNewBlock(lines: string[], index: number): boolean {
  const value = lines[index].trim()
  return value.startsWith('```')
    || /^#{1,3}\s+/.test(value)
    || value.startsWith('>')
    || /^---+$/.test(value)
    || /^([-*+]|\d+\.)\s+/.test(value)
    || isTableStart(lines, index)
}

function isTableStart(lines: string[], index: number): boolean {
  if (index + 1 >= lines.length || !lines[index].includes('|')) return false
  const separators = splitTableRow(lines[index + 1])
  return separators.length > 0 && separators.every(cell => /^:?-{3,}:?$/.test(cell))
}

function splitTableRow(line: string): string[] {
  return line.trim().replace(/^\|/, '').replace(/\|$/, '').split('|').map(cell => cell.trim())
}

function parseInline(value: string): InlineToken[] {
  const tokens: InlineToken[] = []
  const pattern = /(\*\*[^*]+\*\*|`[^`]+`)/g
  let cursor = 0
  for (const match of value.matchAll(pattern)) {
    const start = match.index || 0
    if (start > cursor) tokens.push({ kind: 'text', text: value.slice(cursor, start) })
    const raw = match[0]
    tokens.push(raw.startsWith('**')
      ? { kind: 'strong', text: raw.slice(2, -2) }
      : { kind: 'code', text: raw.slice(1, -1) })
    cursor = start + raw.length
  }
  if (cursor < value.length) tokens.push({ kind: 'text', text: value.slice(cursor) })
  return tokens
}
</script>

<template>
  <div class="message-content answer-markdown">
    <template v-for="(block, blockIndex) in blocks" :key="blockIndex">
      <component :is="`h${block.level}`" v-if="block.type === 'heading'">
        <template v-for="(token, tokenIndex) in block.tokens" :key="tokenIndex">
          <strong v-if="token.kind === 'strong'">{{ token.text }}</strong>
          <code v-else-if="token.kind === 'code'">{{ token.text }}</code>
          <template v-else>{{ token.text }}</template>
        </template>
      </component>

      <blockquote v-else-if="block.type === 'quote'">
        <p v-for="(line, lineIndex) in block.lines" :key="lineIndex">
          <template v-for="(token, tokenIndex) in line" :key="tokenIndex">
            <strong v-if="token.kind === 'strong'">{{ token.text }}</strong>
            <code v-else-if="token.kind === 'code'">{{ token.text }}</code>
            <template v-else>{{ token.text }}</template>
          </template>
        </p>
      </blockquote>

      <div v-else-if="block.type === 'table'" class="answer-table-shell">
        <table>
          <thead><tr>
            <th v-for="(cell, cellIndex) in block.headers" :key="cellIndex">
              <template v-for="(token, tokenIndex) in cell" :key="tokenIndex">
                <strong v-if="token.kind === 'strong'">{{ token.text }}</strong>
                <code v-else-if="token.kind === 'code'">{{ token.text }}</code>
                <template v-else>{{ token.text }}</template>
              </template>
            </th>
          </tr></thead>
          <tbody>
            <tr v-for="(row, rowIndex) in block.rows" :key="rowIndex">
              <td v-for="(cell, cellIndex) in row" :key="cellIndex">
                <template v-for="(token, tokenIndex) in cell" :key="tokenIndex">
                  <strong v-if="token.kind === 'strong'">{{ token.text }}</strong>
                  <code v-else-if="token.kind === 'code'">{{ token.text }}</code>
                  <template v-else>{{ token.text }}</template>
                </template>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <component :is="block.ordered ? 'ol' : 'ul'" v-else-if="block.type === 'list'">
        <li v-for="(item, itemIndex) in block.items" :key="itemIndex">
          <template v-for="(token, tokenIndex) in item" :key="tokenIndex">
            <strong v-if="token.kind === 'strong'">{{ token.text }}</strong>
            <code v-else-if="token.kind === 'code'">{{ token.text }}</code>
            <template v-else>{{ token.text }}</template>
          </template>
        </li>
      </component>

      <pre v-else-if="block.type === 'code'"><code :data-language="block.language">{{ block.text }}</code></pre>
      <hr v-else-if="block.type === 'divider'">
      <p v-else>
        <template v-for="(token, tokenIndex) in block.tokens" :key="tokenIndex">
          <strong v-if="token.kind === 'strong'">{{ token.text }}</strong>
          <code v-else-if="token.kind === 'code'">{{ token.text }}</code>
          <template v-else>{{ token.text }}</template>
        </template>
      </p>
    </template>
  </div>
</template>
