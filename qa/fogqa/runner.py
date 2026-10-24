"""Runs test cases against the emulator and writes an HTML report with screenshots."""
import html
import json
import os
import time
import traceback
from datetime import datetime

from .cases import CASES, CaseFailed
from .device import ROOT, SCREEN_SERIAL, Device
from .vconsole import VConsole

REPORTS = os.path.join(ROOT, "qa", "reports")


class Context:
    def __init__(self, dev, vc, out_dir, case, emit):
        self.dev = dev
        self.vc = vc
        self.out_dir = out_dir
        self.case = case
        self.emit = emit
        self.logs = []
        self.checks = []
        self.shots = []
        self.attempt = 1

    def log(self, msg):
        self.logs.append(msg)
        self.emit(f"[{self.case.id}] {msg}")

    def check(self, ok, what):
        self.checks.append((bool(ok), what))
        self.emit(f"[{self.case.id}] {'✔' if ok else '✘'} {what}")
        if not ok:
            raise CaseFailed(what)

    def shot(self, name):
        # Retry attempts get their own file names so the first attempt's shots are not overwritten.
        tag = f"-try{self.attempt}" if self.attempt > 1 else ""
        fname = f"{self.case.id}{tag}-{len(self.shots) + 1:02d}-{name}.png"
        self.dev.save_screenshot(os.path.join(self.out_dir, fname))
        self.shots.append(fname)


def select(ids=None):
    if not ids:
        return list(CASES)
    wanted = {i.upper() for i in ids}
    return [c for c in CASES if c.id in wanted]


def run(ids=None, dev=None, vc=None, emit=print, stop_flag=None, vc_port=8088):
    """Runs the cases and returns (results, report_dir). `stop_flag()` true stops before the next case.
    `vc_port` is the local port forwarded to this emulator's Virtual Console (8088 on the device)."""
    dev = dev or Device()
    own_vc = vc is None
    vc = vc or VConsole(base=f"http://127.0.0.1:{vc_port}")
    dev.forward(vc_port, 8088)
    if own_vc:
        vc.start()
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S") + (f"-{dev.serial}" if dev.serial != SCREEN_SERIAL else "")
    out_dir = os.path.join(REPORTS, stamp)
    os.makedirs(out_dir, exist_ok=True)
    results = []
    try:
        for c in select(ids):
            if stop_flag and stop_flag():
                emit("중지 요청으로 남은 TC를 건너뜀")
                break
            emit(f"[{c.id}] 시작: {c.title}")
            t0 = time.time()
            # Emulators occasionally stall (black screen, dump timeouts) under memory pressure, so a
            # non-passing case is retried once; the first attempt's error stays in the report.
            first_error = ""
            first_logs, first_shots = [], []
            for attempt in (1, 2):
                ctx = Context(dev, vc, out_dir, c, emit)
                ctx.attempt = attempt
                status, error = _attempt(c, ctx, vc)
                if status == "PASS" or attempt == 2:
                    break
                first_error = f"{status}: {error}"
                # Keep the first attempt's evidence (failure screenshot, traceback) in the report.
                first_logs = [f"[1차] {l}" for l in ctx.logs]
                first_shots = list(ctx.shots)
                emit(f"[{c.id}] 재시도 (1차 {first_error})")
            r = {
                "id": c.id, "feature": c.feature, "title": c.title, "status": status, "error": error,
                "retried_after": first_error,
                "seconds": round(time.time() - t0, 1), "checks": ctx.checks, "logs": first_logs + ctx.logs, "shots": first_shots + ctx.shots,
            }
            results.append(r)
            emit(f"[{c.id}] {status} ({r['seconds']}s){' - ' + error if error else ''}")
            write_report(out_dir, results)
    finally:
        if own_vc:
            vc.stop()
    return results, out_dir


def _attempt(c, ctx, vc):
    status, error = "PASS", ""
    try:
        c.fn(ctx)
    except CaseFailed as e:
        status, error = "FAIL", str(e)
        _safe_shot(ctx, "failure")
    except Exception as e:  # infrastructure problem, not an app assertion
        status, error = "ERROR", f"{type(e).__name__}: {e}"
        ctx.logs.append(traceback.format_exc())
        _safe_shot(ctx, "error")
    finally:
        try:
            vc.reset()
        except Exception:
            pass
    return status, error


def _safe_shot(ctx, name):
    try:
        ctx.shot(name)
    except Exception:
        pass


def write_report(out_dir, results):
    with open(os.path.join(out_dir, "results.json"), "w", encoding="utf-8") as f:
        json.dump(results, f, ensure_ascii=False, indent=1)
    passed = sum(r["status"] == "PASS" for r in results)
    rows = []
    for r in results:
        checks = "".join(
            f"<li class='{'ok' if ok else 'ng'}'>{'✔' if ok else '✘'} {html.escape(w)}</li>" for ok, w in r["checks"]
        )
        logs = "".join(f"<li>{html.escape(str(l))}</li>" for l in r["logs"])
        shots = "".join(f"<a href='{s}' target='_blank'><img src='{s}' loading='lazy'></a>" for s in r["shots"])
        err = f"<p class='err'>{html.escape(r['error'])}</p>" if r["error"] else ""
        if r.get("retried_after"):
            err += f"<p class='retry'>재시도함 · 1차 결과: {html.escape(r['retried_after'])}</p>"
        rows.append(
            f"<section class='case {r['status'].lower()}'><h2><span class='badge'>{r['status']}</span> "
            f"{r['id']} · {html.escape(r['feature'])} — {html.escape(r['title'])} <small>{r['seconds']}s</small></h2>"
            f"{err}<ul class='checks'>{checks}</ul><details><summary>로그</summary><ul>{logs}</ul></details>"
            f"<div class='shots'>{shots}</div></section>"
        )
    page = f"""<!doctype html><html lang="ko"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1"><title>FogDrive QA 리포트</title>
<style>
:root{{--bg:#f6f7f9;--card:#fff;--text:#151a21;--sub:#5b6676;--line:#dde2e9;--ok:#0a9b72;--ng:#c0392b;--er:#b4610a}}
@media (prefers-color-scheme: dark){{:root{{--bg:#0e1218;--card:#161c25;--text:#eef2f6;--sub:#9aa6b5;--line:#2a3340;--ok:#5ee6b8;--ng:#ff8a7a;--er:#ffb547}}}}
body{{margin:0;background:var(--bg);color:var(--text);font:15px/1.6 -apple-system,"Apple SD Gothic Neo",sans-serif}}
main{{max-width:1100px;margin:0 auto;padding:24px 16px}} h1{{margin:0 0 4px}} .sub{{color:var(--sub)}}
.case{{background:var(--card);border:1px solid var(--line);border-radius:14px;padding:14px 16px;margin:12px 0}}
.case h2{{font-size:16px;margin:0 0 6px}} small{{color:var(--sub);font-weight:400}}
.badge{{display:inline-block;padding:1px 8px;border-radius:999px;color:#fff;font-size:12px}}
.pass .badge{{background:var(--ok)}} .fail .badge{{background:var(--ng)}} .error .badge{{background:var(--er)}}
.retry{{color:var(--er)}} .checks{{list-style:none;padding:0;margin:6px 0}} .ok{{color:var(--ok)}} .ng{{color:var(--ng)}} .err{{color:var(--ng)}}
.shots{{display:flex;gap:8px;flex-wrap:wrap}} .shots img{{width:320px;max-width:100%;border-radius:8px;border:1px solid var(--line)}}
</style></head><body><main><h1>FogDrive QA 리포트</h1>
<p class="sub">{os.path.basename(out_dir)} · {passed}/{len(results)} 통과</p>{''.join(rows)}</main></body></html>"""
    with open(os.path.join(out_dir, "index.html"), "w", encoding="utf-8") as f:
        f.write(page)


def tc_doc(path):
    """Writes the human-readable test case list (docs/test-cases.html) from CASES."""
    rows = []
    for c in CASES:
        steps = "".join(f"<li>{html.escape(s)}</li>" for s in c.steps)
        exp = "".join(f"<li>{html.escape(s)}</li>" for s in c.expected)
        rows.append(
            f"<tr><td class='id'>{c.id}</td><td>{html.escape(c.feature)}</td><td><b>{html.escape(c.title)}</b>"
            f"<div class='pre'>사전 조건: {html.escape(c.pre)}</div></td><td><ol>{steps}</ol></td><td><ul>{exp}</ul></td>"
            f"<td><span class='tag'>자동</span></td></tr>"
        )
    page = f"""<!doctype html>
<html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>안개지도 테스트 케이스</title>
<style>
:root{{--bg:#f6f7f9;--surface:#fff;--surface-2:#eef1f5;--text:#151a21;--sub:#5b6676;--line:#dde2e9;--accent:#0a9b72;--accent-soft:#e2f6ef}}
@media (prefers-color-scheme: dark){{:root:not([data-theme="light"]){{--bg:#0e1218;--surface:#161c25;--surface-2:#1f2733;--text:#eef2f6;--sub:#9aa6b5;--line:#2a3340;--accent:#5ee6b8;--accent-soft:#143329}}}}
:root[data-theme="dark"]{{--bg:#0e1218;--surface:#161c25;--surface-2:#1f2733;--text:#eef2f6;--sub:#9aa6b5;--line:#2a3340;--accent:#5ee6b8;--accent-soft:#143329}}
*{{box-sizing:border-box}} body{{margin:0;background:var(--bg);color:var(--text);font:15px/1.6 -apple-system,"Apple SD Gothic Neo","Noto Sans KR",sans-serif}}
main{{max-width:1200px;margin:0 auto;padding:28px 16px 60px}} h1{{font-size:26px;margin:0 0 6px}} .sub{{color:var(--sub)}}
.wrap{{overflow-x:auto;background:var(--surface);border:1px solid var(--line);border-radius:14px;margin-top:16px}}
table{{width:100%;border-collapse:collapse}} th,td{{text-align:left;vertical-align:top;padding:10px 12px;border-bottom:1px solid var(--line)}}
th{{background:var(--surface-2);color:var(--sub);font-size:13px;white-space:nowrap}} tr:last-child td{{border-bottom:none}}
td.id{{font-weight:700;white-space:nowrap}} ol,ul{{margin:0;padding-left:18px}} .pre{{color:var(--sub);font-size:13px;margin-top:4px}}
.tag{{background:var(--accent-soft);color:var(--accent);border-radius:999px;padding:2px 8px;font-size:12px;font-weight:700;white-space:nowrap}}
code{{background:var(--surface-2);padding:1px 6px;border-radius:6px}}
</style></head><body><main>
<h1>안개지도 기능별 테스트 케이스</h1>
<p class="sub">{len(CASES)}개 TC · 모두 자동화. 이 문서는 <code>qa/fogqa/cases.py</code>에서 생성됩니다(<code>python3 -m fogqa tc-doc</code>).</p>
<p>모든 TC는 Pleos Connect Emulator에서 실제 SDK 경로로 실행됩니다. 차량 신호는 Virtual Console API로, 위치는 <code>adb emu geo fix</code>로 주입하고, 화면은 UIAutomator로 판독합니다. 각 TC는 앱 데이터 초기화와 차량 상태 초기화(P단, 도어 닫힘, 와이퍼·전조등 끔, 동승자 없음)에서 시작합니다.</p>
<div class="wrap"><table><thead><tr><th>ID</th><th>기능</th><th>테스트</th><th>단계</th><th>기대 결과</th><th>방식</th></tr></thead>
<tbody>{''.join(rows)}</tbody></table></div>
<p class="sub"><a href="index.html">공모전 준비 가이드로 돌아가기</a></p>
</main></body></html>"""
    with open(path, "w", encoding="utf-8") as f:
        f.write(page)
    return path
