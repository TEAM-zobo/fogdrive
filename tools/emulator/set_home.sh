#!/bin/sh
# Puts the emulator's GPS at the FogDrive home position (송파꿈에그린 위례24단지, the demo persona's home),
# where every demo course starts. The emulator forgets it on a cold boot, so run it after booting.
#   tools/emulator/set_home.sh [serial]
SERIAL=${1:-emulator-5554}
adb -s "$SERIAL" emu geo fix 127.13960 37.47588
