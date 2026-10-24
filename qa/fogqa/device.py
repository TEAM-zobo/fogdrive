"""adb wrapper for the Pleos Connect emulator: UI reading, taps, location, app lifecycle."""
import os
import re
import subprocess
import time
import xml.etree.ElementTree as ET

PACKAGE = "com.zobo.fogdrive"
ACTIVITY = f"{PACKAGE}/.ui.MainActivity"
RUNTIME_PERMISSIONS = [
    "android.permission.ACCESS_FINE_LOCATION",
    "android.permission.ACCESS_COARSE_LOCATION",
    "android.permission.POST_NOTIFICATIONS",
    "pleos.car.permission.FUSED_LOCATION",
]
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
DEFAULT_ADB = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")


def _bounds(s):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", s))
    return x1, y1, x2, y2


class UiNode:
    def __init__(self, text, desc, bounds):
        self.text = text
        self.desc = desc
        self.bounds = bounds

    @property
    def center(self):
        x1, y1, x2, y2 = self.bounds
        return (x1 + x2) // 2, (y1 + y2) // 2


class Device:
    def __init__(self, adb=None, serial=None):
        self.adb = adb or os.environ.get("ADB") or DEFAULT_ADB
        self.serial = serial

    def _cmd(self, *args):
        base = [self.adb] + (["-s", self.serial] if self.serial else [])
        return base + list(args)

    def run(self, *args, timeout=60, check=False):
        p = subprocess.run(self._cmd(*args), capture_output=True, timeout=timeout)
        if check and p.returncode != 0:
            raise RuntimeError(f"adb {' '.join(args)} failed: {p.stderr.decode(errors='ignore')}")
        return p.stdout.decode("utf-8", errors="ignore")

    def shell(self, *args, timeout=60):
        return self.run("shell", *args, timeout=timeout)

    def popen(self, *args):
        return subprocess.Popen(self._cmd(*args), stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)

    # ---- state ----
    def connected(self):
        return "device" in self.run("get-state", timeout=10)

    def forward(self, local, remote):
        self.run("forward", f"tcp:{local}", f"tcp:{remote}")

    def screen_size(self):
        m = re.search(r"(\d+)x(\d+)", self.shell("wm", "size"))
        return (int(m.group(1)), int(m.group(2))) if m else (2560, 1440)

    def screencap(self):
        p = subprocess.run(self._cmd("exec-out", "screencap", "-p"), capture_output=True, timeout=30)
        return p.stdout

    def region_brightness(self, x1, y1, x2, y2, step=16):
        """Mean and spread of luminance (0–255) over a screen region, from a raw screencap.
        Used where the UI tree cannot see the content (e.g. the system's driving block overlay)."""
        raw = subprocess.run(self._cmd("exec-out", "screencap"), capture_output=True, timeout=30).stdout
        w = int.from_bytes(raw[0:4], "little")
        h = int.from_bytes(raw[4:8], "little")
        header = len(raw) - w * h * 4  # 12 or 16 bytes depending on Android version
        if len(raw) < 12 or not w or not h or header < 12:
            raise RuntimeError(f"raw screencap unreadable ({len(raw)} bytes)")
        values = []
        for y in range(max(0, y1), min(h, y2), step):
            row = header + y * w * 4
            for x in range(max(0, x1), min(w, x2), step):
                i = row + x * 4
                r, g, b = raw[i], raw[i + 1], raw[i + 2]
                values.append(0.299 * r + 0.587 * g + 0.114 * b)
        mean = sum(values) / len(values)
        spread = (sum((v - mean) ** 2 for v in values) / len(values)) ** 0.5
        return mean, spread

    def save_screenshot(self, path):
        data = self.screencap()
        with open(path, "wb") as f:
            f.write(data)
        return path

    def ui_nodes(self):
        self.shell("uiautomator", "dump", "/sdcard/fogqa_ui.xml")
        raw = self.shell("cat", "/sdcard/fogqa_ui.xml")
        start = raw.find("<?xml")
        if start < 0:
            return []
        nodes = []
        try:
            root = ET.fromstring(raw[start:])
        except ET.ParseError:
            return []
        for el in root.iter("node"):
            text = el.get("text") or ""
            desc = el.get("content-desc") or ""
            if text or desc:
                nodes.append(UiNode(text, desc, _bounds(el.get("bounds", "[0,0][0,0]"))))
        return nodes

    def texts(self):
        return [n.text for n in self.ui_nodes() if n.text]

    def find(self, text, exact=True, nodes=None):
        for n in nodes if nodes is not None else self.ui_nodes():
            for value in (n.text, n.desc):
                if value and (value == text if exact else text in value):
                    return n
        return None

    def wait_text(self, text, timeout=10.0, exact=False, present=True):
        end = time.time() + timeout
        while time.time() < end:
            found = self.find(text, exact=exact) is not None
            if found == present:
                return True
            time.sleep(0.5)
        return False

    def top_activity(self):
        out = self.shell("dumpsys", "activity", "activities")
        # e.g. topResumedActivity=ActivityRecord{76b3727 u10 com.zobo.fogdrive/.ui.MainActivity t1000017}
        m = re.search(r"topResumedActivity=ActivityRecord\{\S+ \S+ (\S+)", out)
        return m.group(1) if m else ""

    # ---- input ----
    def tap(self, x, y):
        self.shell("input", "tap", str(int(x)), str(int(y)))

    def tap_text(self, text, exact=True, timeout=5.0):
        end = time.time() + timeout
        while time.time() < end:
            node = self.find(text, exact=exact)
            if node:
                self.tap(*node.center)
                return True
            time.sleep(0.5)
        raise AssertionError(f"'{text}' 버튼을 찾지 못함")

    def swipe(self, x1, y1, x2, y2, ms=300):
        self.shell("input", "swipe", *map(str, map(int, (x1, y1, x2, y2, ms))))

    def key(self, name):
        codes = {"back": "4", "home": "3"}
        self.shell("input", "keyevent", codes.get(name, name))

    def geo(self, lat, lng):
        # Emulator console expects longitude first.
        self.run("emu", "geo", "fix", f"{lng:.6f}", f"{lat:.6f}")

    # ---- app lifecycle ----
    def install(self, apk):
        return self.run("install", "-r", "-g", apk, timeout=300, check=True)

    def app_installed(self):
        return PACKAGE in self.shell("pm", "list", "packages", PACKAGE)

    def app_stop(self):
        self.shell("am", "force-stop", PACKAGE)

    def app_reset(self):
        """Wipes app data (DB, prefs) and re-grants runtime permissions."""
        # AAOS runs apps as the driver profile (user 10), not user 0.
        self.shell("pm", "clear", "--user", "current", PACKAGE)
        for perm in RUNTIME_PERMISSIONS:
            self.shell("pm", "grant", "--user", "current", PACKAGE, perm)

    def app_start(self, wait=True):
        self.shell("am", "start", "--user", "current", "-n", ACTIVITY)
        if wait and not self.wait_text("지도", timeout=15, exact=True):
            raise AssertionError("앱 화면이 뜨지 않음")


def demo_route():
    """Road path of the in-app demo drive (single source: DemoDrive.kt)."""
    src = os.path.join(ROOT, "app/src/main/java/com/zobo/fogdrive/demo/DemoDrive.kt")
    with open(src, encoding="utf-8") as f:
        body = f.read().split("val ROUTE", 1)[1]
    return [(float(a), float(b)) for a, b in re.findall(r"(\d+\.\d+) to (\d+\.\d+)", body)]
