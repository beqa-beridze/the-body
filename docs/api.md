# The bridge API

Everything the app exposes on `127.0.0.1:8765`, taken straight from the source. Version 1.0.7-askgate (versionCode 15). If something here disagrees with the code, the code wins and the doc is a bug.

---

## Every route at a glance

Full detail for each one is further down. Anything marked gated needs the two-step confirmation described in the confirmation gate section.

| Path | Input | Auth | Gated |
|---|---|---|---|
| `/`, [`/health`](#get-health-and-get--no-token-required) | none | **no** | no |
| [`/state`](#get-state) | none | yes | no |
| [`/displays`](#get-displays) | none | yes | no |
| [`/read_screen`](#get-read_screen) | query | yes | no |
| [`/find_nodes`](#get-find_nodes) | query | yes | no |
| [`/describe_node`](#get-describe_node) | query | yes | no |
| [`/screen_diff`](#get-screen_diff) | query | yes | no |
| [`/tap`](#get-tap) | query | yes | no |
| [`/long_press`](#get-long_press) | query | yes | no |
| [`/type_text`](#get-type_text) | query | yes | no |
| [`/scroll`](#get-scroll) | query | yes | no |
| [`/swipe`](#get-swipe) | query | yes | no |
| [`/press_key`](#get-press_key) | query | yes | no |
| [`/wait_for`](#get-wait_for) | query | yes | no |
| [`/notifications`](#get-notifications) | query | yes | no |
| [`/context`](#get-context) | none | yes | no |
| [`/media`](#get-media) | none | yes | no |
| [`/sensors`](#get-sensors) | query | yes | no |
| [`/selfcheck/media`](#get-selfcheckmedia) | none | yes | no |
| [`/selfcheck/reply`](#post-selfcheckreply) | body | yes | no |
| [`/ask`](#post-ask-the-phone-rule-alarm) | body | yes | own limits |
| [`/sms/send`](#post-smssend) | body | yes | **yes** |
| [`/notifications/reply`](#post-notificationsreply) | body | yes | **yes**, plus allowlist |
| [`/notifications/action`](#post-notificationsaction) | body | yes | **yes**, plus allowlist |
| [`/notifications/dismiss`](#post-notificationsdismiss) | body | yes | no |
| [`/notifications/snooze`](#post-notificationssnooze) | body | yes | no |
| [`/launch`](#post-launch) | body | yes | no |
| [`/list_apps`](#get-list_apps) | query | yes | no |
| [`/current_app`](#get-current_app) | none | yes | no |

---


---


## Transport

| Property | Value | Source |
|---|---|---|
| Bind address | `127.0.0.1` | `BridgeHttpServer.BIND_ADDR` |
| Port | `8765` | `BridgeHttpServer.PORT` |
| Server | NanoHTTPD 2.3.1 (vendored `libs/nanohttpd-2.3.1.jar`) | |
| Response content type | `application/json` (always) | `reply()` |

Base URL: `http://127.0.0.1:8765`

### Request pipeline, in order

1. **Remote-IP check.** If `session.remoteIpAddress` is not `"127.0.0.1"` and not `"::1"`, you get **HTTP 403** `{"ok":false,"error":"forbidden"}`.
2. **Path normalisation.** A trailing `/` is trimmed when the path is longer than one character, so `/state/` becomes `/state`.
3. **`/` and `/health` short-circuit here**, before the rate limiter and before auth.
4. **Rate limiter.** `rateLimiter.isBlocked(ip)` gives **HTTP 429** `{"ok":false,"error":"rate_limited"}`.
5. **Auth.** See the authentication section. Failure gives **HTTP 401** `{"ok":false,"error":"unauthorized"}`.
6. **Route dispatch.** An unknown path gives **HTTP 404** `{"ok":false,"error":"not_found"}`.
7. Any uncaught exception anywhere gives **HTTP 500** `{"ok":false,"error":"server_error"}`.

### The HTTP status code is almost always 200

Those five strings (`forbidden`, `rate_limited`, `unauthorized`, `not_found`, `server_error`) are the **only** errors that carry a non-200 status. **Every handler-level failure is returned with HTTP 200 and `"ok": false`**, including `service_not_running`, `listener_not_connected`, `not_allowlisted`, `node_stale`, and the entire confirmation-gate flow. A client that branches on HTTP status will treat a refused SMS as a success. **Branch on the `ok` field, not on the status code.**

### HTTP method is never checked

The router dispatches purely on path. `GET`, `POST`, `PUT` and `DELETE` all reach the same handler. Below, "query" versus "body" describes *where the handler looks for values*, not a method restriction.

- **Query-parameter routes** (`qStr`, `qInt`, `qBool`, `qStrOrNull`, `qIntOrNull`) read only `session.parameters`, which is the URL query string. A JSON body sent to these routes is ignored entirely.
- **Payload routes** (`payloadOf`) build one `JSONObject` by parsing a POST body **and then merging the query string** (`s.parameters.forEach { if (!o.has(k)) o.put(k, v[0]) }`, so the body wins on conflict). **Every "POST" route below therefore also works as a plain GET with query parameters.** The shipped `client/body` CLI relies on exactly that.

### Body-parsing details a caller will get wrong

- `payloadOf` only attempts body parsing when the method is `POST` or `PUT`.
- For **POST**, NanoHTTPD 2.3.1 hands the raw body back under the key `"postData"` for any content type other than `application/x-www-form-urlencoded` and `multipart/form-data`. So `Content-Type: application/json` works, and so does sending no content type at all.
- An `application/x-www-form-urlencoded` POST body is decoded into `session.parameters` instead, which the merge step still picks up, so form-encoded bodies also work. Every value then arrives as a **string**.
- **PUT bodies are silently dropped.** NanoHTTPD 2.3.1 writes a PUT body to a temp file under the key `"content"`, not `"postData"`. `payloadOf` looks only for `"postData"`, finds nothing, and the fallback (`parameters.keys.firstOrNull { it.trimStart().startsWith("{") }`) never matches. A `PUT` with a JSON body degrades to query-params-only, with no error.
- **Charset trap.** NanoHTTPD 2.3.1 decodes a non-multipart body with `ContentType.getEncoding()`, which defaults to **`US-ASCII`** when the `Content-Type` header carries no `charset`. Sending `Content-Type: application/json` with non-ASCII text (Georgian, Hebrew, emoji, accents) mangles it before the handler sees it. **Always send `Content-Type: application/json; charset=UTF-8`.** The bundled `client/body` CLI does *not*, it sends bare `application/json`.

### Query-parameter coercion rules

| Helper | Behaviour |
|---|---|
| `qStrOrNull(k)` | first value of `k`, and **an empty value counts as absent** |
| `qStr(k, d)` | that, else default `d` |
| `qInt(k, d)` | `toIntOrNull()`, else default `d`, so a non-numeric value silently becomes the default |
| `qIntOrNull(k)` | `toIntOrNull()`, else `null` |
| `qBool(k, d)` | `true` **only** for the literal `"1"` or `"true"` (case-insensitive). Any other present value, such as `"0"`, `"false"`, `"yes"` or `"on"`, is `false`. Absent gives `d`. |

`qBool` matters for `clear` on `/type_text` and `battery` on `/sensors`, which default to `true`. Pass `clear=0` to disable.

---

## Authentication

**Header:** `Authorization: Bearer <token>`

Checked as:

```kotlin
val auth = session.headers["authorization"]
if (auth != null && auth.startsWith("Bearer ") &&
    tokens.validate(auth.substring("Bearer ".length).trim()))
```

- The header name lookup is lowercase because NanoHTTPD lowercases header names, so any capitalisation of the header name works.
- The **`"Bearer "` prefix is case-sensitive**. `bearer <token>` fails. The trailing space is part of the prefix.
- The token itself is `.trim()`ed.

**The token** (`BridgeTokenStore`) is 24 random bytes from `SecureRandom`, encoded `Base64.URL_SAFE or NO_WRAP or NO_PADDING`, which is 32 characters. It is persisted with `commit()` in the app-private `SharedPreferences` file `"body"` under key `"bridge_token"`, created lazily on first service start. It is displayed, copyable and rotatable from the in-app **Bridge token** screen (`BridgeTokenActivity`). Validation is constant-time: both sides are zero-padded to `max(len_a, len_b, 64)` and compared with `MessageDigest.isEqual`, followed by an exact length equality check.

**Without a valid header:** `recordFailure(ip)` runs and you get **HTTP 401** `{"ok":false,"error":"unauthorized"}`, for every route except `/` and `/health`, *including unknown paths*. An unauthenticated request to a nonexistent path returns 401, never 404.

**With a valid header:** `recordSuccess(ip)` clears that IP's failure record entirely, then the route runs.

### Rate limiter (`AuthRateLimiter`)

Constructed with **all defaults** in `BodyForegroundService` (`AuthRateLimiter()`).

| Setting | Value |
|---|---|
| `maxFailures` | **5** |
| `windowMs` | **60_000** (1 minute rolling window) |
| `blockMs` | **300_000** (5 minutes) |
| Key | the remote IP |

Five failed auth attempts within any rolling 60 s window block that key for 5 minutes. While blocked, **every** authenticated route returns HTTP 429 `rate_limited` *before* auth is even examined, so you cannot unblock yourself by presenting the right token. The block self-resets once `blockMs` elapses, and the failure list is cleared with it. One success wipes the record.

Because the server only ever accepts `127.0.0.1` and `::1`, the key is effectively constant: **one misconfigured client locks out every local client for 5 minutes.**

`/` and `/health` are checked before the limiter and stay reachable throughout a block.

---

## GET /health, and GET / (no token required)

The only unauthenticated routes. `/` and `/health` return the identical body. It is always HTTP 200.

```json
{
  "ok": true,
  "app": "body",
  "version": "1.0.7-askgate",
  "milestone": "M8",
  "capabilities": {
    "accessibility": true,
    "notifications": true,
    "display_targeting": true,
    "sense_layer": true,
    "context_endpoint": true,
    "media_endpoint": true,
    "sensors_endpoint": true,
    "notification_extras": true,
    "notification_dismiss": true,
    "notification_snooze": true,
    "reply_action_index": true,
    "reply_selfcheck": true,
    "media_selfcheck": true,
    "ask_gate": true
  }
}
```

- `capabilities.accessibility` is `BodyAccessibilityService.isConnected()`, meaning `instance != null`.
- `capabilities.notifications` is `BodyNotificationListener.isConnected()`, meaning `connected && instance != null`.
- `capabilities.display_targeting` is `Build.VERSION.SDK_INT >= 30`. The code comment is explicit about why: on an older build `?display=N` was silently ignored and the gesture went to display 0 anyway. Callers driving a hidden display are expected to hard-fail when this is false.
- The remaining eleven flags are **hardcoded `true`** in this build. They are build-version advertisements, not live probes.

**`/health` takes no token and no parameters. Everything else on this server needs a token**, including `/state`, which is otherwise the most health-looking route in the API. `/health` tells you the bridge is alive and what it can do. It tells you nothing about whether your token is valid. To validate a token, call `/state`.

---

## GET /state

Query parameters: none. Auth: required.

```json
{
  "ok": true,
  "battery": 73,
  "charging": false,
  "screenOn": true,
  "uptimeMs": 123456789,
  "accessibility": true,
  "notifications": true
}
```

- `battery` is the percent from the sticky `ACTION_BATTERY_CHANGED` broadcast (`level * 100 / scale`), or **`-1`** when the broadcast is missing or `level`/`scale` are invalid.
- `charging` is true for `BATTERY_STATUS_CHARGING` **or** `BATTERY_STATUS_FULL`.
- `screenOn` is `PowerManager.isInteractive`.
- `uptimeMs` is `SystemClock.elapsedRealtime()`.

No error branch exists, so this route cannot return `ok:false`.

**Naming inconsistency:** this is the only route using camelCase (`screenOn`, `uptimeMs`). Everything else in the API is snake_case, and `/context` returns the same facts as `screen_on` and `uptime_ms`.

---

## GET /displays

Diagnostic. Query parameters: none. Auth: required.

Success:
```json
{
  "ok": true,
  "displays": [
    { "display": 0, "windows": 4, "packages": ["com.android.systemui", null] }
  ]
}
```

`packages` entries are `null` (JSON null) when a window's root package cannot be read.

Errors: `{"ok":false,"error":"service_not_running"}`

This is backed by `getWindowsOnAllDisplays()`, which is **API 30+**. On anything older `displaySummary()` returns an empty list, so you get `{"ok":true,"displays":[]}`, which is indistinguishable from "no displays". Check `capabilities.display_targeting` on `/health` first.

---

## GET /read_screen

Reads the live accessibility tree. **Query parameters.** Auth: required.

| Param | Type | Default | Meaning |
|---|---|---|---|
| `mode` | string | `"interactive"` | `"full"` gives a nested `tree`. **Any other value**, including a typo, gives a flat `elements` list of "interesting" nodes only. |
| `bounds` | bool | `false` | include a `bounds` array on each node |
| `system_ui` | bool | `false` | when false, every node whose package is `com.android.systemui` is pruned along with its subtree |
| `max` | int | `500` | emission budget, **coerced into 1-2000** |
| `display` | int | *(absent gives null)* | read this display's roots via `rootsForDisplay()`. Absent means the default-display `windows` list. |

Success (interactive mode):
```json
{
  "ok": true,
  "app": "com.whatsapp",
  "screen_hash": "1f3a9c2",
  "generation": 7,
  "elements": [
    { "id": "7-1", "role": "Button", "text": "Send", "rid": "com.whatsapp:id/send", "state": "cf", "bounds": [0,0,100,50] }
  ],
  "node_count": 42,
  "truncated": false
}
```

Success with `mode=full` uses an identical envelope but with `"tree"` instead of `"elements"`. **`tree` is not always an object of the same shape.** With exactly one root it is that root's node object directly, and with zero or several roots it is a wrapper `{"role":"windows","children":[ ... ]}`. Child nodes are nested under `"children"`, which is **omitted entirely** on leaf nodes.

- `app` is `svc.foregroundPackage`, the cached last `TYPE_WINDOW_STATE_CHANGED` package, or JSON `null`.
- `screen_hash` is a hex structural hash. See the screen hash section.
- `generation` is covered in the node ids section. **This call bumps it, invalidating every previously issued node id, for every client.**
- `node_count` is the number of nodes actually emitted, and `truncated` is `true` when the budget `max` cut the traversal short.

**Node object (compact form, used by `/read_screen` and `/find_nodes`).** Keys are **omitted when empty or false**, so no field except `id` is guaranteed.

| Key | Notes |
|---|---|
| `id` | `"<generation>-<label>"`, for example `"7-1"`. Always present. |
| `role` | short class name, the part after the last `.`, omitted if empty |
| `text` | `node.text` |
| `desc` | `node.contentDescription` |
| `rid` | full `viewIdResourceName` |
| `hint` | `node.hintText` |
| `state` | packed flag string, omitted if empty |
| `checked` | present **only** when the node is checkable |
| `bounds` | `[left, top, right, bottom]`, only with `bounds=1` |

**`state` flag characters** (`stateOf`): `c` clickable, `e` editable, `s` scrollable, `k` checkable, `f` focusable, `d` **disabled** (present when `isEnabled == false`), `x` selected, `p` longClickable.

**Pruning** (`isPruned`) drops a node *and its whole subtree* when it is systemui and `system_ui` is off, or `isVisibleToUser != true`, or its bounds have zero width or height.

**"Interesting"** (interactive mode only) means clickable, editable, checkable, scrollable, or carrying non-blank `text` or `contentDescription`. Interactive mode still *walks* every child so tree paths stay correct, but it only emits interesting ones.

Errors: `{"ok":false,"error":"service_not_running"}` when the accessibility service is not enabled or not connected.

---

## GET /find_nodes

**Query parameters.** Auth: required. Selectors are **ANDed**.

| Param | Type | Default | Meaning |
|---|---|---|---|
| `text` | string | *(null)* | match `node.text` |
| `desc` | string | *(null)* | match `contentDescription` |
| `rid` | string | *(null)* | matches the **full** `viewIdResourceName` **or** just the part after the last `/` |
| `class` | string | *(null)* | matches the **full** class name **or** the short class name |
| `clickable` | bool | `false` | when true, require `isClickable` |
| `exact` | bool | `false` | `false` is a case-insensitive `contains`, `true` is an exact `==` |
| `limit` | int | `20` | budget, **coerced into 1-2000** |
| `display` | int | *(null)* | same meaning as on `/read_screen` |

Success:
```json
{ "ok": true, "count": 3, "generation": 8, "matches": [ /* compact node objects */ ] }
```

Errors: `{"ok":false,"error":"service_not_running"}`

Things the code does that a reader would not assume:

- **Matches always carry `bounds`.** `findWalk` calls `compactNode(..., includeBounds = true)` unconditionally. There is no `bounds` parameter here.
- **There is no `truncated` field.** The budget silently stops the walk at `limit`. If `count == limit`, assume there may be more.
- **`system_ui` is not a parameter and cannot be enabled.** `findWalk` hardcodes `includeSystemUi = false`, so systemui nodes are unreachable via `/find_nodes` even though `/read_screen?system_ui=1` shows them.
- **`isInteresting` is not applied here.** With no selectors at all, every non-pruned node matches, so `/find_nodes` with no parameters returns the first `limit` *visible* nodes, including ones `/read_screen` would never emit.
- This call **bumps the generation** and clears the id map, exactly like `/read_screen`.

---

## GET /describe_node

Full property dump for one previously labelled node. **Query parameters.** Auth: required.

| Param | Type | Default | Meaning |
|---|---|---|---|
| `id` | string | `""` | node id, either `"G-N"` qualified or bare `"N"` |

Success:
```json
{
  "ok": true,
  "node": {
    "id": "7-3",
    "role": "EditText",
    "text": "",
    "desc": "",
    "viewId": "com.whatsapp:id/entry",
    "hint": "Message",
    "state": "cef",
    "enabled": true,
    "selected": false,
    "checked": false,
    "bounds": [12, 1800, 900, 1900],
    "childCount": 0,
    "actions": ["click", "focus", "set_text"]
  }
}
```

Unlike the compact node, **every key here is always present**, and missing strings are `""`. `id` echoes back exactly what you passed. Note `viewId` here versus `rid` in the compact node: same underlying value, different key name.

`actions` maps `AccessibilityNodeInfo` action ids to names: `click`, `long_click`, `focus`, `clear_focus`, `select`, `clear_selection`, `accessibility_focus`, `clear_accessibility_focus`, `scroll_forward`, `scroll_backward`, `copy`, `paste`, `cut`, `set_selection`, `expand`, `collapse`, `dismiss`, `set_text`, `show_on_screen`, `scroll_up`, `scroll_down`, `scroll_left`, `scroll_right`, `ime_enter`, `scroll_to_position`. Anything unrecognised is rendered as `"action_0x<hex>"`.

Errors:

| Body | When |
|---|---|
| `{"ok":false,"error":"service_not_running"}` | accessibility service down |
| `{"ok":false,"error":"bad_id","hint":"re-read_screen; id map is generation 0"}` | id is unparseable, including the default `""` |
| `{"ok":false,"error":"stale_generation","hint":"re-read_screen; id map is generation N"}` | qualified id whose `G` is not the current generation |
| `{"ok":false,"error":"node_stale","hint":"re-read_screen; id map is generation N"}` | label not in the map, or the node could not be re-located |

**Quirk:** on the `bad_id` path the hint is built with a hardcoded `0` (`staleError(0, "bad_id")`), so it says *"id map is generation 0"* regardless of the real generation. Do not parse the current generation out of that hint. It is only trustworthy on the `stale_generation` and `node_stale` paths.

`describeNode` re-locates via `locateByKey`, which does an exact StableKey search across all roots first and then the `navigateKey` tree-path fallback. That is one step more forgiving than the action routes, which use `navigateKey` only.

---

## GET /screen_diff

**Query parameters.** Auth: required.

| Param | Type | Default | Meaning |
|---|---|---|---|
| `hash` | string | `""` | a `screen_hash` from an earlier `/read_screen` or an action outcome |

Unchanged:
```json
{ "ok": true, "changed": false, "hash": "1f3a9c2" }
```

Changed:
```json
{
  "ok": true,
  "changed": true,
  "hash": "9b2e11",
  "app": "com.whatsapp",
  "added_labels": [],
  "removed_labels": []
}
```

Errors: `{"ok":false,"error":"service_not_running"}`

- **`added_labels` and `removed_labels` are always empty arrays.** They are `JSONArray()` literals, placeholders rather than computed values. Do not build logic on them.
- Omitting `hash` compares against `""`, which never matches, so you always get `changed: true`.
- The hash always covers the **default display** with systemui excluded (`currentHash()` uses `svc.allRoots()` and `includeSystemUi = false`). It ignores the `display` you may have been reading.
- This route does **not** bump the generation.

---

## Action-route common response shape

`/tap`, `/long_press`, `/type_text`, `/scroll`, `/swipe` and `/press_key` all return the `buildOutcome` envelope on success:

```json
{
  "ok": true,
  "action": "tap",
  "method": "node_click",
  "verified": true,
  "screen_changed": true,
  "hash_before": "1f3a9c2",
  "hash_after": "9b2e11",
  "stuck": false,
  "display": 0
}
```

plus per-route extras, plus a `hint` that appears only when `stuck` is true:

```
"loop detected: repeated action without progress; re-read the screen and try a different element or approach"
```

| Key | Meaning |
|---|---|
| `method` | which mechanism actually fired, with values listed per route |
| `verified` | per route. For the click-like and gesture routes it is just `hash_after != hash_before`, and for `/type_text` it is a text check. |
| `screen_changed` | `hash_after != hash_before` |
| `hash_before`, `hash_after` | structural hashes, where `hash_after` comes from `settle()` |
| `display` | echoed back so the caller can verify which screen was targeted |

**`settle()`** polls the hash every **100 ms** until two consecutive reads match or **500 ms** elapses, whichever comes first. A UI that takes longer than 500 ms to change will report `screen_changed: false` even though the action worked.

**`stuck` detection** (`recordAndCheckStuck`) keeps a **global** ring buffer of the last **6** outcomes across *all* action routes and *all* callers, and flags `stuck` when either:

1. the same `(action, targetKey)` pair appears **3 or more** times with no screen change, or
2. the last **4** entries contain **2 or fewer** distinct `hash_after` values.

Rule 2 fires on any legitimate A/B toggling, and the history is shared between concurrent clients, so treat `stuck` as a hint and never as an error.

**Failure shape**, also HTTP 200:
```json
{ "ok": false, "action": "tap", "error": "node_stale", "hint": "re-read_screen" }
```
`hint` is present only when the code supplies one.

**Cross-display verification is broken by design.** All hashing goes through `BodyScreenReader.currentHash()`, which always reads `svc.allRoots()`, the **default display**. Dispatching a gesture with `display=2` will report `verified` and `screen_changed` computed against display 0. On a secondary display, expect `screen_changed: false` even on a successful action.

---

## GET /tap

**Query parameters.** Auth: required.

| Param | Type | Default | Meaning |
|---|---|---|---|
| `id` | string | *(null)* | node id from `/read_screen` or `/find_nodes` |
| `x` | int | *(null)* | absolute X, requires `y` |
| `y` | int | *(null)* | absolute Y, requires `x` |
| `text` | string | *(null)* | fallback text lookup |
| `display` | int | **`0`** | which screen a fallback **gesture** targets |

**Target priority is strict and non-obvious.** `id` wins, else `x` **and** `y` together (both required), else `text`, else `no_target`. Passing `id` and `x`/`y` together silently ignores the coordinates.

The resolution ladder once a node is in hand: first `performAction(ACTION_CLICK)` on the node itself giving `method: "node_click"`, then the nearest ancestor with that action within **8 hops** giving `"parent_click"`, then a 50 ms point gesture at the node's bounds centre giving `"gesture_fallback"`. With bare coordinates the method is `"coordinate"`. The gesture await budget is **2000 ms**.

Errors:

- `service_not_running`
- `bad_id`, `stale_generation` and `node_stale`, all with `hint: "re-read_screen"`
- `no_match` with hint `no node matching "<text>"; re-read screen`
- `no_target` with hint `provide id, x/y coordinates, or fallbackText`
- `gesture_failed` with hint `dispatchGesture returned false or timed out`
- `exception`, whose hint is the exception message or class simple name

**`display` only affects the gesture fallback.** Node resolution uses `lastDisplayId`, the display the *current id map* was built from. Tapping a hidden-display node requires that the preceding `/read_screen` or `/find_nodes` used the same `?display=`.

**`text=` bumps the generation.** The fallback path calls `BodyScreenReader.findNodes(text = ..., limit = 1)` internally, which rebuilds the id map, so every id you were holding is invalid after a text-fallback tap.

`display` defaults to `0` rather than "whatever was last read". The comment in the router says so explicitly: a caller that never passes it behaves exactly as before, and callers driving a hidden display **must** pass it.

---

## GET /long_press

**Query parameters.** Auth: required.

| Param | Type | Default | Meaning |
|---|---|---|---|
| `id` | string | *(null)* | node id |
| `x` | int | *(null)* | absolute X |
| `y` | int | *(null)* | absolute Y |
| `duration` | int | **`600`** | press duration in ms, **coerced to at least 100** |
| `display` | int | **`0`** | gesture target display |

Identical machinery to `/tap` but with `ACTION_LONG_CLICK` and a 3000 ms gesture await. `method` is `"node_long_click"`, `"parent_click"`, `"gesture_fallback"` or `"coordinate"`, and `action` in the response is `"long_press"`.

**There is no `text` fallback here.** `clickLike` is called with `fallbackText = null`, so passing `?text=` does nothing, and without `id` or `x`/`y` you get `no_target`.

Errors: the same set as `/tap` minus `no_match`.

---

## GET /type_text

**Query parameters.** Auth: required.

| Param | Type | Default | Meaning |
|---|---|---|---|
| `text` | string | `""` | text to set |
| `id` | string | *(null)* | target node. Absent means the currently input-focused node. |
| `clear` | bool | **`true`** | `true` replaces the field, `false` **appends** (`existing + text`) |
| `submit` | bool | `false` | after setting, try `ACTION_IME_ENTER` |

Success adds to the standard envelope: `method: "set_text"`, `field_text_after` (the field's text re-read after `node.refresh()`), and, **only when `submit` was requested**, `submitted` (a boolean saying whether IME_ENTER was available and succeeded).

```json
{ "ok": true, "action": "type_text", "method": "set_text", "verified": true,
  "screen_changed": true, "hash_before": "...", "hash_after": "...", "stuck": false,
  "display": 0, "field_text_after": "hello", "submitted": true }
```

`verified` here is `fieldAfter.contains(text)`, not a hash comparison.

Errors:

- `service_not_running`
- `bad_id`, `stale_generation` and `node_stale`, with hint `re-read_screen`
- `no_focused_field` with hint `no input-focused node; tap the text field first or pass id`
- `set_text_failed` with hint `node may not be editable; re-read screen and target an EditText`
- `exception`

Gotchas:

- **There is no `display` parameter.** The response always reports `"display": 0`.
- `clear` defaults to `true`. Remember `qBool` semantics and use `clear=0`, not `clear=no`.
- `text=""` with `clear=true` clears the field, and `verified` is then **always `true`** because `"".contains("")`.
- `ACTION_FOCUS` is attempted first and its failure is swallowed.

---

## GET /scroll

**Query parameters.** Auth: required.

| Param | Type | Default | Meaning |
|---|---|---|---|
| `direction` | string | **`"down"`** | `up`, `down`, `left` or `right`, lowercased before checking |
| `id` | string | *(null)* | scroll this node, or its nearest scrollable ancestor |
| `distance` | string | `"medium"` | gesture-fallback distance |
| `display` | int | **`0`** | gesture target display |

Success extras: `direction` (the lowercased value) and `scroll_progressed` (identical to `screen_changed`). `method` is `"node_scroll"` or `"gesture_fallback"`.

Errors:

- `service_not_running`
- `unknown_direction` with hint `use up/down/left/right`
- `bad_id`, `stale_generation` and `node_stale`, with hint `re-read_screen`
- `gesture_failed` with hint `dispatchGesture returned false or timed out`
- `exception`

Semantics worth stating plainly:

- `direction` is the **content** direction. With `id`, `down` and `right` map to `ACTION_SCROLL_FORWARD` while `up` and `left` map to `ACTION_SCROLL_BACKWARD`. In the gesture fallback the finger travels the **opposite** way (`oppositeDirection(dir)`).
- The ancestor walk here allows `hops <= 8`, which is nine levels including the node itself, one more than `/tap`'s `hops < 8`.
- Without `id`, the gesture uses the whole-screen bounds.

**`distance` accepted values** (`directionalGesture`): `"short"` is **0.3**, `"long"` is **0.9**, and **anything else**, including `"medium"`, a typo, or an empty value, is **0.6**. The stroke spans that fraction of the relevant dimension, centred, clamped to an 8 percent margin inside the bounds, over a **300 ms** stroke with a **3000 ms** await.

---

## GET /swipe

**Query parameters.** Auth: required.

| Param | Type | Default | Meaning |
|---|---|---|---|
| `direction` | string | **`"up"`** | `up`, `down`, `left` or `right` |
| `distance` | string | `"medium"` | `short` is 0.3, `long` is 0.9, anything else is 0.6 |
| `display` | int | **`0`** | gesture target display |

Success extras: `direction` and `distance`, echoed **raw**, so `distance: "banana"` comes back as `"banana"` after being treated as medium. `method` is always `"gesture"`.

Errors:

- `service_not_running`
- `unknown_direction` with hint `use up/down/left/right`
- `gesture_failed` with hint `dispatchGesture returned false or timed out`
- `exception`

**`/swipe` and `/scroll` use opposite conventions for the same word.** `/swipe?direction=down` moves the **finger** down (`directionalGesture(svc, bounds, dir, ...)`), which scrolls content *up*, while `/scroll?direction=down` scrolls **content** down by moving the finger up. `/swipe` always swipes the full screen bounds and has no `id`.

---

## GET /press_key

**Query parameters.** Auth: required.

| Param | Type | Default | Meaning |
|---|---|---|---|
| `key` | string | `""` | global action name, lowercased before matching |

Accepted values, exactly: **`back`, `home`, `recents`, `notifications`, `quick_settings`, `lock_screen`**.

Success extras: `key`, lowercased. `method` is `"global_action"`. The envelope always reports `"display": 0` because global actions are not display-targetable.

Errors:

- `service_not_running`
- `unknown_key` with hint `use back/home/recents/notifications/quick_settings/lock_screen`
- `global_action_failed` with hint `performGlobalAction returned false`
- `exception`

---

## GET /wait_for

Polls until a condition holds. **Query parameters.** Auth: required.

| Param | Type | Default | Meaning |
|---|---|---|---|
| `text` | string | *(null)* | node text must match |
| `rid` | string | *(null)* | resource id must match |
| `app` | string | *(null)* | foreground package must equal, or case-insensitively contain, this |
| `gone` | bool | `false` | invert, so it waits for the condition to be **absent** |
| `timeout` | int | **`5000`** | milliseconds, **not clamped** |

Success:
```json
{ "ok": true, "action": "wait_for", "found": true, "waited_ms": 812, "screen_hash": "9b2e11" }
```

**`found` does not mean "present".** It is `present != gone`, so with `gone=1`, `found: true` means the thing has *disappeared*. And `found: false` is the **timeout** result, which is still `ok: true` rather than an error.

Errors:

- `service_not_running`
- `no_condition` with hint `provide text, resourceId, or app`
- `exception`

Details:

- All supplied conditions are ANDed. `text` and `rid` are evaluated together in one `findNodes(text, resourceId, limit = 1)` call, and `app` is checked separately against `foregroundPackage`.
- The loop checks the condition **before** the timeout test and sleeps 100 ms between polls, so `timeout=0` still performs exactly one check.
- **This route bumps the generation on every poll iteration** whenever `text` or `rid` is supplied, because `conditionPresent` calls `findNodes`. A five-second wait can bump the generation around 50 times. **Any node id you held before calling `/wait_for` with `text` or `rid` is dead afterwards.** Waiting on `app` alone does not touch the id map.
- Note the parameter is `rid` on the wire but `resourceId` in the error hint text.

---

## GET /notifications

**Query parameters.** Auth: required.

| Param | Type | Default | Meaning |
|---|---|---|---|
| `limit` | int | **`50`** | max notifications returned, **not clamped** |
| `pkg` | string | *(null)* | case-insensitive **substring** filter on the package name |

Success:
```json
{ "ok": true, "count": 12, "total": 31, "notifications": [ /* ... */ ] }
```

`total` counts everything matching `pkg`, and `count` is how many were serialised into the array. `total > count` means `limit` truncated. Results are sorted by `postTime` **descending**, newest first, and if sorting throws, the raw order is used.

Errors:

- `{"ok":false,"error":"listener_not_connected"}`
- `{"ok":false,"error":"read_failed: <ExceptionSimpleName>"}`, where the error *string itself* embeds the exception class name, so it is not a fixed token

### Notification object

Every field is individually try/caught and **omitted on failure or when absent**, so assume nothing is guaranteed.

Identity and status: `key` (the opaque string every write route needs), `id` (int), `tag`, `pkg`, `app` (human label via PackageManager, falling back to the package name), `post_time`, `clearable`, `ongoing`, `group_key`, `is_group`.

From the `Notification`: `category`, `flags`, `is_group_summary`, `number` (only when it is not 0), `when` (only when it is not 0), `channel_id`, `group`, `sort_key`, `shortcut_id`, `has_content_intent`.

From extras, each only when non-blank: `title`, `title_big`, `text`, `big_text`, `sub_text`, `info_text`, `summary_text`, `conversation_title`, `self_display_name`, `template`, plus `is_group_conversation` and `show_when` when those keys exist, and `text_lines` (an array of non-blank strings).

`people` is an array of `{ "name", "uri", "key", "is_bot", "is_important" }`. It is **API 28+ only** and returns null below that.

`messages` and `historic_messages` (MessagingStyle) are arrays of `{ "text", "timestamp", "sender", "sender_uri", "sender_is_bot", "data_mime_type" }`. When a `senderPerson` exists, its name **overwrites** `sender`.

`actions` is an array, present only when at least one action serialised:
```json
{
  "index": 0,
  "title": "Reply",
  "semantic_action": 1,
  "allow_generated_replies": true,
  "contextual": false,
  "has_intent": true,
  "has_remote_input": true,
  "remote_inputs": [
    { "result_key": "key_text_reply", "label": "Reply", "allows_free_form_input": true,
      "edit_choices_before_sending": 0, "data_only": false, "choices": ["Yes","No"] }
  ]
}
```
`index` is the **stable index into `notification.actions`** and is exactly what `action_index` addresses on the write routes. `remote_inputs` is present only when non-empty. Null actions are skipped **without consuming an index**, so `actions[i].index` is authoritative and may skip numbers. Never use the array position.

`reply_action_index` is the index of the first action carrying any RemoteInput, the one `/notifications/reply` would pick by default. It is present only when such an action exists.

`can_reply` is a boolean, always emitted when the notification parsed, including `false` when there are no actions at all.

`ranking`, present when the RankingMap has an entry for the key, holds `rank`, `importance`, `importance_name`, `ambient`, `matches_interruption_filter`, `can_bubble`, `can_show_badge`, `suspended`, `last_audibly_alerted_ms`, `user_sentiment`, `is_conversation`, `channel_id`, `channel_name`, `channel_importance` and `smart_replies`.

`importance_name` mapping: `0 NONE`, `1 MIN`, `2 LOW`, `3 DEFAULT`, `4 HIGH`, `5 MAX`, and anything else `UNSPECIFIED`.

Three ranking fields are **also promoted to the top level**: `is_conversation`, `importance` and `channel_name`. Note that `channel_id` appears at top level from the `Notification` and again inside `ranking` from the channel object, and in principle they can differ.

**There is deliberately no `dismissible` field.** The source states it outright: `clearable` (`StatusBarNotification.isClearable`) *is* the "can I dismiss this" answer, and no duplicate was added.

---

## GET /context

The M8 sense layer. **No parameters.** Auth: required. Every field is independently try/caught so a single OEM quirk cannot blank the reply, and `ok` is **always `true`**. Failures surface as `*_error` keys.

```json
{
  "ok": true,
  "version": "1.0.7-askgate",
  "sdk_int": 36,
  "now_ms": 1757990000000,
  "uptime_ms": 987654321,
  "accessibility": true,
  "notifications": true,
  "foreground_app": "com.whatsapp",
  "active_window_app": "com.whatsapp",
  "foreground_agrees": true,
  "active_window_root": "com.whatsapp",
  "screen_on": true,
  "power_save_mode": false,
  "device_idle_mode": false,
  "thermal_status": 0,
  "thermal_status_name": "NONE",
  "display_state": 2,
  "display_state_name": "ON",
  "keyguard_locked": false,
  "keyguard_secure": true,
  "device_locked": false,
  "device_secure": true,
  "battery": { }
}
```

- `foreground_app` is the **cached** last `TYPE_WINDOW_STATE_CHANGED` package, which may be stale after an LMK kill or a missed event. `active_window_app` is read **live** from the window list at request time, though it can legitimately be an IME or an overlay. `foreground_agrees` is `cached != null && cached == live`. Both are reported precisely so a caller can see disagreement rather than trusting one. All three of `foreground_app`, `active_window_app` and `active_window_root` can be JSON `null`.
- `accessibility` here is `svc != null` directly rather than `isConnected()`, which is the same thing in practice.
- `thermal_status` and `thermal_status_name` exist **only on API 29+**. Names: `NONE`, `LIGHT`, `MODERATE`, `SEVERE`, `CRITICAL`, `EMERGENCY`, `SHUTDOWN`, `UNKNOWN`.
- `display_state_name` is one of `OFF`, `ON`, `DOZE`, `DOZE_SUSPEND`, `ON_SUSPEND`, `VR`, `UNKNOWN`. On failure `display_state` is `-1` and the `_name` key is absent.
- On failure the power block emits `power_error` and the keyguard block emits `keyguard_error`, each holding an exception class simple name, and their normal fields are missing.

**`battery` object**, also reused by `/sensors`: `percent` (`-1` if unknown), `status`, `status_name` (`CHARGING`, `DISCHARGING`, `FULL`, `NOT_CHARGING` or `UNKNOWN`), `charging` (CHARGING **or** FULL), `plugged`, `plugged_name` (`NONE`, `AC`, `USB`, `WIRELESS` or `OTHER`), `health`, `health_name` (`GOOD`, `OVERHEAT`, `DEAD`, `OVER_VOLTAGE`, `COLD`, `FAILURE` or `UNKNOWN`), `temperature_c` (**already divided by 10**, since the framework reports tenths of a degree), `voltage_mv` (only when it is 0 or more), `technology`, `present`, `current_now_ua` (microamps, where negative means discharging on most devices), `capacity_pct`, `charge_counter_uah`, and `error` (an exception class name) if the sticky broadcast read failed.

This deliberately uses **no new permission**. `foreground_app` comes from the accessibility service, not `UsageStatsManager`, because `PACKAGE_USAGE_STATS` is not declared in the manifest.

---

## GET /media

**No parameters.** Auth: required.

```json
{
  "ok": true,
  "count": 2,
  "now_playing": "com.spotify.music",
  "sessions": [
    {
      "pkg": "com.spotify.music",
      "app": "Spotify",
      "title": "…", "artist": "…", "album": "…",
      "album_artist": "…", "display_title": "…", "display_subtitle": "…",
      "duration_ms": 214000,
      "playback_state": 3,
      "playback_state_name": "PLAYING",
      "playing": true,
      "position_ms": 45000,
      "speed": 1.0,
      "actions_bitmask": 3639,
      "volume": 11,
      "volume_max": 15
    }
  ]
}
```

- `now_playing` is the package of the **first** session whose state is `STATE_PLAYING`, else JSON `null`.
- Metadata string keys are omitted when null or blank, and `duration_ms` appears only when it is greater than 0.
- If `playbackState` is null or throws, the session object carries just `"playing": false` and none of the other playback keys.
- `volume` and `volume_max` are omitted if `playbackInfo` is unavailable.
- `playback_state_name` is one of `NONE`, `STOPPED`, `PAUSED`, `PLAYING`, `FAST_FORWARDING`, `REWINDING`, `BUFFERING`, `ERROR`, `CONNECTING`, `SKIPPING_TO_PREVIOUS`, `SKIPPING_TO_NEXT`, `SKIPPING_TO_QUEUE_ITEM`, `UNKNOWN`.

Errors:

| Body | When |
|---|---|
| `{"ok":false,"error":"no_media_session_service"}` | `MEDIA_SESSION_SERVICE` unavailable |
| `{"ok":false,"error":"not_enabled_listener","detail":"…","component":"…"}` | `SecurityException` from `getActiveSessions`, meaning **the notification listener is disabled** |
| `{"ok":false,"error":"media_read_failed: <ExceptionSimpleName>"}` | anything else |

`getActiveSessions()` normally requires the privileged `MEDIA_CONTENT_CONTROL`, and this app gets in through the documented escape hatch of passing its own enabled notification-listener `ComponentName`. **`/media` therefore depends on the notification listener grant, not on anything media-specific.** `detail` carries the `SecurityException` message and `component` the flattened component name, deliberately, rather than swallowing the failure.

---

## GET /sensors

**Query parameters.** Auth: required. **One parameter switches between two entirely different response shapes.**

| Param | Type | Default | Meaning |
|---|---|---|---|
| `list` | bool | `false` | `true` gives inventory mode below, false gives poll mode |
| `timeout` | int | **`1500`** | poll: ms to wait for first samples, **coerced into 100-5000** |
| `battery` | bool | **`true`** | poll: include the `battery` object |
| `rate_us` | int | **`2`** (`SensorManager.SENSOR_DELAY_UI`) | poll: sampling rate passed to `registerListener` |

### list=1, inventory

```json
{ "ok": true, "count": 31,
  "sensors": [ { "type": 5, "name": "…", "vendor": "…", "power_ma": 0.13,
                 "max_range": 10000.0, "resolution": 1.0, "wake_up": false } ] }
```
This covers **every** sensor (`Sensor.TYPE_ALL`). Errors: `{"ok":false,"error":"no_sensor_service"}` and `{"ok":false,"error":"list_failed: <ExceptionSimpleName>"}`.

### Poll, the default

```json
{
  "ok": true,
  "timeout_ms": 1500,
  "rate_us": 2,
  "battery": { },
  "sensors": [
    { "type": 5, "kind": "light", "name": "…", "power_ma": 0.13,
      "values": [312.0], "value": 312.0, "unit": "lux", "accuracy": 3,
      "meaning": "normal indoor" },
    { "type": 4, "kind": "gyroscope", "name": "…", "power_ma": 0.5,
      "value": null, "timed_out": true }
  ],
  "sampled": 5
}
```

- `timeout_ms` echoes the **clamped** value, not what you sent.
- A sensor that produced a sample carries `values` (the full float array), `value` (`values[0]`), `unit`, `accuracy` when known, and, for three types only, `meaning`.
- A sensor that produced nothing carries `"value": null` plus **either** `"timed_out": true` **or** `"register_rejected": true`, the latter meaning the framework refused the registration. There is no `values` key in that case.
- `sampled` is how many sensors returned data.
- `"warning": "no_sensor_service"` (with `sensors: []`) or `"warning": "poll_failed: <ExceptionSimpleName>"` may appear. **`ok` stays `true` in every poll outcome**, so this route effectively cannot return `ok:false`.

**Poll mode only ever touches eight fixed sensor types**, regardless of what `list=1` reports: `LIGHT`, `PROXIMITY`, `ACCELEROMETER`, `GYROSCOPE`, `MAGNETIC_FIELD`, `PRESSURE`, `AMBIENT_TEMPERATURE` and `RELATIVE_HUMIDITY`. There is no way to request a different one. The step counter is excluded on purpose because it needs the `ACTIVITY_RECOGNITION` permission.

`kind` values: `light`, `proximity`, `accelerometer`, `gyroscope`, `magnetometer`, `pressure`, `ambient_temperature`, `humidity`, else `"type_<N>"`.

`unit` values: `lux`, `cm`, `m/s^2`, `rad/s`, `uT`, `hPa`, `C`, `%`, else `""`.

`meaning` strings, verbatim:

- light: `"dark (in a pocket or face down)"` below 1, `"dim indoor"` below 50, `"normal indoor"` below 500, `"bright indoor / overcast outdoors"` below 5000, and `"direct daylight"` above that
- proximity: `"something is close to the screen"` below 5, otherwise `"clear"`
- accelerometer: `"lying face up"`, `"lying face down"`, `"upright, portrait"`, `"upside down, portrait"`, `"landscape, rotated left"`, `"landscape, rotated right"`, `"tilted or moving"`

**This is a one-shot poll, never a stream.** Listeners are registered, awaited, then unregistered in a `finally` with the HandlerThread quit, so nothing survives the request and a caller wanting a time series must poll repeatedly. Note that the request **blocks a NanoHTTPD worker thread** for up to `timeout_ms`.

**Do not set `rate_us=0`.** The source documents a verified device bug: at `SENSOR_DELAY_FASTEST` (0 us) the accelerometer, gyroscope and magnetometer never register at all, and `dumpsys` shows `result=BAD_VALUE`, because an app targeting API 31+ without `HIGH_SAMPLING_RATE_SENSORS` cannot exceed 200 Hz. Light, proximity and pressure are unaffected, which makes the failure look like "some sensors are just slow". The default of `2`, around 16 Hz, registers fine.

---

## GET /selfcheck/media

A known-answer test for `/media`. **No parameters.** Auth: required.

It publishes a real `MediaSession` with chosen metadata, reads it back through the *same* `MediaSessionReader` a caller uses, and releases it.

```json
{
  "ok": true,
  "expected": {
    "title": "Body self-check",
    "artist": "com.beqa.body",
    "album": "sense layer M8",
    "duration_ms": 123000,
    "position_ms": 4200,
    "playback_state_name": "PAUSED"
  },
  "observed_self": { /* the session object from /media whose pkg == "com.beqa.body", or null */ },
  "match": true,
  "full_read": { /* the complete /media response captured during the check */ }
}
```

`match` is true only when `observed_self` exists **and** its `title`, `artist`, `duration_ms` and `playback_state_name` all equal the expected values. Note that `position_ms` appears in `expected` but is **not** part of the `match` test.

Errors:

- `{"ok":false,"error":"selfcheck_failed: <ExceptionSimpleName>","detail":"…"}`
- `{"ok":false,"error":"selfcheck_timeout"}` from the 6-second latch
- `{"ok":false,"error":"selfcheck_no_result"}`
- `{"ok":false,"error":"selfcheck_interrupted"}`

It is silent and request-scoped by construction: the state is `PAUSED`, no audio focus is requested, no media notification is posted, and `release()` runs in a `finally`. A `{"count":0}` from `/media` on an idle device is a correct answer that proves only that `getActiveSessions()` was permitted, and this route is what proves the extraction path.

---

## POST /selfcheck/reply

A loopback proof for the RemoteInput reply path. **JSON POST body**, and query parameters are also accepted. Auth: required.

| Field | Type | Default | Meaning |
|---|---|---|---|
| `op` | string | `"status"` | `"post"`, `"clear"`, or anything else which falls through to status |

**`op="post"`** posts a silent self-notification carrying a real RemoteInput action:
```json
{ "ok": true, "posted": true, "notif_id": 4242, "result_key": "body_loopback_text",
  "hint": "GET /notifications?pkg=com.beqa.body to read its key + action index" }
```
Error: `{"ok":false,"error":"post_failed: <ExceptionSimpleName>","detail":"…"}`

**`op="clear"`** gives `{"ok":true,"cleared":true}`, with the error `{"ok":false,"error":"clear_failed: <ExceptionSimpleName>"}`.

**status**, the default, gives `{"ok":true,"posted_at_ms":…,"received_count":0,"last_text":null,"last_at_ms":0}`. `last_text` is JSON `null` until a reply lands.

The notification uses channel `body_loopback` at `IMPORTANCE_MIN`, so no sound, no vibration, no lights, no badge, local-only. Callers should always `clear` when done. The full loop is `post`, then `GET /notifications?pkg=com.beqa.body` for the `key` and action `index`, then `POST /notifications/reply` which requires the confirmation handshake like any other reply, and finally status shows `received_count` incremented and `last_text` set. The reply reaches a `BroadcastReceiver` inside this app and no human being, which is why `com.beqa.body` is on the reply allowlist.

---

## POST /ask, the phone-rule alarm

**JSON POST body**, and query parameters are also accepted. Auth: required. This is the **only** route in this app permitted to make a sound on the phone.

| Field | Type | Default | Used by |
|---|---|---|---|
| `op` | string | `"status"` | all. `post`, `nag`, `clear`, `fire_action`, or anything else which falls through to status |
| `question` | string | `""` | `post` |
| `timeout_ms` | long | **`3600000`** (1 h) | `post`, **coerced into 60000-14400000**, meaning 1 minute to 4 hours |
| `id` | string | *(blank gives null)* | `nag`, `clear`, `status` |
| `answer` | string | `""` | `fire_action`, and it must be `YES` or `NO`, compared uppercase |

### op="post"

```json
{ "ok": true, "posted": true, "id": "ask-1757990000000-a1b2c3d4",
  "expires_at_ms": 1757993600000, "channel": { /* channel object */ } }
```

Errors:

| Body | When |
|---|---|
| `{"ok":false,"error":"question_required"}` | question blank after trim |
| `{"ok":false,"error":"question_too_long","max_chars":240}` | more than 240 characters |
| `{"ok":false,"error":"ask_already_pending","pending_id":"…","pending_question":"…"}` | one ask is already outstanding |
| `{"ok":false,"error":"rate_limited","max_per_hour":6,"retry_after_s":N}` | 6 new asks already this rolling hour |
| `{"ok":false,"error":"post_failed: <ExceptionSimpleName>","detail":"…"}` | `notify()` threw, and the stored id is rolled back |

Note that `rate_limited` here is the **AskGate's own** hourly cap and arrives with HTTP **200**. It is unrelated to the auth rate limiter's HTTP 429 `rate_limited`, which is the same error string in a completely different place.

### op="nag", re-alert the same ask, exempt from the hourly cap

Success is `{"ok":true,"nagged":true,"id":"ask-…","nags":3}`.

If the ask is already answered, expired or cleared you get `{"ok":true,"nagged":false,"state":"YES","answer":"YES"}`, where `answer` is JSON `null` when unanswered.

Errors: `{"ok":false,"error":"no_ask"}`, `{"ok":false,"error":"id_mismatch","current_id":"…"}` and `{"ok":false,"error":"nag_failed: <ExceptionSimpleName>"}`.

### op="clear"

`{"ok":true,"cleared":true,"id":"ask-…"}`, where `id` may be JSON `null`.

On an id mismatch you get `{"ok":true,"cleared":false,"error":"id_mismatch","current_id":"…"}`. **Note the `ok:true` alongside an `error` key**, so check `cleared`, not `ok`.

### op="fire_action"

This fires the **exact** `PendingIntent` the notification's button carries, resolved with `FLAG_NO_CREATE`, proving the full callback path without faking a touch.

`{"ok":true,"sent":true,"id":"ask-…","answer":"YES"}`

Errors: `{"ok":false,"error":"answer_must_be_YES_or_NO"}`, `{"ok":false,"error":"no_such_pending_intent","hint":"the notification never registered this button for id=<id>"}` and `{"ok":false,"error":"send_failed: <ExceptionSimpleName>"}`.

### status, the default op

```json
{
  "ok": true,
  "id": "ask-1757990000000-a1b2c3d4",
  "channel": { /* channel object */ },
  "asks_this_hour": 2,
  "max_asks_per_hour": 6,
  "state": "pending",
  "question": "Can I open your banking app?",
  "nags": 3,
  "created_at_ms": 1757990000000,
  "expires_at_ms": 1757993600000,
  "answered_at_ms": 0,
  "answer": null
}
```

The first five keys are always present, and `id` may be JSON `null`. The last six appear **only** when an ask is stored and either no `id` was queried or it matches the stored one.

**`state` values, and the surprise:** `"none"` when nothing was ever posted, `"unknown"` when you asked about an id the gate no longer holds (the response then also carries `"queried_id"` and omits the detail fields, because the code refuses to guess YES), `"pending"`, `"cleared"`, `"expired"`, **or the literal answer `"YES"` or `"NO"`**. `stateOf` returns the answer itself as the state, so treat any value outside the five known words as an answer. `answer` is JSON `null` until answered. Polling status also cancels the notification when it finds the ask expired.

**`channel` object**, read back from the OS and never assumed:
```json
{ "exists": true, "id": "body_phone_rule_ask", "importance": 4,
  "sound": "content://settings/system/notification_sound", "vibration": true,
  "bypass_dnd": false, "blocked_app_level": false }
```
or `{"exists": false, "id": "body_phone_rule_ask"}`. `sound` may be JSON `null`, and `blocked_app_level` is `!areNotificationsEnabled()`.

**The structural guarantees**, which are the point of this route: there is no informational mode, because every post is a YES/NO question with a pending answer. There is **one** pending ask at a time, a cap of **6** new asks per rolling hour with re-nags free, and every notification carries `setTimeoutAfter()` so a crashed caller cannot leave a stuck alarm. Answering cancels the notification from inside the receiver. The channel is `body_phone_rule_ask` at `IMPORTANCE_HIGH` with sound and vibration, created once, and the source warns that `createNotificationChannel` can only *lower* importance afterwards, so if it is ever found demoted the fix is a new channel id rather than a code change. `AskGateReceiver` is manifest-registered and **not exported**, so nothing outside the app can forge an answer, including `am broadcast` from adb.

---

## Node ids and the generation scheme

**Id format:** `"<generation>-<label>"`, for example `"7-3"`.

- `generation` is a process-global counter incremented **every time the label map is rebuilt**, which means on every `/read_screen` and every `/find_nodes`, including the internal calls made by `/wait_for` (with `text` or `rid`) and by `/tap` with `text=`.
- `label` is a sequential integer, `idMap.size + 1`, assigned in traversal order within that one read. Labels restart at 1 on each read.
- Behind each label is a **StableKey**: `"pkg|shortClass|treepath|left_top_right_bottom"`, where treepath is the child-index path from the root's index in the roots list, so `"0"` for a root and `"0.2.1"` for a grandchild.

**Parsing** (`parseRawId`) splits on the **first** `-` and only when that dash is at index greater than 0. So:

- `"7-3"` means generation 7, label 3, with the generation enforced.
- `"3"` is a bare id, **accepted for back-compat**, with the generation check **skipped**. Only the weaker treepath and short-class validation applies, so a bare id can silently retarget a different element after the tree changes. Always use the qualified form the API hands you.
- `""` or anything non-numeric gives `bad_id`.

**Errors from `resolveChecked`:**

| Error | Meaning |
|---|---|
| `stale_generation` | another read rebuilt the map since this id was issued, so acting could hit the **wrong** element. Re-read. |
| `node_stale` | label unknown in the current map, or the node could not be re-located in the live tree |
| `bad_id` | unparseable |
| `service_not_running` | accessibility service down |

The generation comparison happens under the same lock that rebuilds the map, so a concurrent `/read_screen` cannot slip between check and lookup.

**The practical rule is that the id map is global, single-slot and shared.** Any client's `/read_screen` invalidates every other client's ids. A read, act, read, act loop is fine. A read then *fan out several actions* loop is not, unless nothing in between re-reads.

**Display binding.** The map also records `lastDisplayId`, the display it was built from. Action resolution (`locateByKey` and `navigateKey`) searches that same display's roots. An id built with `?display=2` resolves against display 2, and if you re-read without the parameter the same label now points into display 0's tree.

---

## The screen hash

`screen_hash`, `hash_before`, `hash_after` and the `hash` on `/screen_diff` are all the same value: `Integer.toHexString` of a structural fold over the tree.

Each node contributes `shortClass|text|desc|rid|state|checked`, with **no labels and no bounds**, combined with child hashes in order, so the hash is stable under re-labelling and scroll jitter. Only "interesting" nodes contribute a signature, and pruned subtrees contribute 0.

`currentHash()`, used by every action route and by `/screen_diff`, always reads `allRoots()` on the **default display** with systemui **excluded**. The hash returned by `/read_screen` uses the roots and `system_ui` setting of *that* call. **Two hashes from different sources are therefore not always comparable.** A `/read_screen?display=2&system_ui=1` hash will never equal a `currentHash()` from an action outcome.

`currentHash()` returns the string `"0"` when the accessibility service is unavailable, and `safeHash()` inside the action executor returns `""` when hashing throws.

---

## The two-phase confirmation gate

**Which routes use it:** `/sms/send`, `/notifications/reply` and `/notifications/action`, and **only** those three.

**Which routes deliberately do not:** `/notifications/dismiss` and `/notifications/snooze`. The source states the reasoning: dismissing or snoozing has no effect outside this device, because the underlying message still exists in the source app and a snooze just reposts it later, which matches the gate's philosophy of gating what is irreversible or externally visible. Also ungated are `/launch`, which can open any URL or app, and every action route such as `/tap` and `/type_text`.

### Phase 1, call without confirm

The route validates everything first, checking that the notification exists, the package is allowlisted, the action index is in range and required fields are present, and **returns those errors instead of a gate** when they apply. If validation passes, `ConfirmationGate.guard` mints a token:

```json
{
  "ok": false,
  "confirmation_required": true,
  "confirm_token": "Xq3nZ8pR1mK7vT2wY5aBcD",
  "action": "sms.send",
  "summary": { "to": "[number …42]", "body_preview": "on my way", "body_len": 9 },
  "expires_in_s": 120
}
```

**`ok` is `false` and the HTTP status is 200.** Nothing was sent. A client that treats `ok:false` as terminal will never complete a send.

`confirm_token` is 16 `SecureRandom` bytes, Base64 URL-safe, no wrap, no padding, which is 22 characters.

### Phase 2, repeat the identical request plus confirm

Send **exactly the same parameters** with `confirm: "<token>"`. The token is matched against the stored triple of `(action, sha256(canonical), expiry)`. On a match it is removed atomically, since `pending.remove` is the single-use claim and with two racing threads exactly one proceeds, and then the real work runs.

### Canonical strings, what the token is bound to

| Action | Canonical |
|---|---|
| `sms.send` | `sms.send\|<to>\|<body>` |
| `notif.reply` | `notif.reply\|<key>\|<resolvedIndex>\|<result_key or "*">\|<text>` |
| `notif.action` | `notif.action\|<key>\|<resolvedIndex>\|<title>` |

Only the SHA-256 of the canonical is stored, and raw parameters never are. A token issued for "text Mom hi" can never authorise "text a stranger something else". For `notif.reply` the *resolved* index is folded in, so a token minted for index 0 cannot be replayed against index 1. If you omitted `action_index` in phase 1 and the notification's actions change before phase 2 so that auto-resolution picks a different index, the canonical no longer matches and the token is rejected.

### What invalidates a token

1. **TTL of 120000 ms, which is 2 minutes**, reported as `expires_in_s: 120`. Expired entries are pruned on every `guard()` call.
2. **Single use**, since a successful confirmation removes it.
3. **Any change to the action or its parameters**, giving a different hash and no match.
4. **Pressure, because `MAX_PENDING = 5`.** Before minting, `evictIfFull()` loops while `pending.size >= 5`, evicting the **soonest-to-expire** entry. Starting a sixth confirmation kills the oldest outstanding one, so do not hold several gates open at once.
5. **Process restart**, because `pending` is an in-memory `ConcurrentHashMap`.

### Supplying a bad token

A wrong, expired or already-used token does **not** hard-fail. `guard` falls through to the issue path and returns a **fresh** token with an extra field:

```json
{ "ok": false, "confirmation_required": true, "confirm_token": "<new token>",
  "action": "sms.send", "summary": { }, "expires_in_s": 120, "error": "confirm_invalid" }
```

So the flow restarts rather than erroring out. If you retry blindly you will loop forever, each attempt burning a pending slot, so check for `error: "confirm_invalid"` and surface it.

### summary shapes, echoed back verbatim from phase 1

- **`sms.send`** has `to` (masked), `body_preview` (the first **40** characters of the body) and `body_len`.
- **`notif.reply`** has `pkg`, `action_index`, `action_title` (`""` if none), `result_keys` (an array of all of the action's result keys, or just the one you named) and `text_preview` (the first **40** characters).
- **`notif.action`** has `pkg`, `action_index` and `title` (the action's real title, falling back to the one you sent).

**Number masking** (`maskNumber`): strings shorter than 2 characters become `"[number]"`, otherwise you get `"[number …XX]"` with the last two digits and a Unicode ellipsis (U+2026). The full number is never echoed.

---

## POST /sms/send

**JSON POST body**, and query parameters are also accepted. Auth: required. **Confirmation-gated** as `sms.send`.

| Field | Type | Default | Meaning |
|---|---|---|---|
| `to` | string | `""` | destination number |
| `body` | string | `""` | message text |
| `confirm` | string | *(blank gives null)* | confirmation token for phase 2 |

Success:
```json
{ "ok": true, "sent": true, "to": "[number …42]" }
```

Errors: `{"ok":false,"error":"missing_params"}` when either `to` or `body` is blank, and `{"ok":false,"error":"sms_failed","detail":"<exception message>"}` where `detail` may be JSON `null`. You also get the gate response described in the confirmation gate section.

It uses `SmsManager.sendTextMessage(to, null, body, null, null)` with **no delivery or sent PendingIntent**, so `sent: true` means handed to the framework, not delivered. Long messages are **not** split into multipart, because `sendTextMessage` is used directly. `SEND_SMS` is declared in the manifest but is a runtime permission, so if it has not been granted, expect `sms_failed`.

---

## POST /notifications/reply

**JSON POST body**, and query parameters are also accepted. Auth: required. **Allowlisted and confirmation-gated** as `notif.reply`.

| Field | Type | Default | Meaning |
|---|---|---|---|
| `key` | string | `""` | the `key` from `GET /notifications` |
| `text` | string | `""` | reply text |
| `action_index` | int | *(absent gives `-1`)* | the `index` from the notification's `actions` array |
| `result_key` | string | *(blank gives null)* | target one specific RemoteInput on that action |
| `confirm` | string | *(blank gives null)* | phase-2 token |

Success:
```json
{ "ok": true, "replied": true, "pkg": "com.whatsapp", "action_index": 0,
  "result_keys": ["key_text_reply"] }
```
`result_keys` lists the keys actually written.

Errors:

| Body | When |
|---|---|
| `{"ok":false,"error":"listener_not_connected"}` | notification listener not bound |
| `{"ok":false,"error":"notification_not_found"}` | no active notification with that `key`. A blank key lands here, **not** on `missing_params`. |
| `{"ok":false,"error":"not_allowlisted","pkg":"…"}` | package not in the allowlist |
| `{"ok":false,"error":"no_reply_action"}` | the notification has no actions, or no action carries a RemoteInput, for auto-resolution only |
| `{"ok":false,"error":"action_index_out_of_range"}` | `action_index >= actions.size` |
| `{"ok":false,"error":"action_not_found"}` | the entry at that index is null |
| `{"ok":false,"error":"no_remote_input_on_action","action_index":N,"action_title":"…"}` | the chosen action has no RemoteInput, and `action_title` may be JSON `null` |
| `{"ok":false,"error":"result_key_not_found","action_index":N}` | your `result_key` is not on that action |
| `{"ok":false,"error":"reply_failed","detail":"…"}` | `actionIntent.send()` threw |

You also get the gate response described in the confirmation gate section.

Behaviour worth stating:

- **`text` is never validated.** It defaults to `""`, so a request missing `text` sends an empty reply rather than erroring.
- **Without `action_index`**, resolution falls back to "the first action carrying any RemoteInput", which is silently wrong for an app shipping two reply actions. The source calls this out as the reason `action_index` exists. Read `index` or `reply_action_index` from `GET /notifications` and pass it.
- **Without `result_key`, the text is written into *every* RemoteInput on the chosen action.** Name a `result_key` when an action carries more than one input.
- The intent is sent with `RemoteInput.setResultsSource(intent, SOURCE_FREE_FORM_INPUT)`.

---

## POST /notifications/action

Triggers a non-reply notification button. **JSON POST body**, and query parameters are also accepted. Auth: required. **Allowlisted and confirmation-gated** as `notif.action`.

| Field | Type | Default | Meaning |
|---|---|---|---|
| `key` | string | `""` | notification key |
| `title` | string | `""` | action title, matched **case-insensitively** when no index is given |
| `action_index` | int | *(absent gives `-1`)* | preferred, the exact index |
| `confirm` | string | *(blank gives null)* | phase-2 token |

Success: `{"ok":true,"triggered":true,"action_index":0}`

Errors:

- `listener_not_connected`
- `notification_not_found`
- `{"ok":false,"error":"not_allowlisted","pkg":"…"}`
- `action_not_found`, which covers no actions array at all, no title match, and a null entry at the index
- `action_index_out_of_range`
- `{"ok":false,"error":"action_failed","detail":"…"}`

You also get the gate response described in the confirmation gate section.

The action fires with a bare `Intent()` and no RemoteInput, so do not use this route for reply actions.

**This route is subject to the same allowlist as replies**, even though it triggers arbitrary buttons rather than sending text. Buttons on notifications from apps outside the allowlist are unreachable.

---

## POST /notifications/dismiss

**JSON POST body**, and query parameters are also accepted. Auth: required. **No confirmation and no allowlist.**

| Field | Type | Default | Meaning |
|---|---|---|---|
| `key` | string | `""` | notification key |

Success: `{"ok":true,"dismissed":true,"pkg":"com.whatsapp"}`

Errors: `{"ok":false,"error":"missing_params"}` for a blank key, `listener_not_connected`, `notification_not_found`, `{"ok":false,"error":"not_clearable","pkg":"…"}` and `{"ok":false,"error":"dismiss_failed","detail":"…"}`.

A `confirm` field is parsed by the router and then ignored by this handler.

---

## POST /notifications/snooze

**JSON POST body**, and query parameters are also accepted. Auth: required. **No confirmation and no allowlist.**

| Field | Type | Default | Meaning |
|---|---|---|---|
| `key` | string | `""` | notification key |
| `duration_ms` | long | **`600000`** (10 min) | **coerced into 1000-86400000**, meaning 1 second to 24 hours |

Success: `{"ok":true,"snoozed":true,"duration_ms":600000,"pkg":"com.whatsapp"}`, where `duration_ms` echoes the **clamped** value.

Errors: `{"ok":false,"error":"missing_params"}`, `listener_not_connected`, `notification_not_found` and `{"ok":false,"error":"snooze_failed","detail":"…"}`.

Note there is no `not_clearable` check here, unlike dismiss.

---

## POST /launch

**JSON POST body**, and query parameters are also accepted. Auth: required. **Not gated and not allowlisted.**

| Field | Type | Default | Meaning |
|---|---|---|---|
| `pkg` | string | *(blank gives null)* | package to launch via its launcher intent |
| `url` | string | *(blank gives null)* | URL or URI to open via `ACTION_VIEW` |

**`pkg` takes priority when both are given**, and the `url` is not even looked at.

Success is `{"ok":true,"launched":"com.whatsapp"}` **or** `{"ok":true,"opened":"https://example.com"}`. Note the success key differs by branch, and there is no single field that tells you which happened.

Errors: `{"ok":false,"error":"app_not_found","pkg":"…"}` when there is no launcher intent for that package, `{"ok":false,"error":"launch_failed","detail":"…"}` where `detail` may be JSON `null`, and `{"ok":false,"error":"missing_params"}` when neither is given.

`url` accepts anything `Uri.parse` handles, such as `https://`, `geo:`, `tel:` and `mailto:`. `FLAG_ACTIVITY_NEW_TASK` is added in both branches. **A `tel:` URL only opens the dialer, it does not place a call.** This route is not confirmation-gated despite being able to open arbitrary URIs.

---

## GET /list_apps

**Query parameters.** Auth: required.

| Param | Type | Default | Meaning |
|---|---|---|---|
| `filter` | string | *(null)* | case-insensitive substring matched against **either** the label **or** the package name |

Success:
```json
{ "ok": true, "count": 2,
  "apps": [ { "package": "com.whatsapp", "label": "WhatsApp" } ] }
```
Results are sorted by lowercased label. Only activities resolving `ACTION_MAIN` plus `CATEGORY_LAUNCHER` are listed. When a label cannot be loaded, the package name is used as the label.

Errors: `{"ok":false,"error":"list_failed","detail":"…"}`

Note the key is `package` here, but `pkg` almost everywhere else in the API.

---

## GET /current_app

**No parameters.** Auth: required.

```json
{ "ok": true, "package": "com.whatsapp" }
```

`package` is JSON `null` when the accessibility service is not running or has not yet seen a window-state change. **`ok` is `true` either way**, since this route has no error branch, so a `null` package is not distinguishable from "service down". Use `/context` (`foreground_app` versus `active_window_app` versus `accessibility`) or `/health` when you need to tell those apart. This reports the **cached** `foregroundPackage`, not a live window read.

---

## Package allowlist for notification replies and actions

`ExternalActions.REPLY_ALLOWLIST` is applied to `/notifications/reply` and `/notifications/action`. It is exact, complete, and matched by **exact equality** on `sbn.packageName`, with no prefixes and no substrings:

```
com.whatsapp
org.telegram.messenger
com.google.android.apps.messaging
com.samsung.android.messaging
org.thoughtcrime.securesms
com.facebook.orca
com.instagram.android
com.beqa.body
```

`com.beqa.body` is this app itself. Replying to its own loopback self-check notification reaches a `BroadcastReceiver` in the same process and no human being, and it exists so the reply path can be proven without messaging anyone.

A blocked request returns `{"ok":false,"error":"not_allowlisted","pkg":"<package>"}` with HTTP 200, **before** the confirmation gate is reached, so you get the refusal on the first call rather than after a handshake. Anything not on this list, such as Signal forks, Discord, Slack, Gmail or Element, is unreachable for replies and for action buttons, and the list is compiled in, so there is no runtime way to extend it.

`/notifications/dismiss` and `/notifications/snooze` are **not** allowlisted and work on any package.

---

## Complete error-string index

**Server layer, non-200 status:** `forbidden` (403), `rate_limited` (429), `unauthorized` (401), `not_found` (404), `server_error` (500).

**Handler layer, all HTTP 200 with `ok:false`:**

`service_not_running`, `listener_not_connected`, `no_media_session_service`, `not_enabled_listener`, `no_sensor_service`, `bad_id`, `stale_generation`, `node_stale`, `no_match`, `no_target`, `no_focused_field`, `set_text_failed`, `unknown_direction`, `unknown_key`, `global_action_failed`, `gesture_failed`, `no_condition`, `exception`, `missing_params`, `app_not_found`, `launch_failed`, `sms_failed`, `notification_not_found`, `not_allowlisted`, `no_reply_action`, `action_index_out_of_range`, `action_not_found`, `no_remote_input_on_action`, `result_key_not_found`, `reply_failed`, `action_failed`, `not_clearable`, `dismiss_failed`, `snooze_failed`, `confirm_invalid`, `question_required`, `question_too_long`, `ask_already_pending`, `rate_limited` (AskGate's own), `no_ask`, `id_mismatch`, `answer_must_be_YES_or_NO`, `no_such_pending_intent`, `selfcheck_timeout`, `selfcheck_no_result`, `selfcheck_interrupted`.

**Errors with an interpolated suffix**, which you must match by prefix and never by equality: `read_failed: <Exception>`, `list_failed: <Exception>` (sensors), `media_read_failed: <Exception>`, `selfcheck_failed: <Exception>`, `post_failed: <Exception>` (AskGate and LoopbackReplyProbe), `nag_failed: <Exception>`, `send_failed: <Exception>`, `clear_failed: <Exception>`.

**Ambiguity to guard against:** `list_failed` appears twice with different shapes. `/list_apps` returns the bare string `"list_failed"` plus a `detail` field, while `/sensors?list=1` returns `"list_failed: <ExceptionSimpleName>"` with no `detail`.

---

## Things you will get wrong without reading this

1. **HTTP status is not the error channel.** Only 5 server-level failures use non-200 codes, and every handler error, including every refusal to act, arrives as 200 with `ok:false`. Always read `ok`.
2. **The confirmation gate returns `ok:false` on success of phase 1.** `confirmation_required:true` means "ask again with the token", not "it failed".
3. **A bad `confirm` token issues a new token** with `error:"confirm_invalid"` rather than failing, so blind retries loop forever and burn pending slots, of which there are only 5.
4. **Every POST route also works as a GET** with query parameters, because the method is never checked. Conversely, **PUT bodies are silently discarded** by NanoHTTPD 2.3.1's `"content"` versus `"postData"` key mismatch.
5. **Send `charset=UTF-8`.** Without it the body is decoded as US-ASCII and non-ASCII text is destroyed before any handler sees it.
6. **`qBool` only accepts `"1"` and `"true"`.** `clear=no` silently means `clear=false`, and `verbose=yes` silently means false.
7. **`/read_screen`, `/find_nodes`, `/wait_for` (with text or rid) and `/tap?text=` all bump the generation** and invalidate every outstanding node id, for every client rather than just yours.
8. **`/scroll` and `/swipe` mean opposite things by `direction`**, one being content direction and the other finger direction.
9. **`distance` silently falls back to medium (0.6)** for any unrecognised value, including typos.
10. **`verified` and `screen_changed` are computed against display 0 always**, so cross-display actions look unverified even when they worked, and `settle()` gives up after 500 ms, so slow UIs also report `screen_changed:false`.
11. **`display` defaults to `0`** on the gesture routes rather than to whatever display you last read, and it only steers the gesture fallback, because node resolution follows the display the id map was built from.
12. **`/find_nodes` with no selectors returns everything visible**, cannot see systemui, always includes bounds, and never tells you it truncated, so `count == limit` is your only clue.
13. **`/screen_diff`'s `added_labels` and `removed_labels` are always empty.**
14. **`/wait_for`'s `found:false` is a timeout, not an error**, and with `gone=1` a `found:true` means the target vanished.
15. **`/notifications/reply` with no `action_index` guesses**, and with no `result_key` it writes your text into every RemoteInput on the action. A blank `text` sends an empty reply without complaint.
16. **`/notifications/action` is allowlisted too**, using the same eight packages, even though it triggers ordinary buttons.
17. **`rate_limited` means two different things.** HTTP 429 comes from the auth limiter (5 failures in 60 s gives a 5 minute block, and it blocks the *whole* loopback because the key is the IP), while HTTP 200 comes from AskGate's 6-asks-per-hour cap.
18. **`/health` needs no token but `/state` does**, and `/health` cannot tell you whether your token is good.
19. **`/ask` status can return `"state":"YES"` or `"state":"NO"`**, because the answer *is* the state. And `op=clear` with a mismatched id returns `ok:true` alongside an `error` key.
20. **`/current_app` returns `ok:true` with `package:null`** when the accessibility service is down, so it never reports an error.
21. **`/sensors` polls only 8 fixed sensor types** regardless of what `list=1` shows, always returns `ok:true`, echoes the *clamped* timeout, and must not be called with `rate_us=0`.
22. **`/describe_node`'s `bad_id` hint always says "generation 0"**, so do not parse the current generation out of it.
23. **Key naming is not uniform.** `/state` is camelCase while everything else is snake_case, the same value appears as `rid` in the compact node and `viewId` in `/describe_node`, and packages appear as `pkg` in notifications and media but `package` in `/list_apps` and `/current_app`.
