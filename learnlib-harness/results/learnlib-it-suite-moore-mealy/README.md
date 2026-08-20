# LearnLib Mealy/Moore IT suite results

Output of running LearnLib's own `AbstractMealyLearnerIT`/`AbstractMooreLearnerIT`-based
integration test suites (`learnlib-harness/src/test/java/org/lstarrocq/harness/it/{Lstar,KV,TTT}{Mealy,Moore}OCamlIT.java`)
against lstar-rocq's extracted Mealy-L*/KV/TTT and Moore-L*/KV/TTT, via `mvn verify
-Dit.test=LstarMealyOCamlIT,TTTMealyOCamlIT,KVMealyOCamlIT,LstarMooreOCamlIT,KVMooreOCamlIT,TTTMooreOCamlIT`.

- `console.log` - full raw console output, including the `LEARNLIB_EVENT Passed learner
  integration test ...` lines (source of truth for which test case is which) and this
  project's own per-round `eq #N` progress lines from `MealyOCamlLearningAlgorithm`/
  `MooreOCamlLearningAlgorithm`.
- `testng-results.xml` / `failsafe-summary.xml` - copied from
  `learnlib-harness/target/failsafe-reports/` (gitignored build output, overwritten by the
  next `mvn verify` run).
- `summary.csv` - `test_case,seconds`, parsed from `console.log`.

Result: all 24 test cases pass -- LearnLib's full 6-example Mealy suite (CoffeeMachine, Grid,
ShahbazGroz, Stack, RandomMealy at 100 states, TinyMealy) x 3 algorithms, plus the partial
StateLocalInputMealy example, plus all 3 algorithms against the 100-state RandomMoore example
(the only example `LearningExamples.createMooreExamples()` ships).

This run followed two real bug fixes discovered in the process (see changes.md for the full
account):

1. **Output alphabet discovery bound too shallow.** `OutputAlphabetDiscovery` (needed because
   `AbstractMealyLearnerIT`/`AbstractMooreLearnerIT` never hand the adapter an output
   alphabet, only a membership oracle) originally searched words up to length 6, missing
   `ExampleGrid`'s outputs (every one of its 50 transitions has a unique value, and the
   farthest is 8 steps away). Widened to length 12 / 200k queries.
2. **Untrimmed counterexamples.** `DFAWpMethodEQOracle`'s Mealy/Moore analogues can return a
   counterexample whose full output sequence differs somewhere in the middle but happens to
   coincide again at the very last symbol -- which Mealy/Moore counterexample-processing
   algorithms don't recognize as valid (they reason about the last symbol specifically). This
   caused `KV_Mealy_Binary` on `ExampleCoffeeMachine` and (initially thought to be
   algorithm-specific, but confirmed broader) L*-Mealy and TTT-Mealy on `ExampleStack` to get
   stuck: correct, genuine counterexample, hypothesis never advances, query count grows
   without bound. Verified with a from-scratch, hand-verified, non-Java OCaml reproduction of
   `ExampleStack`'s exact shape that all three algorithms converge correctly when given a
   *properly trimmed* counterexample -- ruling out any upstream Rocq bug. Fixed by trimming
   every counterexample to its first point of actual divergence before returning it.
