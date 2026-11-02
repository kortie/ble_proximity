#!/usr/bin/env python3
"""tests/fixtures/accel/synthetic/ 의 재생용 CSV를 만든다 (S3).
Kotlin SyntheticAccel과 독립된 생성기다 — 파일 재생 경로를 같은 코드로 만든 데이터로만 시험하지 않기 위해.
형식: t_ms,x,y,z (단위 g). 시드 고정이라 다시 실행해도 같은 파일이 나온다.
"""
import math
import os
import random

OUT = os.path.join(os.path.dirname(__file__), "..", "tests", "fixtures", "accel", "synthetic")
PERIOD = 10


def write(name, rows, comment):
    with open(os.path.join(OUT, name), "w", encoding="utf-8") as f:
        f.write(f"# {comment}\n")
        f.write("t_ms,x,y,z\n")
        for t, x, y, z in rows:
            f.write(f"{t},{x:.4f},{y:.4f},{z:.4f}\n")


def bump():
    r = random.Random(7)
    rows = []
    spike_start, width, amp = 1200, 30, 3.6
    for i in range(250):
        t = i * PERIOD
        x, y, z = r.gauss(0, 0.03), r.gauss(0, 0.03), 1.0 + r.gauss(0, 0.03)
        if spike_start <= t <= spike_start + width:
            k = (t - spike_start) / width
            x += amp * math.sin(math.pi * k)
        elif spike_start + width < t <= spike_start + width + 30:
            k = (t - spike_start - width) / 30
            x -= 0.35 * amp * math.sin(math.pi * k)
        rows.append((t, x, y, z))
    write("bump_replay.csv", rows, f"synthetic bump: spike start {spike_start}ms width {width}ms amp {amp}g along x, expected peak {spike_start + width // 2}ms")


def walk():
    r = random.Random(8)
    rows = []
    for i in range(300):
        t = i * PERIOD
        z = 1.0 + r.gauss(0, 0.05)
        phase = (t % 500) / 150
        if phase <= 1:
            z += 0.7 * math.sin(math.pi * phase)
        rows.append((t, r.gauss(0, 0.05) + 0.2 * math.sin(2 * math.pi * t / 1000), r.gauss(0, 0.05), z))
    write("walk_replay.csv", rows, "synthetic walk: 2Hz steps 0.7g along z, expected no bump")


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    bump()
    walk()
