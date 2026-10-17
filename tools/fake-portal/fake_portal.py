#!/usr/bin/env python3
"""A tiny fake Stalker/Ministra portal for developing Hinds TV without a real subscription.

Serves the JSON API the app uses (handshake, get_profile, genres, channels, create_link,
watchdog) plus test MPEG-TS streams. Standard library only.

    python3 tools/fake-portal/fake_portal.py --port 8080 --media /path/to/ts-files

Point the app at http://<this-machine>:8080/c/ (from the Android emulator: http://10.0.2.2:8080/c/)
with any MAC starting 00:1A:79, or use "Playlist or link" with http://10.0.2.2:8080/playlist.m3u
(its header advertises the XMLTV guide at /epg.xml). Stream files are served from --media as <n>.ts; make test
streams with tools/fake-portal/make-test-streams.sh.
"""
import argparse
import json
import os
import secrets
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

GENRES = [
    {"id": "*", "title": "All"},
    {"id": "1", "title": "UK Entertainment"},
    {"id": "2", "title": "News"},
    {"id": "3", "title": "Sport"},
]

CHANNEL_NAMES = [
    ("1", "Test Card One"), ("1", "Test Card Two"), ("2", "Bars News 24"),
    ("3", "Pattern Sports"), ("1", "Colour Channel"), ("2", "Tone World"),
]


def channels(host):
    out = []
    for i, (genre, name) in enumerate(CHANNEL_NAMES, start=1):
        out.append({
            "id": str(i),
            "name": name,
            "number": str(100 + i),
            "cmd": f"ffmpeg http://localhost/ch/{i}",
            "tv_genre_id": genre,
            "logo": "",
            "use_http_tmp_link": "1",
            "enable_tv_archive": 0,
            "xmltv_id": f"test{i}.uk",
        })
    # Plenty of extra channels to exercise scrolling performance.
    for i in range(len(CHANNEL_NAMES) + 1, 2001):
        out.append({
            "id": str(i), "name": f"Filler Channel {i}", "number": str(100 + i),
            "cmd": f"ffmpeg http://localhost/ch/{i}", "tv_genre_id": "1", "use_http_tmp_link": 0,
        })
    return out


SHOWS = ["Morning News", "Cooking Hour", "Quiz Time", "Nature Live", "Evening Drama", "Late Film",
         "Sport Tonight", "Weather", "Documentary", "Comedy Classics"]


def programmes(channel_index, hours=12):
    """Half-hour/hour programmes from 2 h ago, deterministic per channel."""
    start = int(time.time()) // 1800 * 1800 - 7200
    out, t, i = [], start, channel_index
    while t < start + hours * 3600:
        length = 1800 if (i % 3) else 3600
        out.append({"name": SHOWS[i % len(SHOWS)], "descr": f"Episode {i} of {SHOWS[i % len(SHOWS)]}.",
                    "start_timestamp": t, "stop_timestamp": t + length})
        t += length
        i += 1
    return out


def xmltv_time(t):
    return time.strftime("%Y%m%d%H%M%S +0000", time.gmtime(t))


class Portal(BaseHTTPRequestHandler):
    tokens = set()
    media_dir = "."
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):  # quieter log: one line per request
        print(f"{self.address_string()} {self.command} {self.path[:120]} -> {args[1] if len(args) > 1 else ''}")

    def send_json(self, payload, status=200):
        body = json.dumps({"js": payload}).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def send_text(self, text, status=200, ctype="text/plain"):
        body = text.encode()
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        url = urlparse(self.path)
        q = {k: v[0] for k, v in parse_qs(url.query, keep_blank_values=True).items()}
        if url.path == "/c/" or url.path == "/c/index.html":
            return self.send_text("<html><body>MAG portal UI</body></html>", ctype="text/html")
        if url.path == "/playlist.m3u":
            return self.serve_playlist()
        if url.path == "/epg.xml":
            return self.serve_xmltv()
        if url.path.startswith("/play/"):
            return self.serve_stream(q.get("stream", "1"))
        if url.path != "/server/load.php":
            return self.send_text("<html>Not found</html>", status=404, ctype="text/html")

        call = f"{q.get('type')}/{q.get('action')}"
        if call == "stb/handshake":
            token = secrets.token_hex(16).upper()
            Portal.tokens.add(token)
            return self.send_json({"token": token, "random": secrets.token_hex(20)})

        auth = self.headers.get("Authorization", "")
        if auth.removeprefix("Bearer ") not in Portal.tokens:
            return self.send_text("Authorization failed.")
        cookie = self.headers.get("Cookie", "")
        if "mac=00%3A1A%3A79" not in cookie and "mac=00:1A:79" not in cookie:
            return self.send_json({"status": 1, "block_msg": "MAC address not registered (fake portal wants 00:1A:79:…)"})

        if call == "stb/get_profile":
            return self.send_json({"id": "1", "status": 0, "timeslot": 60, "watchdog_timeout": 120})
        if call == "itv/get_genres":
            return self.send_json(GENRES)
        if call == "itv/get_all_channels":
            return self.send_json({"total_items": 2000, "data": channels(self.headers.get("Host"))})
        if call == "itv/create_link":
            n = q.get("cmd", "").rsplit("/", 1)[-1]
            return self.send_json({"id": n, "cmd": f"ffmpeg http://localhost/play/live.php?stream={n}&extension=ts&play_token={secrets.token_hex(4)}"})
        if call == "itv/get_epg_info":
            return self.send_json({"data": {str(i): programmes(i) for i in range(1, 41)}})
        if call == "watchdog/get_events":
            return self.send_json({"data": {"msgs": 0}})
        return self.send_json({})

    def serve_playlist(self):
        host = self.headers.get("Host")
        lines = [f'#EXTM3U url-tvg="http://{host}/epg.xml"']
        for i, (genre, name) in enumerate(CHANNEL_NAMES, start=1):
            group = {"1": "Entertainment", "2": "News", "3": "Sport"}[genre]
            lines.append(f'#EXTINF:-1 tvg-id="ch{i}.test" tvg-chno="{i}" group-title="{group}",{name}')
            lines.append(f"http://{host}/play/live.php?stream={i}&extension=ts")
        self.send_text("\n".join(lines) + "\n", ctype="audio/x-mpegurl")

    def serve_xmltv(self):
        parts = ['<?xml version="1.0" encoding="UTF-8"?>', "<tv>"]
        for i, (_, name) in enumerate(CHANNEL_NAMES, start=1):
            parts.append(f'<channel id="ch{i}.test"><display-name>{name}</display-name></channel>')
        for i in range(1, len(CHANNEL_NAMES) + 1):
            for p in programmes(i):
                parts.append(f'<programme start="{xmltv_time(p["start_timestamp"])}" stop="{xmltv_time(p["stop_timestamp"])}" '
                             f'channel="ch{i}.test"><title>{p["name"]}</title><desc>{p["descr"]}</desc></programme>')
        parts.append("</tv>")
        self.send_text("\n".join(parts), ctype="application/xml")

    def serve_stream(self, n):
        files = sorted(f for f in os.listdir(Portal.media_dir) if f.endswith(".ts"))
        if not files:
            return self.send_text("no media", status=404)
        path = os.path.join(Portal.media_dir, files[(int(n) - 1) % len(files)])
        size = os.path.getsize(path)
        self.send_response(200)
        self.send_header("Content-Type", "video/mp2t")
        self.send_header("Content-Length", str(size))
        self.end_headers()
        with open(path, "rb") as f:
            while chunk := f.read(64 * 1024):
                try:
                    self.wfile.write(chunk)
                except (BrokenPipeError, ConnectionResetError):
                    return


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--port", type=int, default=8080)
    parser.add_argument("--media", default=".", help="directory with *.ts test streams")
    args = parser.parse_args()
    Portal.media_dir = args.media
    print(f"Fake portal on http://0.0.0.0:{args.port}/c/ (emulator: http://10.0.2.2:{args.port}/c/)")
    ThreadingHTTPServer(("0.0.0.0", args.port), Portal).serve_forever()


if __name__ == "__main__":
    main()
