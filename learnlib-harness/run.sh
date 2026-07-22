#!/usr/bin/env bash
# Orchestrates one run of the LearnLib interop harness, in either direction:
#
#   ./run.sh a <lstar|kv|ttt> [port] [small|full]   # Direction A: Java teacher,
#                                                    # our extracted learner
#   ./run.sh b <lstar|kv|ttt> [port]                # Direction B: our OCaml
#                                                    # teacher, LearnLib learner
#
# "small" (the default) runs the 3 hand-built targets only; "full" adds the
# random-DFA corpus up to 100 states, which is slower (see Corpus.java).
#
# Handles startup ordering (server must be listening before the client
# dials) and always tears down the background server on exit, including on
# failure -- set -e plus the EXIT trap below.

set -euo pipefail

HARNESS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$HARNESS_DIR/.." && pwd)"
JAR="$HARNESS_DIR/target/learnlib-harness.jar"

usage() {
  echo "usage: $0 a <lstar|kv|ttt> [port] [small|full]" >&2
  echo "       $0 b <lstar|kv|ttt> [port]" >&2
  exit 1
}

[ $# -ge 2 ] || usage
direction="$1"
algo="$2"
port="${3:-8888}"
case "$algo" in lstar|kv|ttt) ;; *) usage ;; esac

SERVER_PID=""
cleanup() {
  if [ -n "$SERVER_PID" ] && kill -0 "$SERVER_PID" 2>/dev/null; then
    kill "$SERVER_PID" 2>/dev/null || true
    wait "$SERVER_PID" 2>/dev/null || true
  fi
}
trap cleanup EXIT

wait_for_port() {
  # Checks that something is LISTENing on $port without actually connecting:
  # both servers in this harness accept exactly one connection for an
  # entire run's corpus, so a probe connection (e.g. bash's /dev/tcp) would
  # itself get accepted as *the* client and lock the real one out.
  local port="$1" tries=0
  until lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; do
    tries=$((tries + 1))
    if [ "$tries" -ge 100 ]; then
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

case "$direction" in
  a)
    size="${4:-small}"
    echo "== Direction A: Java TeacherServer ($size corpus) vs OCaml $algo =="
    java -jar "$JAR" teacher "$port" "$size" &
    SERVER_PID=$!
    wait_for_port "$port"
    (cd "$REPO_DIR" && eval "$(opam env)" && dune exec examples/socket_learn.exe -- "$algo" "$port")
    ;;
  b)
    echo "== Direction B: OCaml socket_teach vs Java LearnerClient $algo =="
    (cd "$REPO_DIR" && eval "$(opam env)" && dune exec examples/socket_teach.exe -- "$port") &
    SERVER_PID=$!
    wait_for_port "$port"
    java -jar "$JAR" learner localhost "$port" "$algo"
    ;;
  *)
    usage
    ;;
esac
