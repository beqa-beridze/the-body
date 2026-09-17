"""android-body: control the phone through the local Body bridge.

The Body app (com.beqa.body) runs a loopback HTTP API on 127.0.0.1:8765. This plugin
registers android_* tools that call it. Tools stay listed even when the app is closed;
`_bridge_alive()` (a /health ping) gates dispatch. The bearer token is read from
$ANDROID_BRIDGE_TOKEN or ~/.config/body/bridge_token, and never logged.
"""
from __future__ import annotations

import json
import os
import urllib.error
import urllib.parse
import urllib.request

BASE = os.environ.get("ANDROID_BRIDGE_URL", "http://127.0.0.1:8765").rstrip("/")


def _token() -> str:
    t = os.environ.get("ANDROID_BRIDGE_TOKEN")
    if t:
        return t.strip()
    try:
        with open(os.path.expanduser("~/.config/body/bridge_token")) as f:
            return f.read().strip()
    except OSError:
        return ""


def _req(method: str, path: str, params: dict | None = None, timeout: int = 12) -> str:
    url = BASE + path
    headers = {"Authorization": "Bearer " + _token()}
    data = None
    params = {k: v for k, v in (params or {}).items() if v is not None}
    if method == "GET":
        if params:
            url += "?" + urllib.parse.urlencode(params)
    else:
        data = json.dumps(params).encode()
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.read().decode()
    except urllib.error.HTTPError as e:
        try:
            return e.read().decode()
        except Exception:
            return json.dumps({"ok": False, "error": f"http_{e.code}"})
    except Exception as e:
        return json.dumps({"ok": False, "error": "bridge_unreachable", "detail": str(e)})


def _bridge_alive() -> bool:
    try:
        with urllib.request.urlopen(BASE + "/health", timeout=2) as r:
            return getattr(r, "status", 200) == 200
    except Exception:
        return False


# ---- handlers ----------------------------------------------------------------
def _get(path, keys=()):
    def h(**kw):
        return _req("GET", path, {k: kw.get(k) for k in keys})
    return h


def _post(path, keys=()):
    def h(**kw):
        return _req("POST", path, {k: kw.get(k) for k in keys})
    return h


def _obj(props: dict, required=()):
    return {"type": "object", "properties": props, "required": list(required)}


S = {"type": "string"}
I = {"type": "integer"}
B = {"type": "boolean"}

# (name, emoji, handler, schema-params, description)
_TOOLS = [
    ("android_health", "🩺", _get("/health"), _obj({}),
     "Bridge liveness + which capabilities (accessibility, notifications) are connected. No auth."),
    ("android_state", "📱", _get("/state"), _obj({}),
     "Current device state: battery %, charging, screen on/off, uptime."),
    ("android_read_screen", "👁️", _get("/read_screen", ("mode", "bounds", "system_ui", "max")),
     _obj({"mode": {**S, "enum": ["interactive", "full"]}, "bounds": B, "system_ui": B, "max": I}),
     "Read the on-screen UI as compact JSON with integer ids (Set-of-Marks). Prefer this over screenshots. System UI excluded by default to save tokens. Act on elements by their id."),
    ("android_find_nodes", "🔎", _get("/find_nodes", ("text", "desc", "rid", "class", "clickable", "exact", "limit")),
     _obj({"text": S, "desc": S, "rid": S, "class": S, "clickable": B, "exact": B, "limit": I}),
     "Find on-screen elements matching text/content-desc/resource-id/class without dumping the whole tree. Returns ids you can tap."),
    ("android_describe_node", "🔬", _get("/describe_node", ("id",)), _obj({"id": S}, ("id",)),
     "Full properties (bounds, enabled, supported actions) of one element id from the last read_screen/find_nodes."),
    ("android_screen_diff", "🔀", _get("/screen_diff", ("hash",)), _obj({"hash": S}),
     "Cheap 'did the screen change?' check. Pass the previous screen_hash, and it returns changed + new hash without re-dumping."),
    ("android_tap", "👆", _get("/tap", ("id", "x", "y", "text")),
     _obj({"id": S, "x": I, "y": I, "text": S}),
     "Tap an element by its id (preferred), by coordinates, or by fallback text. Returns whether the screen changed + a stuck flag."),
    ("android_type_text", "⌨️", _get("/type_text", ("text", "id", "clear", "submit")),
     _obj({"text": S, "id": S, "clear": B, "submit": B}, ("text",)),
     "Type text into a field (by id, else the focused input). Verifies the field contents afterward."),
    ("android_scroll", "📜", _get("/scroll", ("direction", "id", "distance")),
     _obj({"direction": {**S, "enum": ["up", "down", "left", "right"]}, "id": S, "distance": {**S, "enum": ["short", "medium", "long"]}}, ("direction",)),
     "Scroll a list/screen in a direction."),
    ("android_swipe", "↔️", _get("/swipe", ("direction", "distance")),
     _obj({"direction": {**S, "enum": ["up", "down", "left", "right"]}, "distance": {**S, "enum": ["short", "medium", "long"]}}, ("direction",)),
     "Swipe gesture (pager/carousel)."),
    ("android_press_key", "🔘", _get("/press_key", ("key",)),
     _obj({"key": {**S, "enum": ["back", "home", "recents", "notifications", "quick_settings", "lock_screen"]}}, ("key",)),
     "Global navigation button."),
    ("android_wait_for", "⏳", _get("/wait_for", ("text", "rid", "app", "gone", "timeout")),
     _obj({"text": S, "rid": S, "app": S, "gone": B, "timeout": I}),
     "Wait until an element/app appears (or disappears with gone=true). Use after navigation."),
    ("android_notifications", "🔔", _get("/notifications", ("limit",)), _obj({"limit": I}),
     "List active notifications with titles/text and which have an inline reply."),
    ("android_notification_reply", "💬", _post("/notifications/reply", ("key", "text", "confirm")),
     _obj({"key": S, "text": S, "confirm": S}, ("key", "text")),
     "Reply to a messaging notification IN THE BACKGROUND (no screen takeover). Two-step: first call returns confirmation_required + a confirm_token + a summary. Show the summary to the user, and only call again with confirm=<token> and the identical key/text after they approve."),
    ("android_send_sms", "✉️", _post("/sms/send", ("to", "body", "confirm")),
     _obj({"to": S, "body": S, "confirm": S}, ("to", "body")),
     "Send an SMS. Two-step confirm like android_notification_reply: relay the summary to the user, then re-call with confirm=<token> + identical to/body."),
    ("android_launch", "🚀", _post("/launch", ("pkg", "url")),
     _obj({"pkg": S, "url": S}),
     "Launch an app by package, or open a url/geo:/tel: intent."),
    ("android_list_apps", "📦", _get("/list_apps", ("filter",)), _obj({"filter": S}),
     "List launchable apps (optionally filtered) with their package names."),
    ("android_current_app", "🕵️", _get("/current_app"), _obj({}),
     "The package name of the app currently in the foreground."),
]


def register(ctx) -> None:
    for name, emoji, handler, schema, *rest in _TOOLS:
        description = rest[0] if rest else ""
        ctx.register_tool(
            name=name,
            toolset="android",
            schema={"name": name, "description": description, "parameters": schema},
            handler=handler,
            check_fn=_bridge_alive,
            emoji=emoji,
        )
