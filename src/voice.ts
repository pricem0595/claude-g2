// The voice popup: hold the touchpad to record, release to turn it into text, then tap to send
// it to the open session or double-tap to throw it away. The glasses record; the bridge's
// speech recognizer on the phone makes the text; the bridge types it into the Claude app.

import { api } from './api/bridge'
import { BODY_LINES, BODY_WIDTH, type Glasses } from './glasses'
import { fitBytes, wrap } from './text'

/** The glasses' PCM: 16 kHz, 16-bit, mono. */
const BYTES_PER_SECOND = 16000 * 2
/** Recording stops by itself after this long; the bridge takes at most a minute. */
const MAX_SECONDS = 60
/** Shorter than this is a stray press, not speech: close without asking. */
const MIN_BYTES = BYTES_PER_SECOND / 2

const LISTEN_TITLE = 'Voice - release to stop'
// Header text is squeezed to single spaces, so the hints are split with a dot.
const REVIEW_TITLE = 'Tap: send · Double-tap: cancel'
const RETRY_TITLE = 'Hold: try again · Double-tap: close'

type VoiceState = 'idle' | 'listening' | 'transcribing' | 'review' | 'sending'

export class Voice {
  private readonly glasses: Glasses
  private state: VoiceState = 'idle'
  private chunks: Uint8Array[] = []
  private bytes = 0
  private text = ''
  private startedAt = 0
  private ticker: ReturnType<typeof setInterval> | undefined
  /** Goes up each time the popup opens or closes, so late replies for an old one are dropped. */
  private session = 0

  /** Called when the popup opens (before it's drawn) and when it closes. */
  onOpenChange?: (open: boolean) => Promise<void>

  constructor(glasses: Glasses) {
    this.glasses = glasses
  }

  get open(): boolean {
    return this.state !== 'idle'
  }

  /** Touchpad held: start recording. Holding again on the result records a new message. */
  async start(): Promise<void> {
    if (this.state !== 'idle' && this.state !== 'review') return
    const wasOpen = this.open
    const session = ++this.session
    this.state = 'listening'
    this.chunks = []
    this.bytes = 0
    this.text = ''
    this.startedAt = Date.now()
    // Queued before anything else, so the mic-off of a quick release always comes after it.
    const micOn = this.glasses.mic(true)
    micOn.catch(() => undefined)
    if (!wasOpen) await this.onOpenChange?.(true)
    if (session !== this.session) return

    this.ticker = setInterval(() => {
      if (this.state === 'listening') this.glasses.setStatus(this.elapsed()).catch(() => undefined)
    }, 1000)
    try {
      if (!(await micOn)) throw new Error('The microphone did not start')
      // Released already: stop() has taken over the screen.
      if (this.state === 'listening') await this.glasses.showText(LISTEN_TITLE, this.elapsed(), 'Listening...')
    } catch (err) {
      if (session !== this.session || this.state !== 'listening') return
      this.stopTicker()
      await this.glasses.mic(false).catch(() => undefined)
      await this.fail(err)
    }
  }

  /** A chunk of audio from the glasses. */
  onAudio(pcm: Uint8Array): void {
    if (this.state !== 'listening') return
    // The host may reuse the buffer.
    this.chunks.push(pcm.slice())
    this.bytes += pcm.length
    if (this.bytes >= MAX_SECONDS * BYTES_PER_SECOND) void this.stop()
  }

  /** Touchpad released: stop recording and turn it into text. */
  async stop(): Promise<void> {
    if (this.state !== 'listening') return
    const session = this.session
    this.state = 'transcribing'
    this.stopTicker()
    await this.glasses.mic(false).catch(() => undefined)
    if (session !== this.session) return

    if (this.bytes < MIN_BYTES) return this.close()
    const pcm = this.recording()
    this.chunks = []

    try {
      await this.glasses.setStatus('')
      await this.glasses.setBody('Turning speech into text...')
      const text = await api.transcribe(pcm)
      if (session !== this.session) return
      this.text = text.trim()
      this.state = 'review'
      if (!this.text) {
        await this.glasses.setTitle(RETRY_TITLE)
        await this.glasses.setBody("Didn't catch that.")
        return
      }
      await this.glasses.setTitle(REVIEW_TITLE)
      await this.glasses.setBody(fitBody(this.text))
    } catch (err) {
      if (session !== this.session) return
      await this.fail(err)
    }
  }

  /** Tap: send the text to the open session. With nothing to send, a tap just closes. */
  async confirm(): Promise<void> {
    if (this.state !== 'review') return
    if (!this.text) return this.close()
    const session = this.session
    this.state = 'sending'
    try {
      await this.glasses.setStatus('Sending...')
      await api.send(this.text)
      if (session === this.session) await this.close()
    } catch (err) {
      if (session !== this.session) return
      // Keep the text up, so it can be tried again or cancelled.
      this.state = 'review'
      const message = err instanceof Error ? err.message : String(err)
      await this.glasses.setStatus(`× ${message}`).catch(() => undefined)
    }
  }

  /** Double-tap, or the app leaving the screen: stop everything and close without sending. */
  async cancel(): Promise<void> {
    if (!this.open) return
    const wasListening = this.state === 'listening'
    this.stopTicker()
    if (wasListening) await this.glasses.mic(false).catch(() => undefined)
    await this.close()
  }

  private async close(): Promise<void> {
    this.session++
    this.state = 'idle'
    this.chunks = []
    this.text = ''
    await this.onOpenChange?.(false)
  }

  /** Shows what went wrong; hold records again, double-tap closes. */
  private async fail(err: unknown): Promise<void> {
    this.state = 'review'
    this.text = ''
    const message = err instanceof Error ? err.message : String(err)
    await this.glasses.showText(RETRY_TITLE, '', `× ${message}`).catch(() => undefined)
  }

  private recording(): Uint8Array {
    const pcm = new Uint8Array(this.bytes)
    let at = 0
    for (const chunk of this.chunks) {
      pcm.set(chunk, at)
      at += chunk.length
    }
    return pcm
  }

  private elapsed(): string {
    const seconds = Math.floor((Date.now() - this.startedAt) / 1000)
    return `REC ${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`
  }

  private stopTicker(): void {
    clearInterval(this.ticker)
    this.ticker = undefined
  }
}

/** The text as it fits on screen. Past the last line, the end of the message is what shows. */
function fitBody(text: string): string {
  const lines = wrap(text, BODY_WIDTH)
  const shown = lines.length > BODY_LINES ? ['...', ...lines.slice(lines.length - BODY_LINES + 1)] : lines
  return fitBytes(shown)
}
