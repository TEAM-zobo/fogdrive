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
    sub.add_parser("list", help="TC 목록")
    sub.add_parser("tc-doc", help="docs/test-cases.html 생성")
    a = p.parse_args()

    if a.cmd == "serve":
        from .server import serve
        serve(a.host, a.port)
    elif a.cmd == "run":
        from .runner import run
        results, out = run(a.ids)
        failed = [r for r in results if r["status"] != "PASS"]
        print(f"\n{len(results) - len(failed)}/{len(results)} 통과 · 리포트: {os.path.join(out, 'index.html')}")
        sys.exit(1 if failed else 0)
    elif a.cmd == "list":
        from .cases import CASES
        for c in CASES:
            print(f"{c.id}  [{c.feature}] {c.title}")
    elif a.cmd == "tc-doc":
        from .runner import tc_doc
        print(tc_doc(os.path.join(ROOT, "docs", "test-cases.html")))


if __name__ == "__main__":
    main()
