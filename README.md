# Claude G2

Claude Code Remote Control sessions on Even Realities G2 glasses, with no computer on your side.
It mirrors the **Claude Android app**: whatever the app shows (the session list, a live
transcript, a permission or question prompt) is shown on the glasses. Picking a prompt option on
the glasses taps it in the app.

```
Claude app  ←reads / taps←  Claude G2 Bridge (accessibility service)  ←HTTP on 127.0.0.1:8421←
                                                                         Claude G2 glasses app (Even app WebView)  →BLE→  G2
```

**Why a mirror?** Remote Control has no public API. The endpoints the Claude apps use need your
claude.ai login token, and Anthropic's terms don't let third-party apps handle it
(code.claude.com/docs/en/legal-and-compliance). The mirror never touches credentials: the Claude
app stays signed in, and this only reads its screen.

## On the glasses

| where | scroll | tap | double-tap |
| --- | --- | --- | --- |
| Session list | move the highlight | open that session | close the app |
| Transcript | one line back / forward (past what's loaded, scrolls the phone to load older or newer messages) | jump to the newest message, scrolling the phone there too | back to the session list |
| Prompt (permission or question) | move through the options | **pick the highlighted option** | nothing, so a stray double-tap can't dismiss a prompt |
| Error screen | | retry | close the app |

## Setup (Android only)

1. Build and install the bridge. You need a JDK 17+ as `JAVA_HOME`:
   `cd bridge-android && gradlew assembleDebug`, then
   `adb install app/build/outputs/apk/debug/app-debug.apk`.
2. Open **Claude G2 Bridge**. Tap *Open Accessibility settings* and turn on **Claude G2 mirror**.
   Android 13+ blocks sideloaded accessibility services at first. If it does, open app info,
   choose ⋮ → *Allow restricted settings*, and try again.
3. Load the glasses app. For development, run `npm run dev` and then
   `npx evenhub qr --url http://<pc-ip>:5173 --external`. For no PC at all, use `npm run pack`
   and install the `.ehpk`.
4. On the glasses app's phone page, enter the pairing token the bridge shows.
5. Open a Claude Code session in the Claude app and leave it on screen. The bridge keeps the
   screen on while it runs; you can turn that off.

## Development

| what | command |
| --- | --- |
| Bridge unit tests | `cd bridge-android && gradlew testDebugUnitTest` |
| Bridge on this PC (replays the fixture screens; token `ABCDE`) | `npm run bridge` |
| Glasses app against it | `set VITE_BRIDGE_TOKEN=ABCDE && npm run dev`, then `npm run simulate` |
| Drive the simulator | `npm run simulate` starts it with an automation port: `POST http://127.0.0.1:9898/api/input` with `{"action":"down"}` (or `up`, `click`, `double_click`), `GET /api/screenshot/glasses` |
| Bridge on this PC, one long pretend session (for scrolling) | `gradlew testDebugUnitTest --tests "*DesktopBridge*" --rerun -Dbridge.serve=true -Dbridge.scenario=long`; add `-Dbridge.page=6 -Dbridge.steps=1` for whole-screen jumps with no in-between frames, the worst case. Dev builds log each transcript redraw to the console as `[claude-g2] frame …`. |

### When the Claude app changes and the mirror misreads a screen

Everything specific to the Claude app's layout is in
`bridge-android/app/src/main/kotlin/.../ScreenParser.kt` (`ClaudeUi` and `ScreenParser`).

1. Capture the screen: `adb shell uiautomator dump`, or with the Claude app open, the bridge's
   `/dump` endpoint (`adb forward tcp:18421 tcp:8421`, then `curl -H "Authorization: Bearer <token>" http://127.0.0.1:18421/dump`).
2. **Replace the conversation text before committing it**: captures contain your messages,
   session titles and repository names.
3. Add it to `bridge-android/app/src/test/resources/fixtures/`, write a test in
   `ScreenParserTest`, and adjust the parser until it passes.

The `real-*` fixtures are captures from the Claude app with their text replaced by
"Text N lorem ipsum". The `synthetic-*` ones are hand-written stand-ins.
