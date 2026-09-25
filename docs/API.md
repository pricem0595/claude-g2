# Bridge HTTP API

The Claude G2 Bridge serves this on `http://127.0.0.1:8421`, bound to loopback only. Every
response carries `Access-Control-Allow-Origin: *`, because the glasses app runs in the Even
app's WebView.

## Auth

Every request except the CORS preflight (`OPTIONS`) needs the pairing token shown in the bridge
app, as `Authorization: Bearer ABCDE` or `?token=ABCDE`. Case, spaces and dashes are
ignored when comparing. Without it the bridge answers `401`.

## Routes

| route | answer |
| --- | --- |
| `GET /health` | `{ok, service, package, speech, foreground}`. `speech`: a speech recognizer is available. |
| `GET /state` | The current [state](#state), right away. |
| `GET /state?since=<version>` | Long poll. Returns the state once its `version` differs from `since`, or `204` after 25 s with no change. |
| `POST /action` | `{"type":"click","id":"…","label":"…"}` taps a session or prompt option. The `label` is a fallback in case the screen was redrawn and the id is stale. |
| | `{"type":"scroll","dir":"up"}` scrolls the Claude app's list a page: `up` loads older messages, `down` newer (409 "Already at the end" when there are none). `"dir":"latest"` pages forward until the list stops and answers `{pages}`. |
| | `{"type":"back"}` presses Back while the Claude app is in front. |
| | `{"type":"send","text":"…"}` types the text into the open session's message box and taps Send. 409 off a session; 502 if no Send button shows up within 3 s (the text is then left in the box). |
| `POST /voice/start` | Starts turning a recording into text with the phone's speech recognizer (on-device when it has one) and answers `{id}`. One at a time: a new one cancels the last. 503 with no recognizer. |
| `POST /voice/audio?id=…` | Body: the next piece of the recording, raw PCM, 16 kHz signed 16-bit little-endian mono (`Content-Type: application/octet-stream`). Send it as it's recorded: the recognizer drops audio that comes much faster than real time, so the bridge feeds it at most twice real time. 413 past 60 s in all (the recording is then cancelled), 410 for a recording that's over. |
| `POST /voice/finish?id=…` | The recording has ended: answers `{text}`, `""` when it heard no words. The recognizer has kept up, so this is quick. |
| `POST /voice/cancel?id=…` | Throws the recording away. |
| `POST /voice/transcribe` | For testing over adb: a whole recording in one body (at most 60 s), answered with `{text}` after half its length. |
| `GET /dump` | The last Claude screen as `adb shell uiautomator dump`-format XML, for test fixtures. |

## State

```jsonc
{
  "ok": true,
  "version": 12,            // goes up on every change
  "foreground": true,       // false: the Claude app isn't on screen; the rest is the last thing seen
  "screen": "transcript",   // sessions | transcript | prompt | unknown
  "title": "Fix login redirect",
  "sessions": [{"id": "1x2y3z", "label": "Fix login redirect", "detail": "my-app · laptop · 2m ago"}],
  "lines": ["…"],           // transcript: stitched history, oldest first; unknown: all visible text
  "prompt": {"question": "Bash\nnpm test", "options": [{"id": "…", "label": "Allow", "detail": null}]}
}
```

## Errors

Errors come back as `{"ok": false, "error": "…"}`:

| status | meaning |
| --- | --- |
| `400` | Bad JSON, an unknown action, a missing id, or nothing to send. |
| `401` | Wrong or missing token. |
| `404` | Unknown route. |
| `409` | The Claude app isn't in front, there's nothing to scroll, or nothing has been captured yet. |
| `410` | The option tapped is no longer on screen, or the recording is over. |
| `413` | The recording is over 60 s. |
| `502` | The tap, Back, typing or Send didn't go through, or speech recognition failed. |
| `503` | This phone has no speech recognizer. |
