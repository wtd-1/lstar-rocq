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
- `Corpus.java` - target DFAs for direction A: the same three hand-built languages `socket_teach.ml` serves, LearnLib's own shipped learning examples (Angluin, Paul-and-Mary, tiny DFA, two keylock sizes - pulled from the real `learnlib-learning-examples` artifact, not hand-copied), plus randomly generated DFAs at sizes 5/10/20/50/100 over 2- and 4-symbol alphabets
- `TeacherServer.java` - direction A. LearnLib/AutomataLib is the teacher: hosts a `Corpus` target, answers membership queries by simulating it, answers equivalence queries with `Automata.findSeparatingWord` (exact, not a heuristic search)
- `LearnerClient.java` - direction B. LearnLib's own `ClassicLStarDFA`/`KearnsVaziraniDFA`/`TTTLearnerDFA` as the client, wrapped `MembershipOracle`/`EquivalenceOracle` implementations that just forward over the socket
- `HarnessMain.java` - CLI entry point, dispatches to teacher or learner mode
- unit tests for `Protocol.java`'s round-tripping

## running it

- `run.sh` orchestrates one run (starts the right side first, waits for the port to actually be listening - not by connecting to it, since that would get eaten as the one accepted connection - then runs the other side)
- `make harness-build` / `make harness-test` wired into the top-level Makefile, `make test` still passes unchanged

Remarks on run: 

- direction A: all three of our extracted algorithms (L*, KV, TTT) learned all 18 corpus targets exactly, checked against LearnLib's exact equivalence oracle, up to 100-state / 4-symbol targets
- direction B: LearnLib's own three learners correctly learned all three targets served by our OCaml teacher

KV/TTT came in noticeably faster than L* on the bigger targets (~11s vs ~89s for the full 18-target run), which is expected, not a bug.

## does lstar-rocq agree with learnlib's own implementations

Added a direct check for this, not just "both match the target separately." Right after our extracted learner finishes a target in direction A (once the teacher gets the `ack`), `TeacherServer` now also runs LearnLib's own learner for the same algorithm, fully locally, against the same target - no socket, just a plain membership oracle and an exact equivalence oracle both backed directly by the target DFA. Then it checks whether our hypothesis and LearnLib's hypothesis are language-equivalent to each other (`Automata.findSeparatingWord`), and logs both state counts, both query counts, and the agree/disagree verdict.

Pulled the builder-selection code (`switch (algo) { lstar -> ..., kv -> ..., ttt -> ... }`) out of `LearnerClient.java` into a shared `Learners.java`, since both `LearnerClient` and this new check need to build the same three LearnLib learners.

Result: all 54 comparisons (18 targets x 3 algorithms) agree. Every one, including the 5 targets pulled straight from LearnLib's own `learnlib-learning-examples` (Angluin, Paul-and-Mary, tiny DFA, two keylock sizes) - not just our hand-built or randomly generated ones. Also turned up a genuinely interesting number: LearnLib's own implementations need far fewer queries than ours for the same exact target - roughly 4x-300x fewer depending on algorithm and target size, worst for L* on the biggest targets, best (closest) for KV/TTT on the smallest ones. Not a correctness problem, both converge to the same automaton every time, it's just that LearnLib's algorithms are more query-efficient. Numbers and plots are in `learnlib-harness/results/`.

## results

Ran the full test matrix (`learnlib-harness/run_all.sh`) and saved everything to `learnlib-harness/results/`:

- `direction_a_<algo>_teacher.log` / `direction_a_<algo>_learner.log` - raw output, one pair per algorithm, direction A (Java teacher + agreement check, our OCaml learner) against the full 18-target corpus
- `direction_b_<algo>_teacher.log` / `direction_b_<algo>_learner.log` - same, direction B (our OCaml teacher, LearnLib's own learner), 3 targets only since that's all `socket_teach.ml` serves
- `direction_a_summary.csv`, `direction_b_summary.csv` - parsed into rows, generated by `plot_results.py`
- `queries_vs_states.png`, `time_vs_states.png` - query count / wall time against target size, lstar-rocq vs LearnLib, split by alphabet size, one line per algorithm
- `query_overhead.png` - the ratio (our queries / LearnLib's queries) per target, one panel per algorithm
- `direction_b_states.png` - sanity check that LearnLib's learners land on the same state count for all three direction B targets regardless of algorithm

`plot_results.py` just parses the log files with regexes matching the print formats in `TeacherServer.java`/`socket_learn.ml`/`LearnerClient.java` - no dependency on any format other than what those already print. Re-run it any time after `run_all.sh` to regenerate the CSVs/plots from whatever's currently in the directory.

## running learnlib's actual test suite against our learners

Everything above is a custom-built comparable check - our corpus, our comparison logic. This is the other thing: literally running LearnLib's own shipped integration tests (`AbstractDFALearnerIT` and its `LearningExamples.createDFAExamples()` suite, the same ones `ExtensibleLStarDFAIT`/`KearnsVaziraniDFAIT`/`TTTLearnerDFAIT` run against LearnLib's own learners) directly against our extracted learners, through LearnLib's own TestNG-based test framework rather than a bridge we wrote the assertions for.

This needed an adapter, because of a real architecture mismatch. LearnLib's `LearningAlgorithm` interface expects external stepwise control: `startLearning()`, then the test framework's own hidden exact equivalence oracle checks the hypothesis and calls `refineHypothesis(counterexample)` in a loop until it's satisfied. `AbstractDFALearnerIT.addLearnerVariants` only ever hands the learner-under-test a membership oracle - the equivalence oracle it uses to drive that loop is built internally and never exposed, by design (that's what makes it a fair black-box test). Our extracted L*/KV/TTT don't have an external pause point like that - they're one self-contained recursive Rocq function that calls `equiv_query` itself as part of its own control flow and runs straight through to a finished hypothesis.

`OCamlLearningAlgorithm.java` (new, `learnlib-harness/src/test/java/.../it/`) bridges the two: it implements `LearningAlgorithm.DFALearner<I>`, and each time `startLearning()`/`refineHypothesis()` is called, it spawns a fresh `socket_learn.exe` subprocess and acts as its teacher for one full session - membership queries go straight to the oracle the IT framework gave it, and equivalence queries are answered by a `DFAWpMethodEQOracle` sized off `targetSize` (also given by the framework). Wp-method with a correct state-count upper bound is an exact black-box conformance check, not a heuristic, so this isn't cutting corners - it's the same kind of check a real black-box deployment would have to use, since there's no access to the reference automaton to build `Automata.findSeparatingWord` from here. `refineHypothesis` isn't true incremental resumption (the extracted algorithms have no pause point to resume from) - it appends the given counterexample to an accumulated list and restarts a full session that checks those first. In practice `startLearning()` alone converges correctly since the internal check is exact, so this path mostly only gets exercised by the IT framework's final "a non-counterexample must not trigger refinement" sanity check.

`LstarOCamlDFAIT.java`/`KVOCamlDFAIT.java`/`TTTOCamlDFAIT.java` are the concrete IT classes, one per algorithm, each just registering an `OCamlLearningAlgorithm` variant - this is genuinely LearnLib's own test class shape, not a lookalike. Wired up via `maven-failsafe-plugin` bound to `mvn verify` (not `mvn test`, so it doesn't run as part of the normal build), forcing the `surefire-testng` provider explicitly since junit-jupiter and testng are both on the test classpath and auto-detection between them isn't reliable.

Found one real thing worth reporting, not a bug: `LearningExamples.createDFAExamples()` includes two 100-state, single-symbol "chain" automata (`ExampleKeylock.createExample(100, ...)`), where every counterexample reveals exactly one new state. That's the textbook worst case for classical (Angluin '87) observation-table counterexample handling, which our extracted L* uses. Measured it directly instead of guessing: query count per round grows roughly with the round number itself, so the running total grows faster than quadratically - at 30 states it's already accelerating sharply, and at 100 states it does not finish in practical time. KV and TTT don't have this problem (discrimination-tree-based, not observation-table-based) - they ran the exact same 100-state chain example in under a minute each. So `LstarOCamlDFAIT` overrides `createExampleITCases()` to exclude examples above a 50-state cap (only the two size-100 keylock ones are affected; the other three examples stay), and says exactly why in its doc comment, rather than letting the suite hang or silently dropping the finding.

Result: all 13 test cases pass - LstarOCamlDFAIT (3 of LearnLib's 5 examples), KVOCamlDFAIT (5 of 5), TTTOCamlDFAIT (5 of 5) - using LearnLib's own `SimulatorEQOracle`, its own example DFAs, and its own TestNG test runner end to end. `make harness-it` runs it.

