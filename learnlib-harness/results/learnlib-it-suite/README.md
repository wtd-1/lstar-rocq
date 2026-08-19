# LearnLib IT suite results

Output of running LearnLib's own `AbstractDFALearnerIT`-based integration test suite
(`learnlib-harness/src/test/java/org/lstarrocq/harness/it/*.java`) against lstar-rocq's
extracted L*, KV, and TTT, via `mvn verify` (`make harness-it`).

- `console.log` - full raw console output of that run, including LearnLib's own
  `LEARNLIB_EVENT Passed learner integration test ...` lines (the source of truth for which
  test case is which - see below) and this project's own per-round `eq #N` progress lines
  from `OCamlLearningAlgorithm`.
- `testng-results.xml` / `failsafe-summary.xml` - copied from
  `learnlib-harness/target/failsafe-reports/` (TestNG's/failsafe's own structured reports;
  that directory is build output, gitignored, and gets overwritten by the next `mvn verify`
  run, hence the copy here).
- `summary.csv` - `test_case,seconds`, parsed straight out of `console.log`'s
  `LEARNLIB_EVENT` lines.

Note: `testng-results.xml`/`failsafe-summary.xml` record pass/fail and duration per case but
*not* the descriptive test name (`OCamlLearningAlgorithm[kv-rocq]/ExampleKeylock`, etc.) -
TestNG's default reporters only wrote the generic method name (`testLearning`) for these
`@Factory`-generated instances. The descriptive names only appear in `console.log`
(from `ITest.getTestName()`, logged directly by LearnLib's own
`AbstractLearnerVariantITCase`), which is why that file is kept as the primary record.

This run covered 13 of LearnLib's 15 possible cases (5 `LearningExamples.createDFAExamples()`
examples x 3 algorithms): all 5 for KV and TTT, 3 of 5 for L* at the time of this run (the two
100-state `ExampleKeylock` cases were still in progress for L* - see changes.md for why they
take vastly longer, and for the follow-up run once they complete).
