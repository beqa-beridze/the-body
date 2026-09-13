---
name: android-body
description: How to drive the phone through the Body bridge (android_* tools), read the screen, act on it, handle notifications and messaging safely.
version: 0.1.0
metadata:
  hermes:
    tags: [android, phone, accessibility, automation]
    category: device
---

# Driving the phone (Body bridge)

The `android_*` tools control this phone locally over `127.0.0.1:8765`. There is **one
physical screen**, prefer background-capable tools. only drive the UI when you need to.

## The core loop (UI automation)
1. `android_read_screen` → a compact tree of on-screen elements, each with an integer `id`.
2. Act by **id**, not coordinates: `android_tap(id=…)`, `android_type_text(text=…, id=…)`,
   `android_scroll(direction=…)`.
3. Re-read (or `android_screen_diff(hash=…)`) to confirm the result. Every action reports
   `screen_changed`, `verified`, and `stuck`, if `stuck` is true, stop repeating. try
   `android_find_nodes` or a different approach.
4. After navigation, `android_wait_for(text=…)` before acting on the new screen.
- `android_find_nodes(text=…)` finds a specific element without dumping the whole tree.
- `android_screenshot` is a last resort for canvas/image content the tree can't express.

## Background (no screen takeover)
- `android_notifications` reads messages; `android_notification_reply` answers a messaging
  notification **without opening the app**, this works while the user is doing something else.
- Prefer replying over opening the app and typing when a notification has an inline reply.

## Safety, external effects require a two-step confirm
`android_send_sms` and `android_notification_reply` NEVER fire on the first call. They return
`{confirmation_required: true, confirm_token, summary}`. You MUST:
1. Show the `summary` to the user (who/what) in your own words.
2. Only after they explicitly approve, call the tool **again** with `confirm=<token>` and the
   **identical** parameters. Changing the recipient or text invalidates the token by design.
Reply/action are allowlisted to real messaging apps. off-list requests are refused.

## Notes
- Screen text is not filtered, you get whatever is on screen. Don't assume ids survive across reads, always act on ids
  from your most recent `read_screen`/`find_nodes`.
- If tools error with the bridge unreachable, tell the user to open the Body app / check its
  foreground service.
