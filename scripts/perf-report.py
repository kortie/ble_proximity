#!/usr/bin/env python3
"""S11 성능 보고: Lab이 내보낸 perf_*.csv를 모아 p95를 기준치(config/thresholds.yaml)와 비교한다.

사용:  python3 scripts/perf-report.py <csv 또는 폴더>... [--role host|client]
       (adb pull /sdcard/Android/data/dev.proximity.sample/files/perf ./perf-A)
출력:  항목별 표본 수·p95·기준·PASS/FAIL/표본없음. 표본이 없으면 판정하지 않는다(추정 금지).
종료 코드: FAIL이 하나라도 있으면 1.
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent

# metric 키 → (thresholds.yaml 키, 설명)
CHECKS = {
    "bump_to_confirm_ms": ("e2e.bump_to_confirm_p95_ms", "부딪힘→확인 화면 p95"),
    "accept_to_welcome_ms": ("e2e.accept_to_welcome_p95_ms", "양측 수락→WELCOME p95"),
    "action_roundtrip_ms": ("e2e.action_roundtrip_p95_ms", "액션 왕복 p95"),
    "link_rtt_ms": ("session.downlink_p95_ms", "링크 왕복 p95 (다운링크 지연 참고값: 왕복이라 실제보다 크게 나온다)"),
}


def thresholds():
    """config/thresholds.yaml의 2단계 키만 읽는다 (의존성 없이)."""
    values, section = {}, None
    for line in (ROOT / "config/thresholds.yaml").read_text(encoding="utf-8").splitlines():
        line = line.split("#", 1)[0].rstrip()
        if not line:
            continue
        m = re.match(r"^(\w+):\s*$", line)
        if m:
            section = m.group(1)
            continue
        m = re.match(r"^\s+(\w+):\s*([-\d.]+)$", line)
        if m and section:
            values[f"{section}.{m.group(1)}"] = float(m.group(2))
    return values


def p95(xs):
    s = sorted(xs)
    rank = max(1, min(len(s), -(-95 * len(s) // 100)))  # ceil(0.95 n)
    return s[rank - 1]


def read(paths):
    samples, battery = {}, []
    files = []
    for p in map(pathlib.Path, paths):
        files += sorted(p.glob("perf_*.csv")) if p.is_dir() else [p]
    for f in files:
        rows = []
        for line in f.read_text(encoding="utf-8").splitlines()[1:]:
            key, _, value = line.partition(",")
            if not value:
                continue
            if key.startswith("battery_pct@"):
                rows.append((int(key.split("@")[1]), int(value)))
            else:
                samples.setdefault(key, []).append(int(value))
        if len(rows) >= 2:
            battery.append(rows)
    return files, samples, battery


def battery_per_hour(series):
    """파일마다 처음·마지막 표본으로 시간당 소모(%)를 구한다. 10분 미만이면 쓰지 않는다."""
    out = []
    for rows in series:
        (t0, b0), (t1, b1) = rows[0], rows[-1]
        if t1 - t0 >= 10 * 60_000:
            out.append((b0 - b1) * 3_600_000 / (t1 - t0))
    return out


def main(argv):
    role = "client"
    if "--role" in argv:
        i = argv.index("--role")
        role = argv[i + 1]
        argv = argv[:i] + argv[i + 2:]
    if not argv:
        print(__doc__)
        return 2
    limits = thresholds()
    files, samples, battery = read(argv)
    print(f"파일 {len(files)}개")
    failed = False
    print(f"{'항목':<44} {'n':>5} {'p95':>8} {'기준':>8}  판정")
    for key, (tkey, label) in CHECKS.items():
        xs = samples.get(key, [])
        limit = limits.get(tkey)
        if not xs:
            print(f"{label:<44} {0:>5} {'-':>8} {limit or '-':>8}  표본없음")
            continue
        v = p95(xs)
        verdict = "PASS" if limit is not None and v <= limit else "FAIL"
        failed |= verdict == "FAIL"
        print(f"{label:<44} {len(xs):>5} {v:>8} {limit:>8.0f}  {verdict}")
    per_hour = battery_per_hour(battery)
    bkey = f"e2e.battery_{role}_pct_per_hour"
    if per_hour:
        worst = max(per_hour)
        verdict = "PASS" if worst <= limits[bkey] else "FAIL"
        failed |= verdict == "FAIL"
        print(f"{'배터리 %/h (' + role + ', 가장 나쁜 기록)':<44} {len(per_hour):>5} {worst:>8.1f} {limits[bkey]:>8.0f}  {verdict}")
    else:
        print(f"{'배터리 %/h (' + role + ')':<44} {0:>5} {'-':>8} {limits[bkey]:>8.0f}  표본없음 (10분 이상 기록 필요)")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
