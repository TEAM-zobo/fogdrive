"""adb wrapper for the Pleos Connect emulator: UI reading, taps, location, app lifecycle."""
import os
import re
import subprocess
import sys
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
WINDOWS = os.name == "nt"


def sdk_dir():
    """Same order as Gradle: local.properties, then ANDROID_HOME / ANDROID_SDK_ROOT, then the
    Android Studio default."""
    props = os.path.join(ROOT, "local.properties")
    if os.path.exists(props):
        with open(props, encoding="utf-8") as f:
            for line in f:
                if line.startswith("sdk.dir="):
                    # local.properties escapes Windows paths (C\:\\Users\\...).
                    return line[len("sdk.dir="):].strip().replace("\\:", ":").replace("\\\\", "\\")
    for key in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        if os.environ.get(key):
            return os.environ[key]
    if WINDOWS:
        return os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk")
    if sys.platform == "darwin":
        return os.path.expanduser("~/Library/Android/sdk")
    return os.path.expanduser("~/Android/Sdk")


def sdk_tool(*parts):
    path = os.path.join(sdk_dir(), *parts)
    return path + ".exe" if WINDOWS else path


DEFAULT_ADB = sdk_tool("platform-tools", "adb")


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


SCREEN_SERIAL = "emulator-5554"  # the emulator shown on screen (first instance)


class Device:
    def __init__(self, adb=None, serial=None):
        self.adb = adb or os.environ.get("ADB") or DEFAULT_ADB
        # Always address one emulator explicitly: with the headless QA instance running,
        # serial-less adb commands fail with "more than one device".
        self.serial = serial or os.environ.get("FOGQA_SERIAL") or SCREEN_SERIAL

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

    def ui_nodes(self, attempts=3):
        """Current UI tree. The old dump is deleted first: a failed dump (e.g. "could not get idle
        state" while something animates) must not be read back as the current screen."""
        raw = ""
        for _ in range(attempts):
            self.shell("rm", "-f", "/sdcard/fogqa_ui.xml")
            self.shell("uiautomator", "dump", "/sdcard/fogqa_ui.xml")
            raw = self.shell("cat", "/sdcard/fogqa_ui.xml")
            if "<?xml" in raw:
                break
            time.sleep(0.5)
        start = raw.find("<?xml")
        if start < 0:
            raise RuntimeError("UI 트리를 읽지 못함 (uiautomator dump 실패)")
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
            try:
                found = self.find(text, exact=exact) is not None
            except RuntimeError:  # dump failed (screen not idle): unknown, keep polling
                time.sleep(0.5)
                continue
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
            try:
                node = self.find(text, exact=exact)
            except RuntimeError:  # dump failed (screen not idle): keep polling
                node = None
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

    def app_start(self, wait=True, tutorial=False):
        """Starts the app; unless [tutorial], with the first-run tutorial marked as seen."""
        extra = [] if tutorial else ["--ez", "skip_onboarding", "true"]
        self.shell("am", "start", "--user", "current", "-n", ACTIVITY, *extra)
        if wait and not self.wait_text("지도", timeout=15, exact=True):
            raise AssertionError("앱 화면이 뜨지 않음")


EMULATOR = sdk_tool("emulator", "emulator")
QA_AVD = "Pleos_Connect_QA"
QA_SERIAL = "emulator-5556"
# Home position (경기 고양시 덕양구 향기5로 66), where every demo course starts; set after boot.
HOME = (37.60852, 126.89592)


def boot_headless(avd=QA_AVD, serial=QA_SERIAL, timeout=300, emit=print):
    """Boots a separate AVD without a window so TC runs never touch the emulator on screen.
    A separate AVD (own userdata) is used instead of -read-only, which would force every
    instance, including the one on screen, to discard its data."""
    dev = Device(serial=serial)
    if serial in dev.run("devices", timeout=10):
        return dev
    port = serial.split("-")[1]
    emit(f"{avd} 창 없이 부팅 ({serial})")
    subprocess.Popen(
        [EMULATOR, "-avd", avd, "-no-window", "-port", port, "-no-snapshot", "-no-boot-anim", "-gpu", "host", "-no-audio"],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True,
    )
    end = time.time() + timeout
    while time.time() < end:
        if dev.shell("getprop", "sys.boot_completed", timeout=10).strip() == "1":
            time.sleep(5)  # vehicle/navi services finish starting after boot_completed
            dev.geo(*HOME)
            return dev
        time.sleep(5)
    raise RuntimeError(f"{avd} 부팅 시간 초과")


def shutdown(dev):
    dev.run("emu", "kill", timeout=30)


def demo_route():
    """Road path of the default demo course (single source: DemoCourses.kt, SEONGSU_ROUTE)."""
    src = os.path.join(ROOT, "app/src/main/java/com/zobo/fogdrive/demo/DemoCourses.kt")
    with open(src, encoding="utf-8") as f:
        body = f.read().split("val SEONGSU_ROUTE", 1)[1].split("\n    )", 1)[0]
    return [(float(a), float(b)) for a, b in re.findall(r"(\d+\.\d+) to (\d+\.\d+)", body)]
