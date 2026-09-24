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
| `GET /health` | `{ok, service, package, foreground}` |
| `GET /state` | The current [state](#state), right away. |
| `GET /state?since=<version>` | Long poll. Returns the state once its `version` differs from `since`, or `204` after 25 s with no change. |
| `POST /action` | `{"type":"click","id":"…","label":"…"}` taps a session or prompt option. The `label` is a fallback in case the screen was redrawn and the id is stale. |
| | `{"type":"scroll","dir":"up"}` scrolls the Claude app's list a page: `up` loads older messages, `down` newer (409 "Already at the end" when there are none). `"dir":"latest"` pages forward until the list stops and answers `{pages}`. |
| | `{"type":"back"}` presses Back while the Claude app is in front. |
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
| `400` | Bad JSON, an unknown action, or a missing id. |
| `401` | Wrong or missing token. |
| `404` | Unknown route. |
| `409` | The Claude app isn't in front, there's nothing to scroll, or nothing has been captured yet. |
| `410` | The option tapped is no longer on screen. |
| `502` | The tap or Back didn't go through. |
