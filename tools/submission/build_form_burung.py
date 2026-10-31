#!/usr/bin/env python3
"""Fills the contest application form for 부릉탐험대 from docs/submission/burung/02-app-introduction.md.

    python3 tools/submission/build_form_burung.py

Same form and rules as build_form.py (the 안개지도 form is left as it is); only the source, the
output and the app name differ. Image paths in the source are relative to docs/submission.
"""
import os

import build_form as form

BURUNG = os.path.join(form.SUB, "burung")
form.SOURCE = os.path.join(BURUNG, "02-app-introduction.md")
form.OUT = os.path.join(BURUNG, "application-form-burung.docx")
form.PRIVATE_OUT = os.path.join(form.PRIVATE, "zobo_부릉탐험대_접수양식.docx")
form.APP_NAME = "부릉탐험대"

if __name__ == "__main__":
    form.main()
