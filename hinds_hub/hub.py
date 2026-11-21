"""Hinds Hub: the Home Assistant side of Hinds TV.

- Follows your teams' fixtures (ESPN's public scoreboard data), publishes them as
  sensor.hinds_football and fires hinds_match_soon / hinds_kickoff events for the
  reminder automations.
- Keeps sensor.hinds_now_playing up to date from Hinds TV (the kitchen frame shows it).
- Records channels to /media/hinds, now or on a schedule. Scheduled recordings start the
  stream themselves, so they need the portal details Hinds TV sends when you allow it
  (kept in the add-on's private /data, never logged).

Standard library only. Hinds TV and the kitchen frame talk to it over plain HTTP on port 8642.
"""

import json
import os
import re
import shutil
import subprocess
import threading
import time
import traceback
import urllib.error
import urllib.parse
import urllib.request
import uuid
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from zoneinfo import ZoneInfo

VERSION = "0.1.0"
PORT = 8642
DATA = os.environ.get("HUB_DATA", "/data")
MEDIA = os.environ.get("HUB_MEDIA", "/media/hinds")
SUPERVISOR = "http://supervisor/core/api"
TOKEN = os.environ.get("SUPERVISOR_TOKEN", "")
ESPN = "https://site.api.espn.com/apis/site/v2/sports/soccer/all/teams/{}/schedule?fixture=true"

lock = threading.RLock()


def log(*parts):
    print(time.strftime("%H:%M:%S"), *parts, flush=True)


def load(name, default):
    try:
        with open(os.path.join(DATA, name)) as f:
            return json.load(f)
    except Exception:
        return default


def save(name, value):
    tmp = os.path.join(DATA, name + ".tmp")
    with open(tmp, "w") as f:
        json.dump(value, f)
    os.replace(tmp, os.path.join(DATA, name))


OPTIONS = load("options.json", {"teams": ["359", "86", "382"], "remind_minutes": 30, "keep_days": 30})


# --- Home Assistant -------------------------------------------------------------------------------

def ha(method, path, body=None):
    if not TOKEN:
        return None
    req = urllib.request.Request(SUPERVISOR + path, method=method,
                                 data=None if body is None else json.dumps(body).encode(),
                                 headers={"Authorization": "Bearer " + TOKEN, "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            return json.loads(r.read() or b"null")
    except Exception as e:
        log("home assistant", method, path, "failed:", e)
        return None


def set_state(entity, state, attributes):
    ha("POST", "/states/" + entity, {"state": str(state)[:255], "attributes": attributes})


def fire(event, data):
    ha("POST", "/events/" + event, data)


def timezone():
    cfg = ha("GET", "/config") or {}
    try:
        return ZoneInfo(cfg.get("time_zone") or "Europe/London")
    except Exception:
        return ZoneInfo("Europe/London")


TZ = None


# --- Fixtures -------------------------------------------------------------------------------------

state = {
    "matches": [],          # upcoming and live, soonest first
    "now_playing": {"state": "off"},
    "recording": None,      # the recording in progress
}
fired = load("fired.json", {})


def fetch_json(url, timeout=15):
    req = urllib.request.Request(url, headers={"User-Agent": "HindsHub/" + VERSION, "Accept": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read())


def parse_iso(s):
    return int(datetime.strptime(s.replace("Z", "+0000"), "%Y-%m-%dT%H:%M%z").timestamp()) if len(s) <= 17 else \
        int(datetime.fromisoformat(s.replace("Z", "+00:00")).timestamp())


def team_matches(team_id):
    data = fetch_json(ESPN.format(team_id))
    team = (data.get("team") or {}).get("displayName", team_id)
    out = []
    for e in data.get("events", []):
        comp = (e.get("competitions") or [{}])[0]
        status = (comp.get("status") or {}).get("type") or {}
        sides = {c.get("homeAway"): c for c in comp.get("competitors", [])}
        home, away = sides.get("home", {}), sides.get("away", {})

        def name(c):
            return ((c.get("team") or {}).get("shortDisplayName") or (c.get("team") or {}).get("displayName") or "?")

        def score(c):
            s = c.get("score")
            return (s.get("displayValue") if isinstance(s, dict) else s) if s is not None else None

        league = e.get("league") if isinstance(e.get("league"), dict) else {}
        out.append({
            "id": str(e.get("id")),
            "team": team,
            "team_id": str(team_id),
            "home": name(home),
            "away": name(away),
            "title": name(home) + " v " + name(away),
            "competition": league.get("name") or (e.get("season") or {}).get("displayName") or "",
            "kickoff": parse_iso(e.get("date")),
            "state": status.get("state", "pre"),          # pre, in, post
            "detail": status.get("shortDetail") or "",    # "63'", "HT", "FT", "Sat 21st 5:30 PM"
            "home_score": score(home),
            "away_score": score(away),
        })
    return out


def refresh_fixtures():
    now = time.time()
    found = {}
    for team in OPTIONS.get("teams", []):
        try:
            for m in team_matches(team):
                # A match between two followed teams (Man City at Arsenal) appears once.
                if m["state"] == "post" and m["kickoff"] < now - 3 * 3600:
                    continue
                if m["kickoff"] > now + 21 * 86400:
                    continue
                found.setdefault(m["id"], m)
        except Exception as e:
            log("fixtures for", team, "failed:", e)
    matches = sorted(found.values(), key=lambda m: m["kickoff"])
    with lock:
        state["matches"] = matches
    publish_football()
    return matches


def local_time(ts):
    return datetime.fromtimestamp(ts, TZ).strftime("%H:%M")


def local_day(ts):
    d = datetime.fromtimestamp(ts, TZ)
    today = datetime.now(TZ).date()
    if d.date() == today:
        return "Today"
    if (d.date() - today).days == 1:
        return "Tomorrow"
    return d.strftime("%a %-d %b")


def describe(m):
    return dict(m, kickoff_local=local_time(m["kickoff"]), day=local_day(m["kickoff"]))


def publish_football():
    with lock:
        matches = [describe(m) for m in state["matches"]]
    live = [m for m in matches if m["state"] == "in"]
    nxt = next((m for m in matches if m["state"] == "pre"), None)
    if live:
        m = live[0]
        headline = f"{m['home']} {m['home_score']}–{m['away_score']} {m['away']} ({m['detail']})"
    elif nxt:
        headline = f"{nxt['title']} · {nxt['day']} {nxt['kickoff_local']}"
    else:
        headline = "No matches soon"
    set_state("sensor.hinds_football", headline, {
        "friendly_name": "Football",
        "icon": "mdi:soccer",
        "live": live,
        "next": nxt,
        "matches": matches[:8],
    })


def check_reminders():
    now = time.time()
    lead = int(OPTIONS.get("remind_minutes", 30)) * 60
    changed = False
    with lock:
        matches = list(state["matches"])
    for m in matches:
        info = describe(m)
        data = {k: info[k] for k in ("id", "title", "home", "away", "team", "competition", "kickoff", "kickoff_local", "day")}
        soon_key, ko_key = m["id"] + ":soon", m["id"] + ":ko"
        if soon_key not in fired and m["kickoff"] - lead <= now < m["kickoff"]:
            fired[soon_key] = now
            changed = True
            log("reminder:", m["title"])
            fire("hinds_match_soon", dict(data, minutes=round((m["kickoff"] - now) / 60)))
        if ko_key not in fired and m["kickoff"] <= now < m["kickoff"] + 15 * 60:
            fired[ko_key] = now
            changed = True
            fire("hinds_kickoff", data)
    if changed:
        for k in [k for k, v in fired.items() if v < now - 7 * 86400]:
            del fired[k]
        save("fired.json", fired)


def fixtures_loop():
    next_full = 0
    while True:
        try:
            now = time.time()
            with lock:
                busy = any(m["state"] == "in" or abs(m["kickoff"] - now) < 20 * 60 for m in state["matches"])
            # Every 30 minutes; every minute around and during matches (live scores for the frame).
            if now >= next_full or busy:
                refresh_fixtures()
                next_full = now + 30 * 60
            check_reminders()
        except Exception:
            log("fixtures loop:", traceback.format_exc())
        time.sleep(60)


# --- Portal (only for recordings the hub starts itself) --------------------------------------------

VER = ("ImageDescription: 0.2.18-r23-250; ImageDate: Wed Aug 29 10:49:53 EEST 2018; PORTAL version: 5.6.1; "
       "API Version: JS API version: 343; STB API version: 146; Player Engine version: 0x58c")
DEFAULT_UA = ("Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) "
              "MAG200 stbapp ver: 2 rev: 250 Safari/533.3")


class Portal:
    """Just enough of the Stalker portal API to get a stream link: handshake, profile, create_link."""

    def __init__(self, p):
        self.p = p
        self.token = None

    @property
    def endpoint(self):
        return self.p["endpoint"]

    def headers(self, tok):
        ep = urllib.parse.urlsplit(self.endpoint)
        root = ep.path.rsplit("/", 1)[0] + "/"
        if root.endswith("server/"):
            root = root[: -len("server/")]
        h = {
            "User-Agent": self.p.get("userAgent") or DEFAULT_UA,
            "X-User-Agent": "Model: %s; Link: Ethernet" % self.p.get("stbType", "MAG250"),
            "Referer": "%s://%s%sc/" % (ep.scheme, ep.netloc, root),
            "Accept": "*/*",
            "Cookie": "mac=%s; stb_lang=en; timezone=%s" % (
                urllib.parse.quote(self.p["mac"]), urllib.parse.quote(self.p.get("timezone", "Europe/London"))),
        }
        if tok:
            h["Authorization"] = "Bearer " + tok
        return h

    def call(self, tok, **params):
        params["JsHttpRequest"] = "1-xml"
        url = self.endpoint + ("&" if "?" in self.endpoint else "?") + urllib.parse.urlencode(params)
        req = urllib.request.Request(url, headers=self.headers(tok))
        with urllib.request.urlopen(req, timeout=15) as r:
            body = r.read().decode("utf-8", "replace").strip()
        if body.lower().startswith("authorization failed"):
            raise PermissionError("authorization failed")
        js = json.loads(body).get("js")
        if tok and js is False:
            raise PermissionError("token rejected")
        return js

    def login(self):
        hs = self.call(None, type="stb", action="handshake", token="", prehash="0")
        tok = hs["token"]
        metrics = json.dumps({"mac": self.p["mac"], "sn": self.p.get("serialNumber", ""), "model": self.p.get("stbType", "MAG250"),
                              "type": "STB", "uid": self.p.get("deviceId", ""), "random": hs.get("random") or ""})
        prof = self.call(tok, type="stb", action="get_profile", hd="1", ver=VER, num_banks="2",
                         sn=self.p.get("serialNumber", ""), stb_type=self.p.get("stbType", "MAG250"), client_type="STB",
                         image_version="218", video_out="hdmi", device_id=self.p.get("deviceId", ""),
                         device_id2=self.p.get("deviceId2", ""), signature=self.p.get("signature", ""),
                         auth_second_step="1", hw_version="1.7-BD-00", not_valid_token="0", metrics=metrics,
                         timestamp=str(int(time.time())), api_signature="262")
        if not isinstance(prof, dict) or int(prof.get("status") or 0) != 0:
            raise PermissionError("the portal rejected the profile")
        self.token = tok

    def stream_url(self, cmd):
        for attempt in range(2):
            if not self.token:
                self.login()
            try:
                js = self.call(self.token, type="itv", action="create_link", cmd=cmd, series="",
                               forced_storage="undefined", disable_ad="0", download="0", force_ch_link_check="0")
                raw = (js or {}).get("cmd") or (js or {}).get("url") if isinstance(js, dict) else js
                if not raw:
                    raise ValueError("no stream link")
                return clean_url(raw, self.endpoint)
            except PermissionError:
                self.token = None
                if attempt:
                    raise
        raise ValueError("no stream link")


def clean_url(cmd, endpoint):
    t = cmd.strip()
    if " " in t and "://" not in t.split(" ", 1)[0]:
        t = t.split(" ", 1)[1].strip()
    u = urllib.parse.urlsplit(t)
    if u.hostname in ("localhost", "127.0.0.1"):
        ep = urllib.parse.urlsplit(endpoint)
        t = urllib.parse.urlunsplit((ep.scheme, ep.netloc, u.path, u.query, u.fragment))
    return t


def portal():
    p = load("portal.json", None)
    return Portal(p) if p and p.get("endpoint") and p.get("mac") else None


# --- Recordings -----------------------------------------------------------------------------------

def rec_path(rid, ext="ts"):
    return os.path.join(MEDIA, "%s.%s" % (rid, ext))


def safe_name(s):
    return re.sub(r"[^A-Za-z0-9 ._-]+", "", s).strip()[:60] or "Recording"


class Recorder(threading.Thread):
    """Records one channel until [stop]. The stream goes through ffmpeg (copy, no re-encode) to a TS file;
    if it drops, a fresh link is fetched and recording carries on in the same file."""

    def __init__(self, meta, url=None):
        super().__init__(daemon=True)
        self.meta = meta
        self.url = url
        self.proc = None
        self.stopped = threading.Event()
        self.grew = threading.Condition()

    def run(self):
        m = self.meta
        log("recording", m["title"], "until", local_time(m["stop"]))
        try:
            with open(rec_path(m["id"]), "ab") as out:
                failures = 0
                while not self.stopped.is_set() and time.time() < m["stop"]:
                    url = self.url
                    self.url = None  # a TV-supplied link is used once; later ones come from the portal
                    try:
                        if not url:
                            p = portal()
                            if not p:
                                raise RuntimeError("no portal details: allow recording in Hinds TV settings")
                            url = p.stream_url(m["cmd"])
                    except Exception as e:
                        failures += 1
                        m["error"] = str(e)
                        log("recording link failed:", e)
                        if failures > 5:
                            break
                        self.stopped.wait(10)
                        continue
                    cmd = ["ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error",
                           "-user_agent", m.get("userAgent") or DEFAULT_UA, "-rw_timeout", "20000000",
                           "-i", url, "-map", "0:v?", "-map", "0:a?", "-c", "copy",
                           "-f", "mpegts", "pipe:1"]
                    started = time.time()
                    self.proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
                    # Stops by the clock (live HLS starts a little behind, so a duration limit would end early).
                    timer = threading.Timer(max(1, m["stop"] - time.time()), self.proc.terminate)
                    timer.daemon = True
                    timer.start()
                    while True:
                        chunk = self.proc.stdout.read(65536)
                        if not chunk:
                            break
                        out.write(chunk)
                        out.flush()
                        with self.grew:
                            self.grew.notify_all()
                    self.proc.wait()
                    timer.cancel()
                    err = self.proc.stderr.read().decode("utf-8", "replace").strip()[-300:]
                    if err:
                        log("ffmpeg:", err)
                    failures = failures + 1 if time.time() - started < 30 else 0
                    if failures > 5:
                        m["error"] = "the stream kept failing"
                        break
                    if not self.stopped.is_set() and time.time() < m["stop"] - 5:
                        log("stream dropped, reconnecting")
                        self.stopped.wait(3)
        except Exception:
            log("recorder:", traceback.format_exc())
        finally:
            m["recording"] = False
            m["size"] = os.path.getsize(rec_path(m["id"])) if os.path.exists(rec_path(m["id"])) else 0
            m["duration_s"] = int(min(time.time(), m["stop"]) - m["start"])
            write_meta(m)
            with lock:
                if state["recording"] is self:
                    state["recording"] = None
            with self.grew:
                self.grew.notify_all()
            publish_recording()
            log("recording finished:", m["title"])

    def stop(self):
        self.stopped.set()
        if self.proc and self.proc.poll() is None:
            self.proc.terminate()


def write_meta(m):
    tmp = rec_path(m["id"], "json.tmp")
    with open(tmp, "w") as f:
        json.dump(m, f)
    os.replace(tmp, rec_path(m["id"], "json"))


def recordings():
    out = []
    if not os.path.isdir(MEDIA):
        return out
    for name in os.listdir(MEDIA):
        if name.endswith(".json"):
            try:
                with open(os.path.join(MEDIA, name)) as f:
                    m = json.load(f)
                if os.path.exists(rec_path(m["id"])):
                    m["size"] = os.path.getsize(rec_path(m["id"]))
                    out.append(m)
            except Exception:
                pass
    return sorted(out, key=lambda m: m["start"], reverse=True)


def start_recording(cmd, channel, title, stop, url=None, user_agent=None):
    with lock:
        current = state["recording"]
        if current and current.is_alive():
            raise RuntimeError("already recording " + current.meta["title"])
        os.makedirs(MEDIA, exist_ok=True)
        start = int(time.time())
        rid = time.strftime("%Y%m%d-%H%M", time.localtime(start)) + "-" + uuid.uuid4().hex[:6]
        meta = {"id": rid, "title": title or channel, "channel": channel, "cmd": cmd, "start": start,
                "stop": int(stop), "recording": True, "userAgent": user_agent}
        write_meta(meta)
        r = Recorder(meta, url)
        state["recording"] = r
        r.start()
    publish_recording()
    return meta


def publish_recording():
    with lock:
        r = state["recording"]
        m = r.meta if r and r.is_alive() else None
        schedule = load("schedule.json", [])
    set_state("sensor.hinds_recording", m["title"] if m else "idle", {
        "friendly_name": "Hinds recording",
        "icon": "mdi:record-rec" if m else "mdi:record",
        "channel": m["channel"] if m else None,
        "until": local_time(m["stop"]) if m else None,
        "scheduled": [{"title": s["title"], "channel": s["channel"], "day": local_day(s["start"]),
                       "time": local_time(s["start"])} for s in schedule[:5]],
    })


def schedule_loop():
    while True:
        try:
            now = time.time()
            with lock:
                schedule = load("schedule.json", [])
                due = [s for s in schedule if s["start"] <= now + 30]
                keep = [s for s in schedule if s not in due and s["stop"] > now]
                if len(keep) != len(schedule):
                    save("schedule.json", keep)
            for s in due:
                if s["stop"] > now:
                    try:
                        start_recording(s["cmd"], s["channel"], s["title"], s["stop"] + 5 * 60)
                    except Exception as e:
                        log("scheduled recording failed:", e)
            if due:
                publish_recording()
            # Old recordings go after keep_days.
            limit = now - int(OPTIONS.get("keep_days", 30)) * 86400
            for m in recordings():
                if not m.get("recording") and m["start"] < limit:
                    for ext in ("ts", "json"):
                        try:
                            os.remove(rec_path(m["id"], ext))
                        except OSError:
                            pass
        except Exception:
            log("schedule loop:", traceback.format_exc())
        time.sleep(20)


# --- HTTP API -------------------------------------------------------------------------------------

class Api(BaseHTTPRequestHandler):
    server_version = "HindsHub/" + VERSION

    def log_message(self, fmt, *args):
        pass

    def send_json(self, value, code=200):
        body = json.dumps(value).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(body)

    def body(self):
        n = int(self.headers.get("Content-Length") or 0)
        return json.loads(self.rfile.read(n) or b"{}") if n else {}

    def do_OPTIONS(self):
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, DELETE")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.end_headers()

    def do_GET(self):
        path = urllib.parse.urlsplit(self.path).path
        try:
            if path in ("/", "/status"):
                with lock:
                    r = state["recording"]
                    rec = dict(r.meta) if r and r.is_alive() else None
                    np = dict(state["now_playing"])
                self.send_json({"version": VERSION, "portal": portal() is not None, "recording": rec,
                                "schedule": load("schedule.json", []), "now_playing": np})
            elif path == "/fixtures":
                with lock:
                    matches = [describe(m) for m in state["matches"]]
                self.send_json({"matches": matches})
            elif path == "/recordings":
                self.send_json({"recordings": recordings()})
            elif path.startswith("/recordings/") and path.endswith(".ts"):
                self.serve_recording(path[len("/recordings/"):-3])
            else:
                self.send_json({"error": "not found"}, 404)
        except (BrokenPipeError, ConnectionResetError):
            pass
        except Exception as e:
            log("GET", path, traceback.format_exc())
            self.send_json({"error": str(e)}, 500)

    def do_POST(self):
        path = urllib.parse.urlsplit(self.path).path
        try:
            b = self.body()
            if path == "/now-playing":
                np = {"state": b.get("state", "off"), "channel": b.get("channel"), "programme": b.get("programme"),
                      "since": int(time.time())}
                with lock:
                    state["now_playing"] = np
                set_state("sensor.hinds_now_playing", np["channel"] if np["state"] == "playing" else "off", {
                    "friendly_name": "Living room TV", "icon": "mdi:television-play",
                    "programme": np["programme"], "since": np["since"]})
                self.send_json({"ok": True})
            elif path == "/portal":
                p = {k: b.get(k) for k in ("endpoint", "mac", "stbType", "serialNumber", "deviceId", "deviceId2",
                                           "signature", "timezone", "userAgent")}
                if not p["endpoint"] or not p["mac"]:
                    return self.send_json({"error": "missing portal details"}, 400)
                test = Portal(p)
                test.login()  # proves the details work before keeping them
                save("portal.json", p)
                log("portal details saved")
                self.send_json({"ok": True})
            elif path == "/record":
                stop = int(b.get("stop") or time.time() + 2 * 3600)
                meta = start_recording(b.get("cmd", ""), b.get("channel", ""), b.get("title", ""), stop,
                                       url=b.get("url"), user_agent=b.get("userAgent"))
                self.send_json({"ok": True, "recording": meta})
            elif path == "/record/stop":
                with lock:
                    r = state["recording"]
                if r:
                    r.stop()
                self.send_json({"ok": True})
            elif path == "/schedule":
                item = {"id": uuid.uuid4().hex[:8], "cmd": b["cmd"], "channel": b.get("channel", ""),
                        "title": b.get("title", ""), "start": int(b["start"]), "stop": int(b["stop"])}
                with lock:
                    schedule = load("schedule.json", [])
                    if any(s["cmd"] == item["cmd"] and s["start"] == item["start"] for s in schedule):
                        return self.send_json({"ok": True, "duplicate": True})
                    schedule.append(item)
                    schedule.sort(key=lambda s: s["start"])
                    save("schedule.json", schedule)
                publish_recording()
                self.send_json({"ok": True, "scheduled": item})
            else:
                self.send_json({"error": "not found"}, 404)
        except Exception as e:
            log("POST", path, "failed:", e)
            self.send_json({"error": str(e)}, 500)

    def do_DELETE(self):
        path = urllib.parse.urlsplit(self.path).path
        try:
            if path == "/portal":
                try:
                    os.remove(os.path.join(DATA, "portal.json"))
                except OSError:
                    pass
                self.send_json({"ok": True})
            elif path.startswith("/schedule/"):
                sid = path.rsplit("/", 1)[1]
                with lock:
                    save("schedule.json", [s for s in load("schedule.json", []) if s["id"] != sid])
                publish_recording()
                self.send_json({"ok": True})
            elif path.startswith("/recordings/"):
                rid = os.path.basename(path).replace(".ts", "")
                with lock:
                    r = state["recording"]
                    if r and r.meta["id"] == rid:
                        return self.send_json({"error": "still recording"}, 409)
                for ext in ("ts", "json"):
                    try:
                        os.remove(rec_path(rid, ext))
                    except OSError:
                        pass
                self.send_json({"ok": True})
            else:
                self.send_json({"error": "not found"}, 404)
        except Exception as e:
            self.send_json({"error": str(e)}, 500)

    def serve_recording(self, rid):
        rid = os.path.basename(rid)
        path = rec_path(rid)
        if not os.path.exists(path):
            return self.send_json({"error": "not found"}, 404)
        with lock:
            r = state["recording"]
            live = r if r and r.is_alive() and r.meta["id"] == rid else None
        size = os.path.getsize(path)
        rng = self.headers.get("Range")
        start, end = 0, size - 1
        if rng and rng.startswith("bytes="):
            a, _, b = rng[6:].partition("-")
            start = int(a) if a else max(0, size - int(b))
            end = int(b) if a and b else size - 1
        query = urllib.parse.parse_qs(urllib.parse.urlsplit(self.path).query)
        if live and not rng:
            # Still recording: stream it as it grows (no length), from the start or from near the end.
            offset = max(0, size - 4 * 1024 * 1024) if query.get("from") == ["end"] else 0
            self.send_response(200)
            self.send_header("Content-Type", "video/mp2t")
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            with open(path, "rb") as f:
                f.seek(offset - offset % 188)
                while True:
                    chunk = f.read(65536)
                    if chunk:
                        self.wfile.write(chunk)
                        continue
                    if not live.is_alive():
                        rest = f.read()
                        if rest:
                            self.wfile.write(rest)
                        break
                    with live.grew:
                        live.grew.wait(2)
            return
        end = min(end, size - 1)
        self.send_response(206 if rng else 200)
        self.send_header("Content-Type", "video/mp2t")
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(end - start + 1))
        if rng:
            self.send_header("Content-Range", "bytes %d-%d/%d" % (start, end, size))
        self.end_headers()
        with open(path, "rb") as f:
            f.seek(start)
            left = end - start + 1
            while left > 0:
                chunk = f.read(min(65536, left))
                if not chunk:
                    break
                self.wfile.write(chunk)
                left -= len(chunk)


def main():
    global TZ
    TZ = timezone()
    os.makedirs(MEDIA, exist_ok=True)
    # Recordings cut short by a restart are finished, not "recording".
    for m in recordings():
        if m.get("recording"):
            m["recording"] = False
            write_meta(m)
    log("Hinds Hub", VERSION, "following teams", OPTIONS.get("teams"), "on port", PORT)
    set_state("sensor.hinds_now_playing", "off", {"friendly_name": "Living room TV", "icon": "mdi:television-play"})
    publish_recording()
    threading.Thread(target=fixtures_loop, daemon=True).start()
    threading.Thread(target=schedule_loop, daemon=True).start()
    ThreadingHTTPServer(("0.0.0.0", PORT), Api).serve_forever()


if __name__ == "__main__":
    main()
