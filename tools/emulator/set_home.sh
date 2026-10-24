#!/bin/sh
# Puts the emulator's GPS at the FogDrive home position (경기 고양시 덕양구 향기5로 66),
# where every demo course starts. The emulator forgets it on a cold boot, so run it after booting.
#   tools/emulator/set_home.sh [serial]
SERIAL=${1:-emulator-5554}
adb -s "$SERIAL" emu geo fix 126.89592 37.60852
