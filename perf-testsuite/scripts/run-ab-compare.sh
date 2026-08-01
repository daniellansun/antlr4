#!/usr/bin/env bash
# A/B performance comparison between two commits of the optimized ANTLR4 fork.
#
# Usage:
#   ./perf-testsuite/scripts/run-ab-compare.sh \
#       07111a8ee7c16217d94989a368863db377970a34 \
#       85edb01a754d404cabeb3df5237ff08ae5fe8eb5
#
# Environment:
#   WORK_ROOT   scratch directory (default: /tmp/antlr4-ab-perf)
#   FILES       max corpus files (default: 80)
#   WARMUP      warmup iterations (default: 5)
#   ITERS       measured iterations (default: 15)
#   THREADS     parallel worker count; 0 = available processors (default: 0)
#   SKIP_JMH    if "1", skip JMH and only run ComparativeParseHarness
#   MVN_OPTS    extra Maven flags

set -euo pipefail

BASE_COMMIT="${1:?baseline commit required}"
TARGET_COMMIT="${2:?target commit required}"

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
WORK_ROOT="${WORK_ROOT:-/tmp/antlr4-ab-perf}"
FILES="${FILES:-80}"
WARMUP="${WARMUP:-5}"
ITERS="${ITERS:-15}"
THREADS="${THREADS:-0}"
SKIP_JMH="${SKIP_JMH:-1}"
MVN_OPTS="${MVN_OPTS:--q -DskipTests}"

RESULTS_DIR="${REPO_ROOT}/perf-testsuite/results"
mkdir -p "${RESULTS_DIR}" "${WORK_ROOT}"

STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
BASE_LABEL="base-${BASE_COMMIT:0:9}"
TARGET_LABEL="target-${TARGET_COMMIT:0:9}"

echo "==> Repo:        ${REPO_ROOT}"
echo "==> Baseline:    ${BASE_COMMIT}"
echo "==> Target:      ${TARGET_COMMIT}"
echo "==> Work root:   ${WORK_ROOT}"
echo "==> Results:     ${RESULTS_DIR}"
echo "==> Corpus files:${FILES} warmup=${WARMUP} iters=${ITERS} threads=${THREADS}"

# Snapshot benchmark sources from the current working tree so both commits
# run identical harness code (black-box public API only).
SNAP="${WORK_ROOT}/bench-snapshot-${STAMP}"
rm -rf "${SNAP}"
mkdir -p "${SNAP}/jmh" "${SNAP}/scripts"
cp -a "${REPO_ROOT}/perf-testsuite/src/org/antlr/v4/test/runtime/java/api/perf/jmh/." "${SNAP}/jmh/"
cp -a "${REPO_ROOT}/perf-testsuite/scripts/run-ab-compare.sh" "${SNAP}/scripts/" 2>/dev/null || true

# Capture the JMH-related pom fragment from HEAD for overlay notes.
cp "${REPO_ROOT}/perf-testsuite/pom.xml" "${SNAP}/pom-head.xml"

prepare_worktree() {
  local name="$1"
  local commit="$2"
  local dir="${WORK_ROOT}/${name}"
  echo "==> Preparing worktree ${name} @ ${commit}"
  rm -rf "${dir}"
  git -C "${REPO_ROOT}" worktree remove --force "${dir}" 2>/dev/null || true
  git -C "${REPO_ROOT}" worktree add --detach "${dir}" "${commit}"

  # Overlay identical harness sources.
  local dest="${dir}/perf-testsuite/src/org/antlr/v4/test/runtime/java/api/perf/jmh"
  mkdir -p "${dest}"
  cp -a "${SNAP}/jmh/." "${dest}/"

  # Ensure JMH deps + exec plugin exist on older commits' pom.
  python3 - <<'PY' "${dir}/perf-testsuite/pom.xml"
import sys, re
path = sys.argv[1]
with open(path, "r", encoding="utf-8") as f:
    text = f.read()
if "jmh-core" not in text:
    dep = '''
        <dependency>
            <groupId>org.openjdk.jmh</groupId>
            <artifactId>jmh-core</artifactId>
            <version>1.37</version>
        </dependency>
        <dependency>
            <groupId>org.openjdk.jmh</groupId>
            <artifactId>jmh-generator-annprocess</artifactId>
            <version>1.37</version>
            <scope>provided</scope>
        </dependency>
'''
    text = text.replace("</dependencies>", dep + "    </dependencies>", 1)
if "exec-maven-plugin" not in text:
    plugin = '''
            <plugin>
                <groupId>org.codehaus.mojo</groupId>
                <artifactId>exec-maven-plugin</artifactId>
                <version>3.1.0</version>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <configuration>
                    <annotationProcessorPaths>
                        <path>
                            <groupId>org.openjdk.jmh</groupId>
                            <artifactId>jmh-generator-annprocess</artifactId>
                            <version>1.37</version>
                        </path>
                    </annotationProcessorPaths>
                </configuration>
            </plugin>
'''
    # Insert before closing </plugins> of build section
    text = text.replace("        </plugins>\n    </build>", plugin + "        </plugins>\n    </build>", 1)
with open(path, "w", encoding="utf-8") as f:
    f.write(text)
print("patched", path)
PY
}

run_harness() {
  local name="$1"
  local label="$2"
  local dir="${WORK_ROOT}/${name}"
  local out_tsv="${RESULTS_DIR}/${label}-${STAMP}.tsv"
  local out_log="${RESULTS_DIR}/${label}-${STAMP}.log"

  echo "==> Building ${name}"
  (
    cd "${dir}"
    # Install reactor artifacts needed by perf-testsuite.
    mvn ${MVN_OPTS} -pl runtime/JavaAnnotations,runtime/Java,tool,antlr4-maven-plugin,perf-testsuite -am install
  )

  echo "==> Running ComparativeParseHarness (${label})"
  (
    cd "${dir}"
    # --synthetic true: identical corpus on both commits (runtime sources differ).
    mvn -pl perf-testsuite -q exec:java \
      -Dexec.classpathScope=compile \
      -Dexec.mainClass=org.antlr.v4.test.runtime.java.api.perf.jmh.ComparativeParseHarness \
      -Dexec.args="--label ${label} --files ${FILES} --warmup ${WARMUP} --iters ${ITERS} --threads ${THREADS} --synthetic true --methods 12 --singleMethods 40" \
      -Dexec.jvmArgs="-Xms2g -Xmx2g -XX:+UseG1GC" \
      >"${out_tsv}" 2>"${out_log}"
  )
  echo "    TSV: ${out_tsv}"
  echo "    LOG: ${out_log}"
  # Show summary from log
  tail -n 30 "${out_log}" || true
}

diff_results() {
  local base_tsv="${RESULTS_DIR}/${BASE_LABEL}-${STAMP}.tsv"
  local target_tsv="${RESULTS_DIR}/${TARGET_LABEL}-${STAMP}.tsv"
  local report="${RESULTS_DIR}/ab-compare-${STAMP}.md"
  python3 - <<'PY' "${base_tsv}" "${target_tsv}" "${report}" "${BASE_COMMIT}" "${TARGET_COMMIT}"
import sys, csv
from collections import OrderedDict

base_path, target_path, report_path, base_c, target_c = sys.argv[1:6]

def load(path):
    rows = OrderedDict()
    with open(path, newline='', encoding='utf-8') as f:
        # skip comment lines
        lines = [ln for ln in f if ln.strip() and not ln.startswith('#')]
    if not lines:
        return rows
    reader = csv.DictReader(lines, delimiter='\t')
    for r in reader:
        rows[r['scenario']] = r
    return rows

base = load(base_path)
target = load(target_path)
scenarios = list(OrderedDict.fromkeys(list(base.keys()) + list(target.keys())))

def fnum(r, k):
    try:
        return float(r[k])
    except Exception:
        return float('nan')

lines = []
lines.append('# A/B Performance Comparison Report')
lines.append('')
lines.append(f'- **Baseline commit**: `{base_c}`')
lines.append(f'- **Target commit**: `{target_c}`')
lines.append(f'- **Metric**: trimmed-mean wall time (ms); speedup = baseline/target (>1 is faster on target)')
lines.append('')
lines.append('| Scenario | Base mean ms | Target mean ms | Speedup | Δ% | Base chars/ms | Target chars/ms | Threads | clearDfa |')
lines.append('|---|---:|---:|---:|---:|---:|---:|---:|:---:|')

for s in scenarios:
    b, t = base.get(s), target.get(s)
    if not b or not t:
        lines.append(f'| `{s}` | {"n/a" if not b else b["mean_ms"]} | {"n/a" if not t else t["mean_ms"]} | n/a | n/a |  |  |  |  |')
        continue
    bm, tm = fnum(b,'mean_ms'), fnum(t,'mean_ms')
    speedup = bm / tm if tm > 0 else float('nan')
    delta_pct = (bm - tm) / bm * 100.0 if bm > 0 else float('nan')
    lines.append(
        f"| `{s}` | {bm:.3f} | {tm:.3f} | **{speedup:.3f}x** | {delta_pct:+.2f}% | {fnum(b,'chars_per_ms'):.1f} | {fnum(t,'chars_per_ms'):.1f} | {t.get('threads','')} | {t.get('clearDfa','')} |"
    )

# Highlight cold-path aggregate
cold = [s for s in scenarios if 'cold' in s]
warm = [s for s in scenarios if 'warm' in s]
lines.append('')
lines.append('## Cold-path emphasis (ATN simulation / retained pool / HPPC clear)')
lines.append('')
for s in cold:
    if s in base and s in target:
        bm, tm = fnum(base[s],'mean_ms'), fnum(target[s],'mean_ms')
        if tm > 0:
            lines.append(f'- `{s}`: **{bm/tm:.3f}x** ({(bm-tm)/bm*100:+.2f}%)')

lines.append('')
lines.append('## Warm-path (shared DFA steady state)')
lines.append('')
for s in warm:
    if s in base and s in target:
        bm, tm = fnum(base[s],'mean_ms'), fnum(target[s],'mean_ms')
        if tm > 0:
            lines.append(f'- `{s}`: **{bm/tm:.3f}x** ({(bm-tm)/bm*100:+.2f}%)')

lines.append('')
lines.append('## Serial vs parallel')
lines.append('')
for kind in ('batch_serial_two_stage_warm', 'batch_parallel_two_stage_warm',
             'batch_serial_two_stage_cold', 'batch_parallel_two_stage_cold'):
    if kind in base and kind in target:
        bm, tm = fnum(base[kind],'mean_ms'), fnum(target[kind],'mean_ms')
        if tm > 0:
            lines.append(f'- `{kind}`: **{bm/tm:.3f}x** (base {bm:.2f} ms → target {tm:.2f} ms)')

with open(report_path, 'w', encoding='utf-8') as f:
    f.write('\n'.join(lines) + '\n')
print('\n'.join(lines))
print(f'\nWrote {report_path}')
PY
}

prepare_worktree "base" "${BASE_COMMIT}"
prepare_worktree "target" "${TARGET_COMMIT}"

run_harness "base" "${BASE_LABEL}"
run_harness "target" "${TARGET_LABEL}"
diff_results

echo "==> Done. Results under ${RESULTS_DIR}"
