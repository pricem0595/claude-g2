// Mirrors the bridge's state onto the glasses and turns gestures into taps in the Claude app.
// The Claude app decides what's on screen; this only follows it.

import { api, BridgeError, type Choice, type MirrorState } from './api/bridge'
import { QUESTION_LINES, QUESTION_WIDTH, type Backdrop, type Glasses } from './glasses'
import { wrap } from './text'
import { Transcript } from './transcript'

const RETRY_MS = 3000

type Page =
  | { kind: 'status'; message: string }
  | { kind: 'sessions'; key: string; items: Choice[] }
  | { kind: 'prompt'; key: string; options: Choice[] }
  | { kind: 'transcript' }
  | { kind: 'locked'; body: string }

/** Top line while the phone is locked: nothing can be read or tapped, only notifications arrive. */
const LOCKED_BANNER = 'Phone is locked - Notification only mode'

export class Mirror {
  private readonly glasses: Glasses
  private readonly transcript = new Transcript()
  private state: MirrorState | null = null
  private page: Page | null = null
  /** Title shown in the header. */
  private title = ''
  /** The Claude screen's own title (null when it has none), which identifies the session. */
  private sessionTitle: string | null | undefined = undefined
  /** Serializes redraws from the poll loop and from input. */
  private drawing: Promise<void> = Promise.resolve()
  private stopped = false
  /** Aborts the long poll in flight, so a restart doesn't wait out its 25 s. */
  private inFlight = new AbortController()

  /** Set while jumping to the latest message: state updates are applied but not drawn. */
  private holdDraw = false
  /** Set while the voice popup covers the screen: state updates are kept but nothing is drawn. */
  private overlay = false
  /** Called once the next state from the bridge has been drawn. */
  private stateWaiters: (() => void)[] = []

  /** Called with every new state from the bridge (dev capture uses it). */
  onState?: (state: MirrorState) => void

  constructor(glasses: Glasses) {
    this.glasses = glasses
  }

  /** Long-polls the bridge forever, redrawing on every change. */
  async run(): Promise<void> {
    while (!this.stopped) {
      const abort = (this.inFlight = new AbortController())
      try {
        const next = await api.state(this.state?.version, abort.signal)
        if (abort.signal.aborted) continue
        if (next) {
          this.state = next
          this.onState?.(next)
          await this.draw()
          for (const wake of this.stateWaiters.splice(0)) wake()
        }
      } catch (err) {
        if (abort.signal.aborted) continue
        this.state = null
        await this.draw(err)
        await new Promise((resolve) => setTimeout(resolve, RETRY_MS))
      }
    }
  }

  /** Forgets the current state and fetches it fresh, e.g. after the token changes. */
  restart(): void {
    this.state = null
    this.inFlight.abort()
  }

  stop(): void {
    this.stopped = true
  }

  /** Redraws the current page from scratch, e.g. when the app comes back to the foreground. */
  redraw(): Promise<void> {
    this.page = null
    return this.draw()
  }

  /**
   * Hands the screen to a popup, or takes it back and redraws. Handing it over waits for any
   * draw in progress, so it can't land on top of the popup.
   */
  setOverlay(on: boolean): Promise<void> {
    this.overlay = on
    return on ? this.drawing : this.redraw()
  }

  /** The session on screen, for the voice card to be drawn over. */
  backdrop(): Backdrop {
    return { title: this.title, status: this.transcript.status(), lines: this.transcript.visible().split('\n') }
  }

  /** Dictating a message only makes sense inside a session, where there's a message box. */
  canDictate(): boolean {
    return this.page?.kind === 'transcript' && !!this.state?.foreground && !this.state.locked
  }

  // Input ---------------------------------------------------------------------------------------

  async select(index: number): Promise<void> {
    const page = this.page
    const choice = page?.kind === 'sessions' ? page.items[index] : page?.kind === 'prompt' ? page.options[index] : undefined
    if (!choice) return
    await this.act(`Opening ${choice.label}...`, () => api.click(choice))
  }

  async scroll(dir: 'up' | 'down'): Promise<void> {
    if (this.page?.kind !== 'transcript') return
    if (dir === 'down') {
      if (this.transcript.down()) {
        await this.drawTranscript()
        return
      }
      // At the newest line loaded. If the phone's list is scrolled up, newer messages are below
      // it: scroll the phone forward to load them. Hold the view while they load, so they appear
      // below to scroll through one line at a time instead of jumping to the end of the page.
      // At the real end there's nothing newer: go back to following new output.
      this.transcript.pin()
      let loaded = false
      await this.act('', async () => {
        loaded = await this.load('down')
        if (!loaded) this.transcript.follow()
      })
      // Then make the one-line move the swipe asked for, into what just loaded.
      if (loaded && this.transcript.down()) await this.drawTranscript()
      return
    }
    if (this.transcript.up()) {
      await this.drawTranscript()
      return
    }
    // At the top of what's loaded: scroll the phone to load older messages, then move up into
    // them. At the very start of the conversation there's nothing older; that's not an error.
    let loaded = false
    await this.act('', async () => {
      loaded = await this.load('up')
    })
    if (loaded && this.transcript.up()) await this.drawTranscript()
  }

  /**
   * Asks the phone for older or newer messages and waits until they've arrived here. Returns
   * false when there are none that way.
   */
  private async load(dir: 'up' | 'down'): Promise<boolean> {
    const version = this.state?.version
    try {
      await api.scroll(dir)
    } catch (err) {
      if (isAtEnd(err) || isAtStart(err)) return false
      throw err
    }
    // The bridge answers before its new state reaches this app's long poll.
    if (this.state?.version === version) await this.nextState(1500)
    return true
  }

  /** Resolves when the next state from the bridge has been applied, or after [timeoutMs]. */
  private nextState(timeoutMs: number): Promise<void> {
    return new Promise((resolve) => {
      const timer = setTimeout(resolve, timeoutMs)
      this.stateWaiters.push(() => {
        clearTimeout(timer)
        resolve()
      })
    })
  }

  /** Tap on a text page: jump to the latest output, or retry after an error. */
  async tap(): Promise<void> {
    if (this.page?.kind === 'status') {
      this.restart()
      return
    }
    if (this.page?.kind === 'transcript') {
      // Follow the end of what's loaded now, then bring the phone to its newest message too:
      // what's loaded may stop short of it if the phone's list was scrolled up.
      // The phone pages through everything in between; hold the view and draw once at the end
      // rather than flashing each page past.
      this.transcript.follow()
      this.holdDraw = true
      try {
        await this.act('Jumping to latest...', () => api.scroll('latest'))
      } finally {
        this.holdDraw = false
      }
      await this.drawTranscript()
    }
  }

  /** Double tap: back out of a session. Returns false when there's nothing to go back to. */
  async back(): Promise<boolean> {
    if (this.page?.kind === 'transcript') {
      await this.act('Back...', () => api.back())
      return true
    }
    // On a prompt a stray double tap must not dismiss it; elsewhere it closes the app.
    return this.page?.kind === 'prompt'
  }

  /** Runs an action, showing progress in the status slot and any failure after it. */
  private async act(progress: string, action: () => Promise<unknown>): Promise<void> {
    // No progress text for quick scroll loads: flashing it on every swipe reads as jitter.
    if (progress && this.page?.kind !== 'prompt') await this.glasses.setStatus(progress).catch(() => undefined)
    try {
      await action()
      // Actions that change the screen redraw via the poll loop; one that doesn't (loading older
      // messages when there are none) must not leave the progress text up.
      if (this.page?.kind === 'transcript') await this.glasses.setStatus(this.transcript.status()).catch(() => undefined)
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err)
      if (this.page?.kind === 'prompt') {
        await this.glasses.showPrompt(`× ${message}`, this.page.options.map((o) => o.label))
      } else {
        await this.glasses.setStatus(`× ${message}`).catch(() => undefined)
      }
    }
  }

  // Drawing -------------------------------------------------------------------------------------

  private draw(error?: unknown): Promise<void> {
    const next = this.drawing.then(() => this.drawNow(error))
    this.drawing = next.catch((err) => console.error('[claude-g2] draw failed', err))
    return next
  }

  private async drawNow(error?: unknown): Promise<void> {
    if (this.overlay) return
    const state = this.state
    if (error !== undefined || !state) {
      return this.showStatus(error === undefined ? 'Connecting to the bridge...' : describe(error))
    }
    if (state.locked) {
      // Only the banner, plus the latest notification once one arrives.
      const body = state.notice ? describeNotice(state.notice) : ''
      if (this.page?.kind === 'locked' && this.page.body === body) return
      this.page = { kind: 'locked', body }
      return this.glasses.showBanner(LOCKED_BANNER, body)
    }
    if (!state.foreground) {
      return this.showStatus('Open the Claude app on your phone.\n\nThe glasses mirror whatever it shows.')
    }

    switch (state.screen) {
      case 'sessions': {
        this.transcript.reset()
        const items = state.sessions
        if (items.length === 0) return this.showStatus('No sessions on screen in the Claude app.')
        const key = JSON.stringify(items.map((i) => [i.id, i.label]))
        if (this.page?.kind === 'sessions' && this.page.key === key) return
        this.page = { kind: 'sessions', key, items }
        return this.glasses.showList(state.title ?? 'Sessions', `${items.length} on screen`, items.map((i) => i.label))
      }

      case 'prompt': {
        const prompt = state.prompt
        if (!prompt || prompt.options.length === 0) return this.showStatus('Claude is asking something the glasses can\'t read.')
        const key = JSON.stringify([prompt.question, prompt.options.map((o) => [o.id, o.label])])
        if (this.page?.kind === 'prompt' && this.page.key === key) return
        this.page = { kind: 'prompt', key, options: prompt.options }
        const question = wrap(prompt.question || 'Claude needs an answer', QUESTION_WIDTH)
        const clipped = question.length > QUESTION_LINES
          ? [...question.slice(0, QUESTION_LINES - 1), question[QUESTION_LINES - 1] + '...']
          : question
        return this.glasses.showPrompt(clipped.join('\n'), prompt.options.map(optionText))
      }

      case 'transcript':
      case 'unknown': {
        // A different title is a different session: start its history fresh.
        if (state.title !== this.sessionTitle) this.transcript.reset()
        this.sessionTitle = state.title
        const title = state.title ?? (state.screen === 'unknown' ? 'Claude' : 'Session')
        const changed = this.transcript.update(state.lines, state.atLatest)
        if (this.page?.kind === 'transcript') {
          if (changed && !this.holdDraw) await this.drawTranscript()
          if (title !== this.title) await this.glasses.setTitle(title)
          this.title = title
          return
        }
        this.title = title
        this.page = { kind: 'transcript' }
        return this.glasses.showText(this.title, this.transcript.status(), this.transcript.visible() || ' ')
      }
    }
  }

  private async drawTranscript(): Promise<void> {
    const body = this.transcript.visible()
    // Dev trace for triage in the simulator: one line per redraw.
    if (import.meta.env.DEV) {
      console.log(`[claude-g2] frame ${this.transcript.debug()} [${this.transcript.status()}] | ${body.split('\n')[0]}`)
    }
    await this.glasses.setBody(body)
    await this.glasses.setStatus(this.transcript.status())
  }

  private showStatus(message: string): Promise<void> {
    if (this.page?.kind === 'status' && this.page.message === message) return Promise.resolve()
    this.page = { kind: 'status', message }
    return this.glasses.showText(`Claude G2 v${__APP_VERSION__}`, '', message)
  }
}

/** The bridge's answers when the phone's list can't scroll further that way. */
const isAtEnd = (err: unknown) => err instanceof BridgeError && err.message === 'Already at the end'
const isAtStart = (err: unknown) => err instanceof BridgeError && err.message === 'Nothing older to load'

/**
 * A Claude notification as one message. Android gives the title as e.g. "Claude has a question:
 * Claude" (the app name appended) and the text as the question's short header, "Colour". Only
 * the header is in the notification, never the options, so answering needs the phone.
 */
export function describeNotice(notice: { title: string; text: string | null }): string {
  const title = notice.title.replace(/:\s*Claude$/, '').trim()
  const message = notice.text ? `${title}: ${notice.text}` : title
  const needsAnswer = /question|permission|approv|needs|waiting|input/i.test(title)
  return needsAnswer ? `${message} — unlock to answer` : message
}

/** Option text for the list: label, plus its description when there's room for it. */
const optionText = (o: Choice) => (o.detail ? `${o.label} - ${o.detail}` : o.label)

function describe(err: unknown): string {
  if (err instanceof BridgeError) {
    switch (err.kind) {
      case 'unreachable':
        return 'Bridge not reachable.\n\nTurn on "Claude G2 mirror" in the phone\'s Accessibility settings. Tap to retry.'
      case 'unauthorized':
        return 'Wrong pairing token.\n\nEnter the token from the Claude G2 Bridge app on this app\'s phone page.'
      case 'failed':
        return `Bridge error: ${err.message}\n\nTap to retry.`
    }
  }
  return `Something went wrong: ${err instanceof Error ? err.message : String(err)}\n\nTap to retry.`
}
