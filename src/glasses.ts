// Everything drawn on the glasses goes through this class. Three page layouts:
//
//   text / list                         prompt
//   ┌───────────────────┬──────────┐    ┌──────────────────────────────┐
//   │ title             │ status   │    │ question (up to 3 lines)     │
//   ├───────────────────┴──────────┤    ├──────────────────────────────┤
//   │ body text  or  list          │    │ options list                 │
//   └──────────────────────────────┘    └──────────────────────────────┘
//
// The body or list is the one event-capture container. SDK calls are serialized: the host
// can't take concurrent calls over its BLE link.

import {
  AudioInputSource,
  CreateStartUpPageContainer,
  ListContainerProperty,
  ListItemContainerProperty,
  RebuildPageContainer,
  StartUpPageCreateResult,
  TextContainerProperty,
  TextContainerUpgrade,
  type EvenAppBridge,
} from '@evenrealities/even_hub_sdk'
import { byteLength, truncate } from './text'

const WIDTH = 576
const HEIGHT = 288
const HEADER_HEIGHT = 32
const STATUS_WIDTH = 150
const TITLE_WIDTH = WIDTH - STATUS_WIDTH
const CONTENT_Y = HEADER_HEIGHT + 2
const CONTENT_HEIGHT = HEIGHT - CONTENT_Y
const BODY_PADDING = 6
const LINE_HEIGHT = 27
const SDK_TIMEOUT_MS = 10_000

/** How many display lines the body shows, and how wide they may be. */
export const BODY_LINES = Math.floor((CONTENT_HEIGHT - 2 * BODY_PADDING) / LINE_HEIGHT)
export const BODY_WIDTH = WIDTH - 2 * BODY_PADDING - 12

export const QUESTION_LINES = 3
export const QUESTION_WIDTH = WIDTH - 2 * BODY_PADDING - 12
const QUESTION_HEIGHT = QUESTION_LINES * LINE_HEIGHT + 2 * BODY_PADDING

// List limits from the SDK: at most 20 items of at most 64 characters each.
export const MAX_LIST_ITEMS = 20
// The SDK documents 64 characters, but G2-NOTES.md measured 63 bytes in the simulator and longer
// items make the whole page fail to draw. Stay safely under, in UTF-8 bytes.
const MAX_ITEM_BYTES = 60
const ITEM_WIDTH = WIDTH - 40

// The card: a bordered box over the lower part of the screen. The glasses can't fill a
// background, so anything behind it would show through; instead the page behind is redrawn
// dimmed and cut to the lines that fit above the card.
const CARD_Y = 96
const CARD_MARGIN = 12
const CARD_PADDING = 8
const CARD_BORDER = 2
const CARD_HEIGHT = HEIGHT - CARD_Y - 6
/** Text lines inside the card, and how wide they may be. */
export const CARD_LINES = Math.floor((CARD_HEIGHT - 2 * (CARD_PADDING + CARD_BORDER)) / LINE_HEIGHT)
export const CARD_WIDTH = WIDTH - 2 * CARD_MARGIN - 2 * (CARD_PADDING + CARD_BORDER) - 12
/**
 * Lines of the page behind that fit above the card. Its padding is tighter than the body's:
 * two lines with the body's padding overflowed, and the glasses drew a scroll bar.
 */
const BACKDROP_PADDING = 2
export const BACKDROP_LINES = Math.floor((CARD_Y - CONTENT_Y - 2 * BACKDROP_PADDING) / LINE_HEIGHT)
/** Brightness (0-4) of the page behind the card. */
const DIM = 1

const TITLE = { containerID: 1, containerName: 'title' }
const STATUS = { containerID: 2, containerName: 'status' }
const LIST = { containerID: 3, containerName: 'list' }
const BODY = { containerID: 4, containerName: 'body' }
const QUESTION = { containerID: 5, containerName: 'question' }
const CARD = { containerID: 6, containerName: 'card' }

/** What the card is drawn over: the page it covers, dimmed. */
export interface Backdrop {
  title: string
  status: string
  /** Body lines, already wrapped; the last ones that fit above the card are shown. */
  lines: string[]
}

export type PageKind = 'text' | 'list'

function withTimeout<T>(promise: Promise<T>, what: string): Promise<T> {
  return Promise.race([
    promise,
    new Promise<never>((_, reject) => setTimeout(() => reject(new Error(`${what} timed out`)), SDK_TIMEOUT_MS)),
  ])
}

// Header text must stay on one line: a container one line tall shows the first line of wrapped
// text and hides the rest, so an over-long title looked like a stray fragment.
const fitTitle = (text: string) => truncate(text, TITLE_WIDTH - 8)
const fitStatus = (text: string) => truncate(text, STATUS_WIDTH - 4)

export const listItem = (text: string) => {
  const fitted = truncate(text, ITEM_WIDTH)
  if (byteLength(fitted) <= MAX_ITEM_BYTES) return fitted
  let cut = [...fitted]
  while (cut.length > 0 && byteLength(cut.join('') + '...') > MAX_ITEM_BYTES) cut.pop()
  return cut.join('').trimEnd() + '...'
}

export class Glasses {
  /** Kind of the page on screen, which decides how input events are routed. */
  kind: PageKind = 'text'

  private readonly bridge: EvenAppBridge
  private started = false
  private queue: Promise<unknown> = Promise.resolve()
  /** What each text container shows now, by container ID. */
  private shown = new Map<number, string>()

  constructor(bridge: EvenAppBridge) {
    this.bridge = bridge
  }

  showText(title: string, status: string, body: string): Promise<void> {
    const text = new TextContainerProperty({
      ...BODY,
      xPosition: 0,
      yPosition: CONTENT_Y,
      width: WIDTH,
      height: CONTENT_HEIGHT,
      paddingLength: BODY_PADDING,
      isEventCapture: 1,
      content: body,
    })
    return this.render('text', [...this.header(title, status), text], [])
  }

  /**
   * A full-width single line at the very top (no status slot) over body text. Used while the
   * phone is locked, when there's nothing to act on.
   */
  showBanner(line: string, body: string): Promise<void> {
    const banner = new TextContainerProperty({
      ...TITLE,
      xPosition: 0,
      yPosition: 0,
      width: WIDTH,
      height: HEADER_HEIGHT,
      paddingLength: 0,
      isEventCapture: 0,
      content: truncate(line, WIDTH - 8),
    })
    const text = new TextContainerProperty({
      ...BODY,
      xPosition: 0,
      yPosition: CONTENT_Y,
      width: WIDTH,
      height: CONTENT_HEIGHT,
      paddingLength: BODY_PADDING,
      isEventCapture: 1,
      content: body || ' ',
    })
    return this.render('text', [banner, text], [])
  }

  showList(title: string, status: string, items: string[]): Promise<void> {
    return this.render('list', this.header(title, status), [this.list(CONTENT_Y, CONTENT_HEIGHT, items)])
  }

  /** A question over a list of answers: scroll moves the highlight, a tap picks. */
  showPrompt(question: string, options: string[]): Promise<void> {
    const text = new TextContainerProperty({
      ...QUESTION,
      xPosition: 0,
      yPosition: 0,
      width: WIDTH,
      height: QUESTION_HEIGHT,
      paddingLength: BODY_PADDING,
      isEventCapture: 0,
      content: question,
    })
    const listY = QUESTION_HEIGHT + 2
    return this.render('list', [text], [this.list(listY, HEIGHT - listY, options)])
  }

  /**
   * A bordered card over the lower part of the screen, with the page it covers dimmed above
   * it. The card takes the input.
   */
  showCard(backdrop: Backdrop, text: string): Promise<void> {
    const [title, status] = this.header(backdrop.title, backdrop.status)
    title.textColor = DIM
    status.textColor = DIM
    const behind = new TextContainerProperty({
      ...BODY,
      xPosition: 0,
      yPosition: CONTENT_Y,
      width: WIDTH,
      height: CARD_Y - CONTENT_Y,
      paddingLength: BACKDROP_PADDING,
      isEventCapture: 0,
      textColor: DIM,
      content: backdrop.lines.slice(-BACKDROP_LINES).join('\n') || ' ',
    })
    const card = new TextContainerProperty({
      ...CARD,
      xPosition: CARD_MARGIN,
      yPosition: CARD_Y,
      width: WIDTH - 2 * CARD_MARGIN,
      height: CARD_HEIGHT,
      borderWidth: CARD_BORDER,
      borderColor: 15,
      borderRadius: 8,
      paddingLength: CARD_PADDING,
      isEventCapture: 1,
      content: text || ' ',
    })
    return this.render('text', [title, status, behind, card], [])
  }

  /** Replaces the card's text without redrawing the page. */
  setCard(text: string): Promise<boolean> {
    return this.upgrade(CARD, text)
  }

  /** Replaces the title without redrawing the page (no flicker). */
  setTitle(text: string): Promise<boolean> {
    return this.upgrade(TITLE, fitTitle(text))
  }

  /** Replaces the status without redrawing the page. */
  setStatus(text: string): Promise<boolean> {
    return this.upgrade(STATUS, fitStatus(text))
  }

  /** Replaces the body text of a text page without redrawing it. */
  setBody(text: string): Promise<boolean> {
    return this.upgrade(BODY, text)
  }

  /**
   * Turns the glasses' microphone on or off. Queued with the drawing calls, so a quick
   * press-and-release always turns it off after it was turned on.
   */
  mic(on: boolean): Promise<boolean> {
    return this.run('audioControl', () =>
      on ? this.bridge.audioControl(true, AudioInputSource.Glasses) : this.bridge.audioControl(false),
    )
  }

  /** Asks the host to close the app; exit mode 1 shows its confirmation dialog. */
  exit(): Promise<boolean> {
    return this.run('shutDownPageContainer', () => this.bridge.shutDownPageContainer(1))
  }

  private list(y: number, height: number, items: string[]): ListContainerProperty {
    const shown = items.slice(0, MAX_LIST_ITEMS)
    if (shown.length === 0) throw new Error('A list needs at least one item')
    return new ListContainerProperty({
      ...LIST,
      xPosition: 0,
      yPosition: y,
      width: WIDTH,
      height,
      paddingLength: 4,
      isEventCapture: 1,
      itemContainer: new ListItemContainerProperty({
        itemCount: shown.length,
        itemWidth: 0,
        isItemSelectBorderEn: 1,
        itemName: shown.map(listItem),
      }),
    })
  }

  private header(title: string, status: string): TextContainerProperty[] {
    return [
      new TextContainerProperty({
        ...TITLE,
        xPosition: 0,
        yPosition: 0,
        width: TITLE_WIDTH,
        height: HEADER_HEIGHT,
        paddingLength: 0,
        isEventCapture: 0,
        content: fitTitle(title),
      }),
      new TextContainerProperty({
        ...STATUS,
        xPosition: WIDTH - STATUS_WIDTH,
        yPosition: 0,
        width: STATUS_WIDTH,
        height: HEADER_HEIGHT,
        paddingLength: 0,
        isEventCapture: 0,
        textColor: 2,
        content: fitStatus(status) || ' ',
      }),
    ]
  }

  private render(kind: PageKind, textObject: TextContainerProperty[], listObject: ListContainerProperty[]) {
    const page = { containerTotalNum: textObject.length + listObject.length, textObject, listObject }
    this.shown = new Map(textObject.map((t) => [t.containerID ?? 0, t.content ?? '']))
    return this.run('render', async () => {
      // The startup call is one-shot per host session. If the WebView reloads, the host still
      // holds the old page and rejects a second startup call, so fall back to a rebuild.
      const created =
        !this.started &&
        (await this.bridge.createStartUpPageContainer(new CreateStartUpPageContainer(page))) ===
          StartUpPageCreateResult.success
      if (!created && !(await this.bridge.rebuildPageContainer(new RebuildPageContainer(page)))) {
        throw new Error('Could not draw the page')
      }
      this.started = true
      this.kind = kind
    })
  }

  private upgrade(target: { containerID: number; containerName: string }, content: string) {
    // The glasses ignore an update to empty text, leaving the old text up ("Loading newer..."
    // never cleared). A single space clears the container.
    const text = content || ' '
    // Skip updates that change nothing: each one redraws the container, which flickers.
    if (this.shown.get(target.containerID) === text) return Promise.resolve(true)
    this.shown.set(target.containerID, text)
    return this.run('textContainerUpgrade', () =>
      this.bridge.textContainerUpgrade(new TextContainerUpgrade({ ...target, content: text })),
    ).catch((err) => {
      // Unknown what's showing now, so don't skip the next update.
      this.shown.delete(target.containerID)
      throw err
    })
  }

  private run<T>(what: string, fn: () => Promise<T>): Promise<T> {
    const next = this.queue.then(() => withTimeout(fn(), what))
    this.queue = next.catch(() => undefined)
    return next
  }
}
