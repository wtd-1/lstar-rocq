#!/usr/bin/env bash
# Runs the full LearnLib interop harness test matrix -- both directions, all
# three algorithms -- and saves every run's output under
# learnlib-harness/results/, for plot_results.py to visualize afterwards.
# For a single one-off run instead, see run.sh.
#
# Direction A (Java teacher, our extracted learner) runs the full corpus
# (hand-built targets + random DFAs up to 100 states) and, since the algo
# name is passed through, also runs LearnLib's own learner locally per
# target and logs whether it agrees with our hypothesis -- see
# TeacherServer's doc comment.
#
# Direction B (OCaml teacher, LearnLib's own learner) only has the 3
# hand-built targets to offer (see socket_teach.ml), so that's all it runs.

set -euo pipefail

HARNESS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$HARNESS_DIR/.." && pwd)"
RESULTS_DIR="$HARNESS_DIR/results"
JAR="$HARNESS_DIR/target/learnlib-harness.jar"
JAVA_OPTS=(-Dorg.slf4j.simpleLogger.defaultLogLevel=warn)

mkdir -p "$RESULTS_DIR"

SERVER_PID=""
cleanup() {
  if [ -n "$SERVER_PID" ] && kill -0 "$SERVER_PID" 2>/dev/null; then
    kill "$SERVER_PID" 2>/dev/null || true
    wait "$SERVER_PID" 2>/dev/null || true
  fi
  SERVER_PID=""
}
trap cleanup EXIT

wait_for_port() {
  # See run.sh for why this checks LISTEN state rather than connecting.
  local port="$1" tries=0
  until lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; do
    tries=$((tries + 1))
    if [ "$tries" -ge 200 ]; then
      echo "timed out waiting for port $port" >&2
      exit 1
    fi
    sleep 0.1
  done
}

echo "== building OCaml side (dune build) =="
(cd "$REPO_DIR" && eval "$(opam env)" && dune build)

echo "== building Java side (mvn package) =="
(cd "$HARNESS_DIR" && mvn -q -o package -DskipTests)

port=8850

for algo in lstar kv ttt; do
  port=$((port + 1))
  echo "== Direction A / $algo (full corpus + agreement check), port $port =="
  java "${JAVA_OPTS[@]}" -jar "$JAR" teacher "$port" full "$algo" \
    > "$RESULTS_DIR/direction_a_${algo}_teacher.log" 2>&1 &
  SERVER_PID=$!
  wait_for_port "$port"
  (cd "$REPO_DIR" && eval "$(opam env)" && dune exec examples/socket_learn.exe -- "$algo" "$port") \
    > "$RESULTS_DIR/direction_a_${algo}_learner.log" 2>&1
  wait "$SERVER_PID"
  SERVER_PID=""
done

for algo in lstar kv ttt; do
  port=$((port + 1))
  echo "== Direction B / $algo, port $port =="
  (cd "$REPO_DIR" && eval "$(opam env)" && dune exec examples/socket_teach.exe -- "$port") \
    > "$RESULTS_DIR/direction_b_${algo}_teacher.log" 2>&1 &
  SERVER_PID=$!
  wait_for_port "$port"
  java "${JAVA_OPTS[@]}" -jar "$JAR" learner localhost "$port" "$algo" \
    > "$RESULTS_DIR/direction_b_${algo}_learner.log" 2>&1
  wait "$SERVER_PID"
  SERVER_PID=""
done

echo "== done, logs in $RESULTS_DIR =="
