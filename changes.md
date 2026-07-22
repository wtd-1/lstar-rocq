# changes

Big test of the framework using LearnLib. Wrote a teacher/learner wrapper so LearnLib and our extracted OCaml code can talk to each other over a socket, in both directions, and ran it against a real corpus instead of just the hand-written examples.

## protocol

The socket protocol already existed (`lib/SocketTeacher.ml`, one JSON object per line, `mq`/`eq` requests). Extended it so one connection can carry a whole batch of targets instead of just one:

- teacher sends `{"type":"config","alphabet":[...],"target":"name"}` before querying starts on a target
- teacher sends `{"type":"done"}` once every target is exhausted
- learner sends `{"type":"ack"}` once it's actually done with a target

KV and TTT's extracted hypothesis represents states as tree leaves, and rebuilding the "final" hypothesis right after an equivalence query comes back empty issues a few more live membership queries (walking the discrimination tree to resolve the initial state). If the teacher advances to the next target the moment it replies `NONE`, those extra queries land on the wrong target and desync the connection. L* didn't show this because its initial state is always the literal empty word, no query needed. Found this by running our own learner against our own teacher before touching Java at all.

Pulled the JSON parsing helpers out of `alternating_socket.ml`'s mock server into `lib/SocketProtocol.ml` so both new drivers and the Java side can share/mirror them, and added `SocketProtocol.MakeIntSymbol`, a functor that builds a `Symbol` module over `"0".."k-1"` at runtime once the alphabet size is known from the handshake.

## ocaml side

- `lib/SocketProtocol.ml` - new, shared wire format helpers (config/done/ack, mq/eq parsing, `MakeIntSymbol`)
- `lib/SocketTeacher.ml` - split `MakeSocketTeacher` into a generic `MakeProtocolTeacher` (takes any connection provider) plus the original auto-dialing wrapper on top, so the multi-target driver can reuse it
- `examples/socket_learn.ml` - new. Our extracted learner as the client: reads the config handshake, builds the alphabet at runtime, runs whichever of lstar/kv/ttt was asked for, reports state count via `D.states` (a plain field, doesn't trigger more queries - `D.transition`/`D.accept` are live closures and would)
- `examples/socket_teach.ml` - new. A small OCaml teacher serving three hand-built targets (alternating, ends_in_01, mod3) so LearnLib's own learners have something to learn against
- `examples/dune`, `lib/dune` - registered the two new executables, added `str` to the library

Also fixed a quadratic blowup in the counterexample BFS (was appending to the end of a growing list every step, `O(n^2)` for n up to 65k) and a double-close bug (`ic`/`oc` wrap the same fd, closing both throws on the second one).

## java side

New Maven project, `learnlib-harness/`, against real LearnLib 0.18.0 / AutomataLib 0.12.1 (checked actual Maven Central coordinates and class names before writing anything, package layout changed a fair bit from older LearnLib versions).

- `Protocol.java` - same wire format as the OCaml side, hand-rolled parsing (no JSON library, matches the OCaml style since both ends control the grammar)
- `Connection.java` - small socket wrapper, sets `TCP_NODELAY` on both ends (learning is a lot of small blocking round trips)
- `Corpus.java` - target DFAs for direction A: the same three hand-built languages `socket_teach.ml` serves, plus randomly generated DFAs at sizes 5/10/20/50/100 over 2- and 4-symbol alphabets
- `TeacherServer.java` - direction A. LearnLib/AutomataLib is the teacher: hosts a `Corpus` target, answers membership queries by simulating it, answers equivalence queries with `Automata.findSeparatingWord` (exact, not a heuristic search)
- `LearnerClient.java` - direction B. LearnLib's own `ClassicLStarDFA`/`KearnsVaziraniDFA`/`TTTLearnerDFA` as the client, wrapped `MembershipOracle`/`EquivalenceOracle` implementations that just forward over the socket
- `HarnessMain.java` - CLI entry point, dispatches to teacher or learner mode
- unit tests for `Protocol.java`'s round-tripping

## running it

- `run.sh` orchestrates one run (starts the right side first, waits for the port to actually be listening - not by connecting to it, since that would get eaten as the one accepted connection - then runs the other side)
- `make harness-build` / `make harness-test` wired into the top-level Makefile, `make test` still passes unchanged

Remarks on run: 

- direction A: all three of our extracted algorithms (L*, KV, TTT) learned all 13 corpus targets exactly, checked against LearnLib's exact equivalence oracle, up to 100-state / 4-symbol targets
- direction B: LearnLib's own three learners correctly learned all three targets served by our OCaml teacher

KV/TTT came in noticeably faster than L* on the bigger targets (~11s vs ~93s for the full 13-target run), which is expected, not a bug.


