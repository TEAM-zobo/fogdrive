"""Client for the Pleos Connect Virtual Console HTTP API (emulator port 8088).

The console only accepts writes while a browser session is alive, so a heartbeat thread
posts the same session ping the web page sends every second.
"""
import json
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

DOORS = {"front_left": "door_fl", "front_right": "door_fr", "rear_left": "door_rl", "rear_right": "door_rr"}


class VConsole:
    def __init__(self, base="http://127.0.0.1:8088", client="fogqa"):
        self.base = base
        self.client = client
        self._stop = threading.Event()
        self._thread = None

    def _req(self, path, method="GET", params=None, timeout=5):
        url = self.base + path
        if params:
            url += ("&" if "?" in url else "?") + urllib.parse.urlencode(params)
        data = b"" if method != "GET" else None
        req = urllib.request.Request(url, method=method, data=data)
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                body = r.read()
                return r.status, (json.loads(body) if body.strip().startswith((b"{", b"[")) else body)
        except urllib.error.HTTPError as e:
            return e.code, e.read()[:300]

    def start(self):
        if self._thread and self._thread.is_alive():
            return
        self._stop.clear()

        def beat():
            while not self._stop.is_set():
                try:
                    self._req("/browser/session", "POST", {"client": self.client}, timeout=3)
                except Exception:
                    pass
                self._stop.wait(1.0)

        self._thread = threading.Thread(target=beat, daemon=True)
        self._thread.start()
        time.sleep(1.5)

    def stop(self):
        self._stop.set()

    def available(self):
        try:
            return self._req("/vehicle/state", timeout=3)[0] == 200
        except Exception:
            return False

    def state(self):
        return self._req("/vehicle/state")[1]

    def put(self, path, **params):
        status, body = self._req(path, "PUT", params)
        if status != 200:
            raise RuntimeError(f"Virtual Console {path} {params} -> {status} {body}")
        return body

    # ---- typed helpers (values as the console catalog defines them) ----
    def gear(self, value, timeout=15.0):  # Park | Reverse | Neutral | Drive
        """Requests a gear and waits until the console reports it. P is refused while the
        simulated car is still rolling, so the request is repeated until it sticks."""
        end = time.time() + timeout
        while True:
            self.put("/vehicle/gear", gear=value)
            time.sleep(0.5)
            actual = ((self.state().get("gear") or {}).get("actual") or {}).get("value")
            if actual == value:
                return actual
            if time.time() > end:
                raise RuntimeError(f"기어 {value} 전환 실패 (현재 {actual})")
            time.sleep(0.5)

    def door(self, area, opened):
        return self.put("/vehicle/body", **{DOORS[area]: "true" if opened else "false"})

    def wiper(self, mode):  # Off | Auto | Low | High
        return self.put("/vehicle/body", front_wiper=mode)

    def headlamp(self, mode):  # Off | Auto | Position | LowBeam
        return self.put("/vehicle/lights", feature="headlamp_mode", value=mode)

    # Emulator quirk (Pleos Connect Emulator 2.1.0): the console's seat "occupied" flag reaches the
    # Vehicle SDK inverted (console true -> getCurrentSeatOccupancy false). `occupied` here means what
    # the app should see through the SDK.
    SEAT_INVERTED = True

    def seat(self, area, occupied):  # driver | passenger | rear_left | rear_right
        sent = (not occupied) if self.SEAT_INVERTED else occupied
        return self.put("/vehicle/seat", area=area, kind="occupied", value="true" if sent else "false")

    def soc(self, percent):
        return self.put("/vehicle/ev-battery", field="soc", value=str(percent))

    def window(self, area, percent):  # front_left | front_right | rear_left | rear_right, 0 (closed)..100
        field = {"front_left": "window_fl", "front_right": "window_fr", "rear_left": "window_rl", "rear_right": "window_rr"}[area]
        return self.put("/vehicle/body", **{field: str(percent)})

    def range_km(self, km):
        return self.put("/vehicle/odometer", remaining_range_m=str(int(km * 1000)))

    def speed(self, kph):
        return self.put("/vehicle/controls", speed=str(kph), accelerator="10" if kph else "0", brake="0", steering="0")

    def reset(self):
        """Parked, doors/windows/frunk/trunk/charge port closed, wipers/lights off, no passengers, 80% battery, standing still."""
        self.speed(0)
        self.gear("Park")
        for area in DOORS:
            self.door(area, False)
        # The emulator boots with the frunk open (cluster warning "프렁크를 닫으세요").
        self.put("/vehicle/body", frunk="false", trunk="false")
        self.wiper("Off")
        self.headlamp("Off")
        for area in ("passenger", "rear_left", "rear_right"):
            self.seat(area, False)
        self.soc(80)
        for area in ("front_left", "front_right", "rear_left", "rear_right"):
            self.window(area, 0)
        # Also boots with the charge port open (cluster icon).
        self.put("/vehicle/ev-battery", field="charge_port_open", value="false")
