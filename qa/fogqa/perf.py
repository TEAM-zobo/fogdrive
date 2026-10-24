"""CPU/memory measurement of the app on the emulator (per-thread, from /proc)."""
import threading
import time

from .device import PACKAGE


def thread_ticks(dev, pid):
    out = {}
    for tid in dev.shell("ls", f"/proc/{pid}/task").split():
        st = dev.shell("cat", f"/proc/{pid}/task/{tid}/stat")
        if ")" not in st:
            continue
        name = st[st.find("(") + 1:st.rfind(")")]
        f = st.split(")")[-1].split()
        out[tid] = (name, int(f[11]) + int(f[12]))
    return out


def measure(dev, seconds=30, cores=4):
    """Returns (total % of one core, % of all cores, top threads, PSS MB) over `seconds`."""
    pid = dev.shell("pidof", PACKAGE).strip()
    a = thread_ticks(dev, pid)
    t0 = time.time()
    time.sleep(seconds)
    b = thread_ticks(dev, pid)
    wall = time.time() - t0
    rows = sorted((((b[t][1] - a.get(t, (0, 0))[1]) / 100 / wall * 100), b[t][0]) for t in b)
    total = sum(r[0] for r in rows)
    pss = 0
    for line in dev.shell("dumpsys", "meminfo", pid).splitlines():
        if "TOTAL PSS" in line:
            pss = int(line.split()[2]) // 1024
            break
    return total, total / cores, list(reversed(rows[-5:])), pss


class Feeder:
    """Injects one location per second along a route (simulated driving)."""

    def __init__(self, dev, route, interval=1.0):
        self.dev, self.route, self.interval = dev, route, interval
        self._stop = threading.Event()

    def __enter__(self):
        def run():
            i = 0
            while not self._stop.is_set():
                self.dev.geo(*self.route[i % len(self.route)])
                i += 1
                self._stop.wait(self.interval)
        threading.Thread(target=run, daemon=True).start()
        return self

    def __exit__(self, *exc):
        self._stop.set()


def driving_benchmark(dev, vc, kmh=40, seconds=40, emit=print):
    """Driving-mode CPU/PSS with locations fed at `kmh` along the demo route (one fix per second)."""
    import re
    from . import cases
    from .server import route_points

    pts = route_points(step_m=kmh / 3.6)
    vc.reset()
    dev.app_stop()
    dev.app_reset()
    dev.geo(*cases.START)
    dev.app_start()
    dev.tap_text("설정")
    dev.wait_text("실행 중", 10, True)
    time.sleep(2)
    vc.gear("Drive")
    vc.speed(kmh)
    dev.wait_text("주행 모드", 10, True)
    try:
        with Feeder(dev, pts):
            time.sleep(6)
            total, all_cores, top, pss = measure(dev, seconds)
    finally:
        vc.speed(0)
        vc.gear("Park")
    emit(f"{kmh}km/h 주행 모드: CPU {all_cores:.1f}% (4코어 기준, 한 코어 기준 {total:.1f}%), PSS {pss}MB, "
         f"상위 스레드 {[(round(c, 1), n) for c, n in top[:3]]}")
    return all_cores, pss
