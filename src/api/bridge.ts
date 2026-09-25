// Typed client for the on-phone Claude G2 Bridge. The contract lives in docs/API.md.

const DEFAULT_URL = 'http://127.0.0.1:8421'

// Build-time override lets a dev build on real glasses reach a desktop bridge on a PC.
// Whatever origin is used here must also be in app.json's network whitelist.
export const BRIDGE_URL: string = import.meta.env.VITE_BRIDGE_URL ?? DEFAULT_URL

export type ScreenKind = 'sessions' | 'transcript' | 'prompt' | 'unknown'

export interface Choice {
  id: string
  label: string
  detail: string | null
}

export interface MirrorState {
  version: number
  /** False when the Claude app isn't on the phone's screen; the rest is the last thing seen. */
  foreground: boolean
  screen: ScreenKind
  title: string | null
  sessions: Choice[]
  /** Transcript history, oldest first (or all visible text, for 'unknown'). */
  lines: string[]
  prompt: { question: string; options: Choice[] } | null
  /** Phone locked or screen off: nothing can be read or tapped, only notifications arrive. */
  locked: boolean
  /** The latest Claude notification since the phone locked, e.g. "Claude has a question: Claude" / "Colour". */
  notice: { title: string; text: string | null } | null
  /** The Claude app's list is at its newest message (it can't scroll further toward newer). */
  atLatest: boolean
}

export type BridgeErrorKind = 'unreachable' | 'unauthorized' | 'failed'

export class BridgeError extends Error {
  readonly kind: BridgeErrorKind

  constructor(message: string, kind: BridgeErrorKind) {
    super(message)
    this.kind = kind
  }
}

let token = ''

export function setToken(value: string): void {
  token = value.trim()
}

async function request(
  method: 'GET' | 'POST',
  path: string,
  body: unknown,
  timeoutMs: number,
  signal?: AbortSignal,
): Promise<Response> {
  const headers: Record<string, string> = { Authorization: `Bearer ${token}` }
  const raw = body instanceof Uint8Array
  if (body !== undefined) headers['Content-Type'] = raw ? 'application/octet-stream' : 'application/json'
  const timeout = AbortSignal.timeout(timeoutMs)
  const started = Date.now()
  let res: Response
  try {
    res = await fetch(BRIDGE_URL + path, {
      method,
      headers,
      body: body === undefined ? undefined : raw ? (body as Uint8Array<ArrayBuffer>) : JSON.stringify(body),
      signal: signal ? AbortSignal.any([signal, timeout]) : timeout,
    })
  } catch (err) {
    // Dev trace for triage: which request failed, how, and after how long.
    if (import.meta.env.DEV) {
      const e = err instanceof Error ? `${err.name}: ${err.message}` : String(err)
      const why = signal?.aborted ? ' (aborted by app)' : timeout.aborted ? ` (timed out after ${timeoutMs} ms)` : ''
      console.warn(`[claude-g2] fetch ${method} ${path} failed after ${Date.now() - started} ms${why}: ${e}`)
    }
    throw new BridgeError('Bridge not reachable', 'unreachable')
  }
  if (res.status === 401) throw new BridgeError('Wrong pairing token', 'unauthorized')
  return res
}

async function call<T>(method: 'GET' | 'POST', path: string, body?: unknown, timeoutMs = 8000): Promise<T> {
  const res = await request(method, path, body, timeoutMs)
  const data = await res.json().catch(() => ({ ok: false, error: `HTTP ${res.status}` }))
  if (!res.ok || !data.ok) throw new BridgeError(data.error ?? `HTTP ${res.status}`, 'failed')
  return data as T
}

export const api = {
  health: () => call<{ service: boolean; foreground: boolean }>('GET', '/health', undefined, 4000),

  /**
   * The current state, or, with `since`, the next one after it. The bridge holds the request
   * for up to 25 s and answers 204 (null here) if nothing changed.
   */
  async state(since?: number, signal?: AbortSignal): Promise<MirrorState | null> {
    const res = await request('GET', since === undefined ? '/state' : `/state?since=${since}`, undefined, 35000, signal)
    if (res.status === 204) return null
    const data = await res.json().catch(() => ({ ok: false, error: `HTTP ${res.status}` }))
    if (!res.ok || !data.ok) throw new BridgeError(data.error ?? `HTTP ${res.status}`, 'failed')
    return data as MirrorState
  },

  /** The raw Claude screen as uiautomator XML (dev capture). */
  async dump(): Promise<string> {
    const res = await request('GET', '/dump', undefined, 8000)
    if (!res.ok) throw new BridgeError(`HTTP ${res.status}`, 'failed')
    return res.text()
  },

  click: (choice: Choice) => call('POST', '/action', { type: 'click', id: choice.id, label: choice.label }),
  /** Scrolls the Claude app's list: up loads older messages, down newer, latest jumps to the end. */
  scroll: (dir: 'up' | 'down' | 'latest') => call('POST', '/action', { type: 'scroll', dir }, dir === 'latest' ? 20000 : 8000),
  back: () => call('POST', '/action', { type: 'back' }),

  // Speech to text on the phone, streamed: start when recording starts, send the audio (16 kHz
  // 16-bit mono) as it's recorded, and finish on release for the text, "" when no words were
  // heard. The phone's recognizer drops audio that arrives faster than real time.
  voiceStart: async () => (await call<{ id: string }>('POST', '/voice/start')).id,
  voiceAudio: async (id: string, pcm: Uint8Array) => {
    await call('POST', `/voice/audio?id=${encodeURIComponent(id)}`, pcm)
  },
  voiceFinish: async (id: string) =>
    (await call<{ text: string }>('POST', `/voice/finish?id=${encodeURIComponent(id)}`, undefined, 25000)).text,
  voiceCancel: async (id: string) => {
    await call('POST', `/voice/cancel?id=${encodeURIComponent(id)}`)
  },
  /** Types `text` into the open session's message box and taps Send. */
  send: (text: string) => call('POST', '/action', { type: 'send', text }, 10000),
}
