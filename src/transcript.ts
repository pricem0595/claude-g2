// The transcript as display lines, with a scroll position. Follows new output until the user
// scrolls up, and keeps its place when older messages are loaded above.
//
// The SDK can't set a text container's scroll position, and the firmware's own scrolling
// starts at the top, which is the wrong end for a live transcript. So the body holds exactly
// one screen of lines and each swipe moves it a few lines, which reads as a scroll.

import { BODY_LINES, BODY_WIDTH } from './glasses'
import { fitBytes, wrap } from './text'

/** Lines moved per swipe (user choice, 2026-09-23). */
const SCROLL_STEP = 1

export class Transcript {
  private blocks: string[] = []
  private lines: string[] = []
  /** Index of the first line on screen, or null to follow the end. */
  private top: number | null = null
  /** New output arrived below while scrolled up. */
  private unseen = false
  /** The phone's list is at its newest message, so following the end here means live. */
  private atLatest = true

  get following(): boolean {
    return this.top === null
  }

  /** Replaces the history. Returns true if what's on screen or the status changed. */
  update(blocks: string[], atLatest = true): boolean {
    const before = this.visible() + this.status()
    if (this.top !== null) {
      // Older messages loaded above push the visible ones down; keep them in view.
      const shift = blocks.indexOf(this.blocks[0] ?? '')
      if (shift > 0) this.top += this.toLines(blocks.slice(0, shift)).length
      // Only a new message counts: the last one changing in place (streaming text, or the
      // "1m 46s · thinking…" row ticking every second) would otherwise show it constantly.
      if (blocks.length > this.blocks.length + Math.max(0, shift)) this.unseen = true
    }
    this.blocks = blocks
    this.atLatest = atLatest
    this.lines = this.toLines(blocks)
    return this.visible() + this.status() !== before
  }

  reset(): void {
    this.blocks = []
    this.lines = []
    this.top = null
    this.unseen = false
  }

  /** Scrolls up a few lines. Returns false when already at the top of what's loaded. */
  up(): boolean {
    const top = this.start()
    if (top === 0) return false
    this.top = Math.max(0, top - SCROLL_STEP)
    return true
  }

  /** Scrolls down a few lines, back to following once the end is reached. */
  down(): boolean {
    if (this.top === null) return false
    const next = this.top + SCROLL_STEP
    if (next >= this.lines.length - BODY_LINES) this.follow()
    else this.top = next
    return true
  }

  /**
   * Stops following and holds the current view, so newer lines loaded below don't jump it to the
   * end. Used before asking the phone for newer messages.
   */
  pin(): void {
    this.top = this.start()
  }

  follow(): void {
    this.top = null
    this.unseen = false
  }

  /** The body text for the screen. */
  visible(): string {
    const start = this.start()
    return fitBytes(this.lines.slice(start, start + BODY_LINES))
  }

  /** Header note: "live" while following the newest output, "new below" if some arrived out of view. */
  status(): string {
    if (this.top === null) return this.atLatest ? 'live' : ''
    return this.unseen ? 'new below' : ''
  }

  /** Position summary for the dev trace, e.g. "top=12/40 new-below" or "follow/40". */
  debug(): string {
    return `${this.top === null ? 'follow' : `top=${this.top}`}/${this.lines.length}${this.unseen ? ' new-below' : ''}`
  }

  private start(): number {
    return this.top ?? Math.max(0, this.lines.length - BODY_LINES)
  }

  private toLines(blocks: string[]): string[] {
    return blocks.flatMap((block) => wrap(block, BODY_WIDTH))
  }
}
