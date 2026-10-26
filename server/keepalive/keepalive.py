#!/usr/bin/env python3
"""Keep an Always Free machine from looking idle: hold ~3 GB of written memory,
and burn some CPU for a while every minute (second line of defence)."""
import subprocess, sys, time

MEM_GB = 3
BURN_SECONDS = 30
PERIOD = 60
BUSY = "import time\nt=time.time()+%d\nwhile time.time()<t: pass" % BURN_SECONDS

# bytearray(n) alone is lazily zeroed and uses no real memory; write one byte per page so it is resident
PAGE = 4096
SIZE = 256 * 1024 * 1024
chunks = []
for _ in range(MEM_GB * 4):
    c = bytearray(SIZE)
    c[::PAGE] = b"\x01" * (SIZE // PAGE)
    chunks.append(c)
print("holding %d GB" % MEM_GB, flush=True)

while True:
    workers = [subprocess.Popen([sys.executable, "-c", BUSY]) for _ in range(2)]
    for w in workers:
        w.wait()
    time.sleep(PERIOD - BURN_SECONDS)
