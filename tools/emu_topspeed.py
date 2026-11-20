"""Drive the emulator GPS to a peak speed.

usage: emu_topspeed.py ADB PEAK_MPS [SPIKE_INDEXES SPIKE_MPS] [START_LAT]
"""
import subprocess
import sys
import time

ADB = sys.argv[1]
PEAK = float(sys.argv[2])
SPIKE_I = [int(x) for x in sys.argv[3].split(",")] if len(sys.argv) > 3 else []
SPIKE_V = float(sys.argv[4]) if len(sys.argv) > 4 else 0.0
lat = float(sys.argv[5]) if len(sys.argv) > 5 else 35.50
lon = -83.9207

speeds = [10.0] * 3
v = 10.0
while v < PEAK:
    v = min(PEAK, v + 2.5)
    speeds.append(v)
speeds += [PEAK] * 3
while v > 10.0:
    v = max(10.0, v - 4.0)
    speeds.append(v)
speeds += [10.0] * 3

prev = speeds[0]
t0 = time.time()
for i, sp in enumerate(speeds):
    time.sleep(max(0.0, t0 + i - time.time()))
    lat += (prev + sp) / 2.0 / 111_111.0
    prev = sp
    rep = SPIKE_V if i in SPIKE_I else sp
    subprocess.run([ADB, "emu", "geo", "fix", f"{lon:.7f}", f"{lat:.7f}", "610", "9", f"{rep * 1.943844:.3f}"], capture_output=True)
print(f"peak {PEAK} m/s = {PEAK / 0.44704:.1f} mph, {len(speeds)} fixes, spike {SPIKE_V} m/s at #{SPIKE_I}" if SPIKE_I else
      f"peak {PEAK} m/s = {PEAK / 0.44704:.1f} mph, {len(speeds)} fixes")
