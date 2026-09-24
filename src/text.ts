// Text fitting for the glasses font, which is proportional. @evenrealities/pretext measures
// it the way the firmware does, so lines broken here aren't re-wrapped by the glasses.

import { getTextWidth, pxTruncate } from '@evenrealities/pretext'

// The firmware refuses text containers over about 1000 bytes (G2-NOTES.md: 999 in the simulator).
export const MAX_TEXT_BYTES = 990

const encoder = new TextEncoder()
export const byteLength = (text: string) => encoder.encode(text).length

/** Splits one block of text into display lines no wider than `width` pixels. */
export function wrap(text: string, width: number): string[] {
  const lines: string[] = []
  for (const paragraph of text.split('\n')) {
    let line = ''
    for (const word of paragraph.split(/\s+/).filter(Boolean)) {
      const candidate = line ? `${line} ${word}` : word
      if (getTextWidth(candidate) <= width) {
        line = candidate
        continue
      }
      if (line) lines.push(line)
      line = word
      // A word wider than the line (a path, a URL) is hard-broken.
      while (getTextWidth(line) > width) {
        let cut = line.length - 1
        while (cut > 1 && getTextWidth(line.slice(0, cut)) > width) cut--
        lines.push(line.slice(0, cut))
        line = line.slice(cut)
      }
    }
    lines.push(line)
  }
  return lines
}

/** Fits one line into `width` pixels, ending in '...' if it had to be cut. */
export const truncate = (text: string, width: number) => pxTruncate(text.replace(/\s+/g, ' ').trim(), width)

/**
 * Joins already-wrapped lines, dropping lines from the start until the result fits in a text
 * container. A wrapped line is one screen width, far under the limit on its own.
 */
export function fitBytes(lines: string[]): string {
  let start = 0
  while (start < lines.length - 1 && byteLength(lines.slice(start).join('\n')) > MAX_TEXT_BYTES) start++
  return lines.slice(start).join('\n')
}
