#!/usr/bin/env python3
"""Fills the contest application form (접수양식 DOCX) from docs/submission/02-app-introduction.md.

    python3 tools/submission/build_form.py

Reads   docs/submission/template/contest-application-consent.docx  (the official form, unchanged)
        docs/submission/02-app-introduction.md                     (sections "## 1." .. "## 8.")
Writes  docs/submission/application-form.docx

Only non-personal fields are filled: section 02 (1-8), the app name lines, the team name and the
category. Personal fields in 01, the consent in 03 and the signatures in 04 stay blank on purpose.
Standard library only.
"""
import os
import re
import struct
import zipfile
from xml.sax.saxutils import escape

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
SUB = os.path.join(ROOT, "docs", "submission")
TEMPLATE = os.path.join(SUB, "template", "contest-application-consent.docx")
SOURCE = os.path.join(SUB, "02-app-introduction.md")
OUT = os.path.join(SUB, "application-form.docx")

APP_NAME = "안개지도(FogDrive)"
TEAM = "zobo"
# The input boxes of section 02, in order 1..8 (the tags are the form's own content-control tags).
TAGS = ["app_summary", "planning_background", "vehicle_scenario", "key_features",
        "pleos_plan", "reference_files", "reference_link", "shortform_link"]
CELL_DXA = 9866 - 220           # input box width minus its cell margins
IMAGE_WIDTH_IN = 5.6
EMU_PER_IN = 914400

FONT_RPR = '<w:lang w:eastAsia="ko-KR"/>'


# ---------------------------------------------------------------- markdown subset -> WordprocessingML

def runs(text, size=None, color=None, bold=False):
    """**bold**, `code` and 【기입 필요】 (highlighted) inside one line."""
    out = []
    for part in re.split(r"(\*\*[^*]+\*\*|`[^`]+`|【[^】]*】)", text):
        if not part:
            continue
        b, code, todo = bold, False, False
        if part.startswith("**") and part.endswith("**"):
            part, b = part[2:-2], True
        elif part.startswith("`") and part.endswith("`"):
            part, code = part[1:-1], True
        elif part.startswith("【"):
            todo = True
        part = re.sub(r"\[([^\]]+)\]\(([^)]+)\)", r"\1 (\2)", part)
        rpr = ""
        if code:
            rpr += '<w:rFonts w:ascii="Consolas" w:hAnsi="Consolas"/>'
        if b:
            rpr += "<w:b/>"
        if color:
            rpr += f'<w:color w:val="{color}"/>'
        if size:
            rpr += f'<w:sz w:val="{size}"/><w:szCs w:val="{size}"/>'
        if todo:
            rpr += '<w:highlight w:val="yellow"/>'
        out.append(f'<w:r><w:rPr>{rpr}{FONT_RPR}</w:rPr><w:t xml:space="preserve">{escape(part)}</w:t></w:r>')
    return "".join(out)


def para(text, indent=0, hanging=0, before=0, after=60, size=None, color=None, bold=False, keep_next=False, center=False):
    ind = f'<w:ind w:left="{indent}" w:hanging="{hanging}"/>' if indent else ""
    kn = "<w:keepNext/>" if keep_next else ""
    jc = '<w:jc w:val="center"/>' if center else ""
    return (f'<w:p><w:pPr>{kn}<w:spacing w:before="{before}" w:after="{after}" w:line="300" w:lineRule="auto"/>'
            f'{ind}{jc}</w:pPr>{runs(text, size, color, bold)}</w:p>')


def table(rows):
    header, body = rows[0], rows[1:]
    n = len(header)
    # Column widths follow how much text each column carries, with a floor.
    weight = [max(4, max(len(r[i]) if i < len(r) else 0 for r in rows) ** 0.8) for i in range(n)]
    widths = [int(CELL_DXA * w / sum(weight)) for w in weight]
    widths = [max(w, 1000) for w in widths]
    widths[-1] += CELL_DXA - sum(widths)
    grid = "".join(f'<w:gridCol w:w="{w}"/>' for w in widths)
    border = '<w:{0} w:val="single" w:sz="4" w:space="0" w:color="B7C3CF"/>'
    borders = "".join(border.format(s) for s in ("top", "left", "bottom", "right", "insideH", "insideV"))
    xml = [f'<w:tbl><w:tblPr><w:tblW w:w="{CELL_DXA}" w:type="dxa"/><w:tblBorders>{borders}</w:tblBorders>'
           f'<w:tblLayout w:type="fixed"/><w:tblCellMar><w:top w:w="40" w:type="dxa"/><w:left w:w="80" w:type="dxa"/>'
           f'<w:bottom w:w="40" w:type="dxa"/><w:right w:w="80" w:type="dxa"/></w:tblCellMar></w:tblPr>'
           f'<w:tblGrid>{grid}</w:tblGrid>']
    for ri, row in enumerate([header] + body):
        cells = []
        for i in range(n):
            text = row[i] if i < len(row) else ""
            shade = '<w:shd w:val="clear" w:color="auto" w:fill="E8EEF4"/>' if ri == 0 else ""
            cells.append(f'<w:tc><w:tcPr><w:tcW w:w="{widths[i]}" w:type="dxa"/>{shade}</w:tcPr>'
                         f'{para(text, after=0, size=18, bold=ri == 0)}</w:tc>')
        trpr = '<w:trPr><w:tblHeader/><w:cantSplit/></w:trPr>' if ri == 0 else '<w:trPr><w:cantSplit/></w:trPr>'
        xml.append(f"<w:tr>{trpr}{''.join(cells)}</w:tr>")
    xml.append("</w:tbl>")
    return "".join(xml)


class Images:
    def __init__(self):
        self.items = []   # (rel id, zip name, bytes)

    def add(self, path):
        with open(path, "rb") as f:
            data = f.read()
        w, h = jpeg_size(data)
        idx = len(self.items) + 1
        rid = f"rIdFog{idx}"
        name = f"media/fogdrive{idx}.jpg"
        self.items.append((rid, name, data))
        cx = int(IMAGE_WIDTH_IN * EMU_PER_IN)
        cy = int(cx * h / w)
        pid = 4000 + idx
        return (f'<w:p><w:pPr><w:keepNext/><w:spacing w:before="120" w:after="20"/><w:jc w:val="center"/></w:pPr><w:r><w:drawing>'
                f'<wp:inline distT="0" distB="0" distL="0" distR="0"><wp:extent cx="{cx}" cy="{cy}"/>'
                f'<wp:docPr id="{pid}" name="Picture {pid}"/>'
                f'<a:graphic xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">'
                f'<a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture">'
                f'<pic:pic xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture">'
                f'<pic:nvPicPr><pic:cNvPr id="{pid}" name="fogdrive{idx}.jpg"/><pic:cNvPicPr/></pic:nvPicPr>'
                f'<pic:blipFill><a:blip r:embed="{rid}"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>'
                f'<pic:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="{cx}" cy="{cy}"/></a:xfrm>'
                f'<a:prstGeom prst="rect"><a:avLst/></a:prstGeom></pic:spPr></pic:pic>'
                f'</a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>')


def jpeg_size(data):
    i = 2
    while i < len(data):
        if data[i] != 0xFF:
            i += 1
            continue
        marker = data[i + 1]
        if 0xC0 <= marker <= 0xCF and marker not in (0xC4, 0xC8, 0xCC):
            h, w = struct.unpack(">HH", data[i + 5:i + 9])
            return w, h
        i += 2 + struct.unpack(">H", data[i + 2:i + 4])[0]
    raise ValueError("not a JPEG")


def render(lines, images):
    out, i = [], 0
    while i < len(lines):
        line = lines[i].rstrip()
        if not line:
            i += 1
        elif line.startswith("|"):
            rows = []
            while i < len(lines) and lines[i].startswith("|"):
                cells = [c.strip() for c in lines[i].strip().strip("|").split("|")]
                if not all(re.fullmatch(r":?-+:?", c) for c in cells):
                    rows.append(cells)
                i += 1
            out.append(table(rows))
            out.append(para("", after=0))
        elif m := re.match(r"!\[([^\]]*)\]\(([^)]+)\)", line):
            out.append(images.add(os.path.normpath(os.path.join(SUB, m.group(2)))))
            out.append(para(m.group(1), after=120, size=18, color="5B6B7B", center=True))
            i += 1
        elif m := re.match(r"- (.*)", line):
            out.append(para("• " + m.group(1), indent=284, hanging=284, after=40))
            i += 1
        elif m := re.match(r"(\d+)\. (.*)", line):
            out.append(para(f"{m.group(1)}. {m.group(2)}", indent=340, hanging=340, after=60))
            i += 1
        elif re.fullmatch(r"\*\*[^*]+\*\*", line):
            out.append(para(line, before=140, after=60, color="16324F", keep_next=True))
            i += 1
        else:
            out.append(para(line))
            i += 1
    return "".join(out)


def sections(md):
    parts = re.split(r"^## (\d)\. .*$", md, flags=re.M)
    return {int(parts[k]): parts[k + 1].strip("\n").splitlines() for k in range(1, len(parts), 2)}


# ---------------------------------------------------------------- filling the form

def fill_box(doc, tag, body):
    """Replaces the paragraph inside the single-cell input table that holds content control [tag]."""
    for m in re.finditer(r"<w:tbl>.*?</w:tbl>", doc, flags=re.S):
        if f'<w:tag w:val="{tag}"/>' in m.group(0):
            tbl = m.group(0)
            start = tbl.index("</w:tcPr>") + len("</w:tcPr>")
            end = tbl.rindex("</w:tc>")
            new = tbl[:start] + body + tbl[end:]
            new = new.replace('<w:trHeight w:val="510"/>', "")
            return doc[:m.start()] + new + doc[m.end():]
    raise SystemExit(f"input box {tag} not found in the form")


def fill_line(doc, prefix, text):
    """Rewrites every paragraph whose text starts with [prefix] as prefix + text, keeping its formatting."""
    count = 0

    def sub(m):
        nonlocal count
        p = m.group(0)
        if not "".join(re.findall(r"<w:t(?: [^>]*)?>([^<]*)</w:t>", p)).startswith(prefix):
            return p
        count += 1
        ppr = re.search(r"<w:pPr>.*?</w:pPr>", p, flags=re.S)
        rpr = re.search(r"<w:r(?: [^>]*)?><w:rPr>(.*?)</w:rPr>", p, flags=re.S)
        head = p[:p.index(">") + 1]
        return (f"{head}{ppr.group(0) if ppr else ''}<w:r><w:rPr>{rpr.group(1) if rpr else ''}</w:rPr>"
                f'<w:t xml:space="preserve">{escape(prefix + text)}</w:t></w:r></w:p>')

    doc = re.sub(r"<w:p[ >](?:(?!</w:p>).)*</w:p>", sub, doc, flags=re.S)
    if not count:
        raise SystemExit(f"line '{prefix}' not found in the form")
    return doc


def main():
    md = open(SOURCE, encoding="utf-8").read()
    secs = sections(md)
    images = Images()
    with zipfile.ZipFile(TEMPLATE) as z:
        files = {n: z.read(n) for n in z.namelist()}
    doc = files["word/document.xml"].decode("utf-8")

    for n, tag in enumerate(TAGS, start=1):
        doc = fill_box(doc, tag, render(secs[n], images))
    doc = fill_line(doc, "앱명 / 프로젝트명: ", APP_NAME)
    doc = fill_line(doc, "팀명(개인 참가 시 성명): ", TEAM)
    doc = fill_line(doc, "팀명(팀 참가 시): ", TEAM)
    doc = fill_line(doc, "참가 부문: ", "□ 대학(원)생    ■ 일반(스타트업 포함)")
    if "xmlns:wp=" not in doc[:3000] or "xmlns:r=" not in doc[:3000]:
        raise SystemExit("form lacks the drawing namespaces")
    files["word/document.xml"] = doc.encode("utf-8")

    rels = files["word/_rels/document.xml.rels"].decode("utf-8")
    extra = "".join(f'<Relationship Id="{rid}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="{name}"/>'
                    for rid, name, _ in images.items)
    files["word/_rels/document.xml.rels"] = rels.replace("</Relationships>", extra + "</Relationships>").encode("utf-8")
    ct = files["[Content_Types].xml"].decode("utf-8")
    if 'Extension="jpg"' not in ct:
        ct = ct.replace("</Types>", '<Default Extension="jpg" ContentType="image/jpeg"/></Types>', 1)
        if 'Extension="jpg"' not in ct:
            raise SystemExit("could not register the jpg content type")
    files["[Content_Types].xml"] = ct.encode("utf-8")
    for _, name, data in images.items:
        files["word/" + name] = data

    tmp = OUT + ".tmp"
    with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as z:
        for n, data in files.items():
            # Fixed timestamp so rebuilding unchanged content gives the same bytes (no spurious git diffs).
            z.writestr(zipfile.ZipInfo(n, date_time=(1980, 1, 1, 0, 0, 0)), data, zipfile.ZIP_DEFLATED)
    os.replace(tmp, OUT)
    print(f"{os.path.relpath(OUT, ROOT)} ({len(images.items)} images)")


if __name__ == "__main__":
    main()
