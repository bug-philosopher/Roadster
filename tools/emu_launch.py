"""Drive the emulator GPS through a timed run.

usage: emu_launch.py ADB SHOT_DIR STAGE_X STAGE_Y [TAG] [SCALE] [SHORT]
"""
import math
import subprocess
import sys
import time

ADB, SHOTS, SX, SY = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
TAG = sys.argv[5] if len(sys.argv) > 5 else "run"
SCALE = float(sys.argv[6]) if len(sys.argv) > 6 else 1.0
lat, lon = 35.4673, -83.9207


def fix(v_mps, alt=610.0):
    global lat
    knots = v_mps * 1.943844
    subprocess.run([ADB, "emu", "geo", "fix", f"{lon:.7f}", f"{lat:.7f}", f"{alt:.1f}", "9", f"{knots:.3f}"], capture_output=True)


def move(meters):
    global lat
    lat += meters / 111_111.0


def shot(name):
    with open(f"{SHOTS}/{TAG}_{name}.png", "wb") as f:
        f.write(subprocess.run([ADB, "exec-out", "screencap", "-p"], capture_output=True).stdout)



def model(seconds=30.0, dt=0.0005):
    shifts = [13.4, 24.6, 33.5, 42.0]
    nxt, until, v, x, t = 0, -1.0, 0.0, 0.0, 0.0
    out = []
    while t < seconds:
        out.append((t, v, x))
        if nxt < len(shifts) and v >= shifts[nxt] and t >= until:
            until, nxt = t + 0.35, nxt + 1
        a = -0.25 if t < until else SCALE * max(0.45, 4.4 - 0.095 * v) * ((0.45 + 0.55 * t / 0.35) if t < 0.35 else 1.0)
        v = max(0.0, v + a * dt)
        x += v * dt
        t += dt
    return out


prof = model()
def at(t):
    return prof[min(len(prof) - 1, int(t / 0.0005))]


for i in range(3):
    fix(13.4); move(13.4); time.sleep(1)
subprocess.run([ADB, "shell", "input", "tap", SX, SY])
time.sleep(1.2)
shot("p1_staging")

for v in (9.0, 4.5, 1.2):
    fix(v); move(v); time.sleep(1)
for i in range(3):
    fix(0.0); time.sleep(1)
shot("p2_ready")

time.sleep(0.4)
t0 = time.time()
k = 0
last_x = 0.0
while True:
    k += 1
    target = t0 + k * 1.0 - 0.4
    time.sleep(max(0.0, target - time.time()))
    tt, v, x = at(target - t0)
    move(x - last_x); last_x = x
    fix(v, 610 + x * 0.01)
    if k == 6:
        time.sleep(0.3); shot("p3_running")
    if x > 420 or tt > 29 or (len(sys.argv) > 7 and v > 28.5):
        break
time.sleep(1.5)
shot("p4_done")
print(f"model: 0-60 mph at {next(t for t, v, x in prof if v >= 26.8224):.2f}s, 1/4 mile at "
      f"{next(t for t, v, x in prof if x >= 402.336):.2f}s @ {next(v for t, v, x in prof if x >= 402.336) / 0.44704:.1f} mph")
