"""QA dashboard: live emulator screen, click-to-tap, vehicle/location controls, TC runs, reports.

Exposed through the Synology reverse proxy, so every request needs HTTP Basic auth
(user `zobo`, password in qa/.qa_password, created on first start).
"""
import base64
import json
import mimetypes
import os
import secrets
import subprocess
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from . import runner
from .cases import CASES, ROUTE
from .device import ROOT, Device
from .vconsole import VConsole

STATIC = os.path.join(os.path.dirname(__file__), "static")
PASSWORD_FILE = os.path.join(ROOT, "qa", ".qa_password")
USER = "zobo"
EMULATOR = os.path.expanduser("~/Library/Android/sdk/emulator/emulator")
AVD = "Pleos_Connect_v2.1"
GRADLE_JAVA_HOME = os.path.expanduser("~/Library/Java/JavaVirtualMachines/corretto-17.0.4.1/Contents/Home")


def load_password():
    if not os.path.exists(PASSWORD_FILE):
        # Created 0600 up front so the secret is never readable by others, even briefly.
        fd = os.open(PASSWORD_FILE, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "w") as f:
            f.write(secrets.token_urlsafe(12))
    with open(PASSWORD_FILE) as f:
        return f.read().strip()


class State:
    def __init__(self):
        self.dev = Device()
        self.vc = VConsole(client="fogqa-dashboard")
        self.lock = threading.Lock()
        self.job = None          # name of the running long task (TC run / build)
        self.log = []
        self.log_base = 0        # absolute index of log[0]; clients poll with absolute cursors
        self.results = []
        self.report_dir = None
        self.stop_requested = False
        self.route_stop = threading.Event()
        self.route_progress = None
        self.screen = None       # cached `wm size` of the connected emulator

    def emit(self, msg):
        line = f"{time.strftime('%H:%M:%S')} {msg}"
        self.log.append(line)
        drop = len(self.log) - 400
        if drop > 0:
            del self.log[:drop]
            self.log_base += drop

    def start_job(self, name, target):
        with self.lock:
            if self.job:
                raise BusyError(f"'{self.job}' 진행 중")
            self.job = name
            self.stop_requested = False

        def wrap():
            try:
                target()
            except Exception as e:
                self.emit(f"오류: {type(e).__name__}: {e}")
            finally:
                with self.lock:
                    self.job = None

        threading.Thread(target=wrap, daemon=True).start()


class BusyError(Exception):
    pass


S = State()
PASSWORD = None


def vehicle_summary():
    try:
        st = S.vc.state()
    except Exception:
        return None
    if not isinstance(st, dict):
        return None
    body = st.get("body") or {}
    return {
        "gear": ((st.get("gear") or {}).get("actual") or {}).get("value") or "-",
        "speed": (st.get("controls") or {}).get("speed_kph", 0),
        "doors": body.get("doors", {}),
        "wiper": ((body.get("wipers") or {}).get("front") or {}).get("mode", "-"),
        "headlamp": (st.get("lights") or {}).get("headlamp_mode", "-"),
        # Shown as the app sees it through the SDK (see VConsole.SEAT_INVERTED).
        "seats": {k: (not v) if S.vc.SEAT_INVERTED else v for k, v in ((st.get("seat") or {}).get("occupied") or {}).items()},
        "soc": (st.get("ev_battery") or {}).get("state_of_charge"),
    }


def route_points(step_m=15.0):
    import math
    pts = ROUTE
    out = []
    for (a, b), (c, d) in zip(pts, pts[1:]):
        dist = math.hypot((c - a) * 111_000, (d - b) * 88_000)
        n = max(1, int(dist / step_m))
        out += [(a + (c - a) * k / n, b + (d - b) * k / n) for k in range(n)]
    return out + [pts[-1]]


def start_route(speed_kmh):
    stop_route()
    # A fresh event per playback: a previous thread still sleeping keeps seeing its own (set) event.
    stop = S.route_stop = threading.Event()
    pts = route_points()
    delay = 15.0 / (max(5.0, speed_kmh) / 3.6)

    def play():
        for i, (lat, lng) in enumerate(pts):
            if stop.is_set():
                return
            S.dev.geo(lat, lng)
            S.route_progress = f"{i + 1}/{len(pts)}"
            if stop.wait(delay):
                return
        S.route_progress = None

    threading.Thread(target=play, daemon=True).start()


def stop_route():
    S.route_stop.set()
    S.route_progress = None


def emulator_running():
    return "emulator-" in S.dev.run("devices", timeout=10)


def start_emulator():
    if emulator_running():
        return "이미 실행 중"
    subprocess.Popen(
        [EMULATOR, "-avd", AVD, "-no-snapshot", "-no-boot-anim", "-gpu", "host"],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True,
    )
    return "부팅 시작 (약 1–2분)"


def build_and_install():
    S.emit("디버그 APK 빌드 시작")
    env = dict(os.environ, JAVA_HOME=GRADLE_JAVA_HOME)
    p = subprocess.run(["./gradlew", ":app:assembleDebug", "--console=plain", "-q"], cwd=ROOT, env=env,
                       capture_output=True, text=True, timeout=900)
    if p.returncode != 0:
        S.emit("빌드 실패:\n" + (p.stdout + p.stderr)[-2000:])
        return
    S.emit("빌드 완료, 설치 중")
    S.emit(S.dev.install(os.path.join(ROOT, "app/build/outputs/apk/debug/app-debug.apk")).strip()[-200:])


def run_cases(ids):
    stop_route()  # a playing route would keep injecting locations into the cases
    S.results = []
    S.report_dir = None
    results, out = runner.run(ids, dev=S.dev, vc=S.vc, emit=S.emit, stop_flag=lambda: S.stop_requested)
    S.results = results
    S.report_dir = os.path.basename(out)
    passed = sum(r["status"] == "PASS" for r in results)
    S.emit(f"완료: {passed}/{len(results)} 통과 · 리포트 /reports/{S.report_dir}/index.html")


def list_reports():
    out = []
    if os.path.isdir(runner.REPORTS):
        for d in sorted(os.listdir(runner.REPORTS), reverse=True)[:20]:
            f = os.path.join(runner.REPORTS, d, "results.json")
            if os.path.exists(f):
                with open(f, encoding="utf-8") as fh:
                    rs = json.load(fh)
                out.append({"dir": d, "passed": sum(r["status"] == "PASS" for r in rs), "total": len(rs)})
    return out


VEHICLE_OPS = {
    "gear": lambda b: S.vc.gear(b["value"]),
    "door": lambda b: S.vc.door(b["area"], bool(b["opened"])),
    "wiper": lambda b: S.vc.wiper(b["value"]),
    "headlamp": lambda b: S.vc.headlamp(b["value"]),
    "seat": lambda b: S.vc.seat(b["area"], bool(b["occupied"])),
    "soc": lambda b: S.vc.soc(int(b["value"])),
    "speed": lambda b: S.vc.speed(int(b["value"])),
    "reset": lambda b: S.vc.reset(),
}


class Handler(BaseHTTPRequestHandler):
    server_version = "fogqa"

    def log_message(self, fmt, *args):
        pass

    # ---- plumbing ----
    def _authorized(self):
        header = self.headers.get("Authorization", "")
        if not header.startswith("Basic "):
            return False
        try:
            user, _, pw = base64.b64decode(header[6:]).decode().partition(":")
        except Exception:
            return False
        return secrets.compare_digest(user.encode(), USER.encode()) and secrets.compare_digest(pw.encode(), PASSWORD.encode())

    def _deny(self):
        self.send_response(401)
        self.send_header("WWW-Authenticate", 'Basic realm="FogDrive QA", charset="UTF-8"')
        self.send_header("Content-Length", "0")
        self.end_headers()

    def _json(self, obj, status=200):
        data = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _file(self, path, ctype=None):
        if not os.path.isfile(path):
            return self._json({"error": "not found"}, 404)
        with open(path, "rb") as f:
            data = f.read()
        self.send_response(200)
        self.send_header("Content-Type", ctype or mimetypes.guess_type(path)[0] or "application/octet-stream")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _body(self):
        n = int(self.headers.get("Content-Length") or 0)
        return json.loads(self.rfile.read(n) or b"{}") if n else {}

    # ---- GET ----
    def do_GET(self):
        if not self._authorized():
            return self._deny()
        try:
            return self._get()
        except (BrokenPipeError, ConnectionResetError):
            pass
        except Exception as e:  # adb timeouts etc.: answer instead of dropping the connection
            return self._json({"error": f"{type(e).__name__}: {e}"}, 500)

    def _get(self):
        path = urllib.parse.urlparse(self.path).path
        if path == "/":
            return self._file(os.path.join(STATIC, "dashboard.html"), "text/html; charset=utf-8")
        if path == "/stream.h264":
            return self._stream()
        if path == "/screen.png":
            data = S.dev.screencap()
            self.send_response(200)
            self.send_header("Content-Type", "image/png")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            return self.wfile.write(data)
        if path == "/api/status":
            connected = emulator_running()
            if not connected:
                S.screen = None
            elif S.screen is None:
                S.screen = S.dev.screen_size()
            vconsole = S.vc.available()
            if connected and not vconsole:
                # Emulator restarted: the port forward is gone.
                S.dev.forward(8088, 8088)
                vconsole = S.vc.available()
            return self._json({
                "emulator": connected,
                "screen": S.screen,
                "vconsole": vconsole,
                "app": connected and S.dev.app_installed(),
                "vehicle": vehicle_summary(),
                "job": S.job,
                "route": S.route_progress,
            })
        if path == "/api/cases":
            return self._json([{k: getattr(c, k) for k in ("id", "feature", "title", "pre", "steps", "expected")} for c in CASES])
        if path == "/api/job":
            since = int(urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query).get("since", ["0"])[0])
            base, log = S.log_base, list(S.log)
            return self._json({"job": S.job, "log": log[max(0, since - base):], "next": base + len(log),
                               "results": S.results, "report": S.report_dir})
        if path == "/api/reports":
            return self._json(list_reports())
        if path.startswith("/reports/"):
            root = os.path.realpath(runner.REPORTS)
            target = os.path.realpath(os.path.join(root, urllib.parse.unquote(path[len("/reports/"):])))
            if not target.startswith(root + os.sep):
                return self._json({"error": "bad path"}, 400)
            return self._file(target)
        return self._json({"error": "not found"}, 404)

    def _stream(self):
        """Raw H.264 from screenrecord; the page feeds it to JMuxer. screenrecord stops after
        3 minutes, so the page reconnects when the stream ends."""
        proc = S.dev.popen("exec-out", "screenrecord", "--output-format=h264", "--size", "1280x720",
                           "--bit-rate", "4000000", "-")
        self.send_response(200)
        self.send_header("Content-Type", "video/h264")
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Accel-Buffering", "no")  # stop nginx (Synology proxy) from buffering
        self.end_headers()
        try:
            while True:
                chunk = proc.stdout.read1(65536) if hasattr(proc.stdout, "read1") else proc.stdout.read(4096)
                if not chunk:
                    break
                self.wfile.write(chunk)
                self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            pass
        finally:
            proc.kill()
            proc.wait()

    # ---- POST ----
    def do_POST(self):
        if not self._authorized():
            return self._deny()
        path = urllib.parse.urlparse(self.path).path
        try:
            b = self._body()
            if path == "/api/job/stop":
                S.stop_requested = True
                return self._json({"ok": True, "message": "현재 TC가 끝나면 중지"})
            if path == "/api/emulator":
                return self._json({"ok": True, "message": start_emulator()})
            if path == "/api/run":
                ids = b.get("ids") or []
                S.start_job("TC 실행", lambda: run_cases(ids))
                return self._json({"ok": True})
            if path == "/api/build":
                S.start_job("빌드·설치", build_and_install)
                return self._json({"ok": True})
            # Manual controls are blocked while a TC run drives the emulator.
            if S.job == "TC 실행":
                raise BusyError("TC 실행 중에는 수동 조작을 막아 둡니다")
            if path == "/api/tap":
                S.dev.tap(b["x"], b["y"])
            elif path == "/api/swipe":
                S.dev.swipe(b["x1"], b["y1"], b["x2"], b["y2"], b.get("ms", 300))
            elif path == "/api/key":
                S.dev.key(b["name"])
            elif path == "/api/vehicle":
                VEHICLE_OPS[b["op"]](b)
            elif path == "/api/geo":
                S.dev.geo(float(b["lat"]), float(b["lng"]))
            elif path == "/api/route":
                if b.get("action") == "start":
                    start_route(float(b.get("speed", 40)))
                else:
                    stop_route()
            elif path == "/api/app":
                action = b.get("action")
                if action == "start":
                    S.dev.app_start(wait=False)
                elif action == "stop":
                    S.dev.app_stop()
                elif action == "reset":
                    S.dev.app_stop()
                    S.dev.app_reset()
                    S.dev.app_start(wait=False)
                else:
                    raise ValueError(f"unknown app action {action}")
            else:
                return self._json({"error": "not found"}, 404)
            return self._json({"ok": True})
        except BusyError as e:
            return self._json({"ok": False, "error": str(e)}, 409)
        except Exception as e:
            return self._json({"ok": False, "error": f"{type(e).__name__}: {e}"}, 500)


def serve(host="127.0.0.1", port=8765):
    global PASSWORD
    PASSWORD = load_password()
    try:
        S.dev.forward(8088, 8088)
    except Exception:
        pass
    S.vc.start()
    httpd = ThreadingHTTPServer((host, port), Handler)
    httpd.daemon_threads = True
    print(f"FogDrive QA dashboard on http://{host}:{port} (user {USER}, password in {PASSWORD_FILE})", flush=True)
    try:
        httpd.serve_forever()
    finally:
        S.vc.stop()
