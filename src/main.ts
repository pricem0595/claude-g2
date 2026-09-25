import { OsEventTypeList, waitForEvenAppBridge, type EvenAppBridge, type EvenHubEvent } from '@evenrealities/even_hub_sdk'
import { api, BRIDGE_URL, setToken, type MirrorState } from './api/bridge'
import { Glasses, type PageKind } from './glasses'
import { Mirror } from './mirror'
import { Voice } from './voice'

const TOKEN_KEY = 'token'

type Input =
  | { kind: 'select'; index: number }
  | { kind: 'scroll'; dir: 'up' | 'down' }
  | { kind: 'tap' }
  | { kind: 'back' }
  | { kind: 'foreground' }
  | { kind: 'background' }
  | { kind: 'hold' }
  | { kind: 'release' }
  | { kind: 'exit' }

/**
 * Turns a raw hub event into an input. Protobuf omits zero values, so a missing eventType is
 * CLICK_EVENT (0) and a missing list index is item 0. List pages report taps as listEvent (and
 * scroll natively, moving the highlight); text pages report scroll as textEvent and taps as
 * sysEvent. A long press and its release are separate events (SDK 0.0.15+).
 */
function toInput(event: EvenHubEvent, page: PageKind): Input | null {
  const { listEvent, textEvent, sysEvent } = event

  if (listEvent) {
    const type = listEvent.eventType ?? OsEventTypeList.CLICK_EVENT
    if (type === OsEventTypeList.DOUBLE_CLICK_EVENT) return { kind: 'back' }
    if (type === OsEventTypeList.CLICK_EVENT) return { kind: 'select', index: listEvent.currentSelectItemIndex ?? 0 }
    return null
  }

  const other = sysEvent ?? textEvent
  if (!other) return null
  switch (other.eventType ?? OsEventTypeList.CLICK_EVENT) {
    case OsEventTypeList.SCROLL_TOP_EVENT:
      return page === 'text' ? { kind: 'scroll', dir: 'up' } : null
    case OsEventTypeList.SCROLL_BOTTOM_EVENT:
      return page === 'text' ? { kind: 'scroll', dir: 'down' } : null
    case OsEventTypeList.CLICK_EVENT:
      return page === 'text' ? { kind: 'tap' } : null
    case OsEventTypeList.DOUBLE_CLICK_EVENT:
      return { kind: 'back' }
    case OsEventTypeList.LONG_PRESS_EVENT:
      return { kind: 'hold' }
    case OsEventTypeList.LONG_PRESS_RELEASE_EVENT:
      return { kind: 'release' }
    case OsEventTypeList.FOREGROUND_ENTER_EVENT:
      return { kind: 'foreground' }
    case OsEventTypeList.FOREGROUND_EXIT_EVENT:
      return { kind: 'background' }
    case OsEventTypeList.SYSTEM_EXIT_EVENT:
    case OsEventTypeList.ABNORMAL_EXIT_EVENT:
      return { kind: 'exit' }
    default:
      return null
  }
}

async function handle(mirror: Mirror, glasses: Glasses, input: Input): Promise<void> {
  switch (input.kind) {
    case 'select':
      return mirror.select(input.index)
    case 'scroll':
      return mirror.scroll(input.dir)
    case 'tap':
      return mirror.tap()
    case 'back':
      if (!(await mirror.back())) await glasses.exit()
      return
    case 'foreground':
      return mirror.redraw()
    case 'exit':
      return mirror.stop()
    case 'background':
    case 'hold':
    case 'release':
      return
  }
}

/**
 * Input while the voice popup is up, or a hold/release that opens or closes it. Never dropped
 * as busy: a lost release would leave the mic on.
 */
async function handleVoice(voice: Voice, mirror: Mirror, input: Input): Promise<void> {
  switch (input.kind) {
    case 'hold':
      return voice.start()
    case 'release':
      return voice.stop()
    case 'tap':
      return voice.confirm()
    case 'back':
    case 'background':
      return voice.cancel()
    case 'exit':
      await voice.cancel()
      return mirror.stop()
    case 'select':
    case 'scroll':
    case 'foreground':
      return
  }
}

/** The phone-side page: bridge status and the pairing token field. */
async function setUpPhonePage(bridge: EvenAppBridge, mirror: Mirror): Promise<void> {
  const url = document.getElementById('bridge-url')
  const state = document.getElementById('bridge-state')
  const input = document.getElementById('token') as HTMLInputElement | null
  const save = document.getElementById('save-token')
  if (url) url.textContent = BRIDGE_URL

  const refresh = () =>
    api
      .health()
      .then((h) => (h.foreground ? 'connected, Claude app on screen' : 'connected, Claude app not on screen'))
      .catch((err) => (err instanceof Error ? err.message : 'not reachable'))
      .then((text) => state && (state.textContent = text))

  // The WebView's own localStorage doesn't reliably persist; the bridge's storage does.
  const saved = (await bridge.getLocalStorage(TOKEN_KEY).catch(() => '')) || import.meta.env.VITE_BRIDGE_TOKEN || ''
  setToken(saved)
  if (input) input.value = saved

  save?.addEventListener('click', async () => {
    const token = input?.value.trim() ?? ''
    setToken(token)
    await bridge.setLocalStorage(TOKEN_KEY, token).catch(() => false)
    mirror.restart()
    await refresh()
  })
  await refresh()
}

/**
 * Dev builds only: sends the raw Claude screen behind each new state to the Vite dev server,
 * which saves it under captures/ (see vite.config.ts). At most one every few seconds.
 */
function captureToDevServer(): (state: MirrorState) => void {
  const MIN_GAP_MS = 4000
  let last = 0
  let pending: ReturnType<typeof setTimeout> | undefined
  let latest: MirrorState
  const send = async () => {
    pending = undefined
    last = Date.now()
    try {
      const xml = await api.dump()
      await fetch('/__capture', { method: 'POST', headers: { 'X-Capture-Label': latest.screen }, body: xml })
    } catch (err) {
      console.warn('[claude-g2] capture failed', err instanceof Error ? err.message : err)
    }
  }
  return (state) => {
    latest = state
    if (!state.foreground || pending) return
    pending = setTimeout(send, Math.max(0, last + MIN_GAP_MS - Date.now()))
  }
}

async function main(): Promise<void> {
  const bridge = await waitForEvenAppBridge()
  const glasses = new Glasses(bridge)
  const mirror = new Mirror(glasses)
  const voice = new Voice(glasses)
  voice.onOpenChange = (open) => mirror.setOverlay(open)

  // Inputs that arrive while one is still being handled are dropped. Taps made during a slow
  // action would otherwise replay against whatever the Claude app shows next.
  let busy = false
  bridge.onEvenHubEvent(async (event) => {
    // Many small chunks a second while recording: straight to the recorder, not logged.
    if (event.audioEvent) {
      voice.onAudio(event.audioEvent.audioPcm)
      return
    }
    const input = toInput(event, glasses.kind)
    if (import.meta.env.DEV) console.log('[claude-g2] event', JSON.stringify(event), '→', input?.kind ?? 'ignored')
    if (!input) return

    // A hold opens the popup only in a session, and not while a mirror action could still
    // draw over it. Once open, the popup takes every input.
    const startsVoice = input.kind === 'hold' && !busy && mirror.canDictate()
    if (voice.open || startsVoice) {
      handleVoice(voice, mirror, input).catch((err) => console.error('[claude-g2] voice', err instanceof Error ? err.message : err))
      return
    }
    if (input.kind === 'hold' || input.kind === 'release' || input.kind === 'background') return
    if (busy && input.kind !== 'exit') return

    busy = true
    try {
      await handle(mirror, glasses, input)
    } catch (err) {
      console.error('[claude-g2]', err instanceof Error ? err.message : err)
    } finally {
      busy = false
    }
  })

  if (import.meta.env.DEV) mirror.onState = captureToDevServer()
  // The simulator can't long-press. Dev builds loaded with ?voice-demo do one hold, 1.5 s of
  // recording and a release as soon as a session is open; then tap or double-tap in the
  // simulator. The desktop bridge hears any recording as the same sentence.
  if (import.meta.env.DEV && location.search.includes('voice-demo')) {
    const timer = setInterval(async () => {
      if (!mirror.canDictate()) return
      clearInterval(timer)
      await handleVoice(voice, mirror, { kind: 'hold' })
      voice.onAudio(new Uint8Array(64000).map((_, i) => i % 7))
      await new Promise((resolve) => setTimeout(resolve, 1500))
      await handleVoice(voice, mirror, { kind: 'release' })
    }, 500)
  }

  await setUpPhonePage(bridge, mirror)
  await mirror.run()
}

main().catch((err) => console.error('[claude-g2] startup failed', err instanceof Error ? err.message : err))
