# Claude G2

Claude Code Remote Control sessions on Even Realities G2 glasses, with no computer on your side.
It mirrors the **Claude Android app**: whatever the app shows (the session list, a live
transcript, a permission or question prompt) is shown on the glasses. Picking a prompt option on
the glasses taps it in the app, and you can dictate messages to a session by voice.

```
Claude app  ←reads / taps←  Claude G2 Bridge (accessibility service)  ←HTTP on 127.0.0.1:8421←
                                                                         Claude G2 glasses app (Even app WebView)  →BLE→  G2
```

**Why a mirror?** Remote Control has no public API. The endpoints the Claude apps use need your
claude.ai login token, and Anthropic's terms don't let third-party apps handle it
(code.claude.com/docs/en/legal-and-compliance). The mirror never touches credentials: the Claude
app stays signed in, and this only reads its screen.

## On the glasses

| where | scroll | tap | double-tap | hold |
| --- | --- | --- | --- | --- |
| Session list | move the highlight | open that session | close the app | |
| Transcript | one line back / forward (past what's loaded, scrolls the phone to load older or newer messages) | jump to the newest message, scrolling the phone there too | back to the session list | **dictate a message** (see below) |
| Voice card | | **send the text to the session** | cancel, nothing is sent | record again |
| Prompt (permission or question) | move through the options | **pick the highlighted option** | nothing, so a stray double-tap can't dismiss a prompt | |
| Error screen | | retry | close the app | |

## Voice dictation

In a session, send Claude a message without touching the phone:

1. **Hold** the touchpad and speak. A card opens over the lower part of the screen with the
   recording time; the session stays visible, dimmed, above it.
2. **Let go** when you're done. The card shows what you said.
3. **Tap** to send it to the session, or **double-tap** to throw it away. Hold again to record
   it over.

- A recording can be up to **60 seconds**; it stops by itself at the limit.
- Recording carries on for a moment after you let go, so the last word isn't cut off.
- If nothing was heard, the card says *Didn't catch that*: hold to try again, or double-tap to
  close.
- Dictation only works inside a session, where the Claude app has a message box.

The glasses' microphones do the recording, and the phone's own speech recognizer turns it into
text as you speak: Android's on-device one when the phone has it, so the audio stays on the phone.
Nothing is sent until you tap.

## Locking the phone screen

The bridge keeps the phone awake while the Claude app is on screen, so in a pocket it can take
stray touches. While the Claude app is in front, a small lock button sits in the top right
corner: **hold it** to lock the screen against touches, and hold it again to unlock. The glasses
keep working while it's locked, dictation included, and the keyboard stays hidden. The system's
own gestures (the navigation bar, the notification shade) can't be blocked.

## Setup (Android 13+ only)

1. Download `claude-g2-bridge-<version>.apk` from
   [Releases](https://github.com/pricem0595/claude-g2/releases) on the phone and open it. Allow
   installing from that app when Android asks.
2. Open **Claude G2 Bridge**. Tap *Open Accessibility settings* and turn on **Claude G2 mirror**.
   Android 13+ blocks sideloaded accessibility services at first. If it does, open app info,
   choose ⋮ → *Allow restricted settings*, and try again.
3. Get the **Claude G2** glasses app from the **Even Hub** in the Even app.
4. On the glasses app's phone page, enter the pairing token the bridge shows.
5. Open a Claude Code session in the Claude app and leave it on screen. The bridge keeps the
   screen on while it runs; you can turn that off.
6. Dictation needs no setup. Only if it reports a missing microphone permission, tap *Allow
   microphone* on the bridge's step 5 (the sound still comes from the glasses).

**Upgrading from a bridge you built yourself:** release builds are signed with the project's key,
so Android won't install one over a self-built copy. Uninstall the old bridge first, then repeat
steps 2 and 4.

## License

[PolyForm Noncommercial 1.0.0](LICENSE): free to use, change and share for any noncommercial
purpose; commercial use is not permitted. This is an independent project, not made by or
affiliated with Anthropic or Even Realities.
