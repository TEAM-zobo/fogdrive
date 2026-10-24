"""python3 -m fogqa {serve|run|list|tc-doc}"""
import argparse
import os
import sys

from .device import ROOT


def main():
    p = argparse.ArgumentParser(prog="fogqa", description="FogDrive QA tools")
    sub = p.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("serve", help="웹 대시보드 실행")
    s.add_argument("--port", type=int, default=8765)
    s.add_argument("--host", default="127.0.0.1")
    r = sub.add_parser("run", help="TC 실행 (기본: 전체)")
    r.add_argument("ids", nargs="*", help="예: TC-03 TC-05")
    r.add_argument("--serial", help="대상 에뮬레이터 (기본: 연결된 하나)")
    r.add_argument("--vc-port", type=int, default=None, help="Virtual Console 로컬 포트 (기본 8088, --headless는 18088)")
    r.add_argument("--headless", action="store_true", help="QA 전용 AVD를 창 없이 띄워 실행 (화면 에뮬레이터와 분리)")
    r.add_argument("--install", help="실행 전에 설치할 APK 경로")
    r.add_argument("--shutdown", action="store_true", help="--headless 실행 후 QA 에뮬레이터 종료")
    sub.add_parser("list", help="TC 목록")
    pf = sub.add_parser("perf", help="주행 모드 CPU·메모리 측정")
    pf.add_argument("--kmh", type=int, nargs="*", default=[40, 100])
    pf.add_argument("--headless", action="store_true")
    pf.add_argument("--shutdown", action="store_true", help="--headless 측정 후 QA 에뮬레이터 종료")
    sub.add_parser("tc-doc", help="docs/test-cases.html 생성")
    a = p.parse_args()

    if a.cmd == "serve":
        from .server import serve
        serve(a.host, a.port)
    elif a.cmd == "run":
        from .device import Device, boot_headless, shutdown
        from .runner import run
        if a.headless:
            dev = boot_headless()
            port = a.vc_port or 18088
        else:
            dev = Device(serial=a.serial)
            port = a.vc_port or 8088
        apk = a.install or (os.path.join(ROOT, "app/build/outputs/apk/debug/app-debug.apk") if a.headless else None)
        if apk:
            print(f"설치: {apk}")
            dev.install(apk)
        try:
            results, out = run(a.ids, dev=dev, vc_port=port)
        finally:
            if a.headless and a.shutdown:
                shutdown(dev)
        failed = [r for r in results if r["status"] != "PASS"]
        print(f"\n{len(results) - len(failed)}/{len(results)} 통과 · 리포트: {os.path.join(out, 'index.html')}")
        sys.exit(1 if failed else 0)
    elif a.cmd == "perf":
        from .device import Device, boot_headless, shutdown
        from .perf import driving_benchmark
        from .vconsole import VConsole
        dev = boot_headless() if a.headless else Device()
        port = 18088 if a.headless else 8088
        dev.forward(port, 8088)
        vc = VConsole(base=f"http://127.0.0.1:{port}")
        vc.start()
        try:
            for kmh in a.kmh:
                driving_benchmark(dev, vc, kmh)
        finally:
            vc.stop()
            if a.headless and a.shutdown:
                shutdown(dev)
    elif a.cmd == "list":
        from .cases import CASES
        for c in CASES:
            print(f"{c.id}  [{c.feature}] {c.title}")
    elif a.cmd == "tc-doc":
        from .runner import tc_doc
        print(tc_doc(os.path.join(ROOT, "docs", "test-cases.html")))


if __name__ == "__main__":
    main()
