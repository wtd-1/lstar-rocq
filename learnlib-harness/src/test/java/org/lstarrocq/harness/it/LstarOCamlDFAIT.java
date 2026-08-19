package org.lstarrocq.harness.it;

import de.learnlib.oracle.MembershipOracle.DFAMembershipOracle;
import de.learnlib.testsupport.it.learner.AbstractDFALearnerIT;
import de.learnlib.testsupport.it.learner.LearnerVariantList.DFALearnerVariantList;
import net.automatalib.alphabet.Alphabet;

/**
 * Runs LearnLib's own DFA learner integration test suite -- all 5 examples from
 * {@code LearningExamples.createDFAExamples()}, driven with LearnLib's exact
 * {@code SimulatorEQOracle} -- against lstar-rocq's extracted L* implementation, via
 * {@link OCamlLearningAlgorithm}. Compare with LearnLib's own {@code ExtensibleLStarDFAIT}
 * for the same test suite run against {@code ClassicLStarDFA}.
 *
 * <p>Two of those 5 examples ({@code ExampleKeylock.createExample(100, false)} and
 * {@code ExampleKeylock.createExample(100, true)}) are 100-state, single-symbol "chain"
 * automata where every counterexample reveals exactly one new state -- the textbook worst
 * case for classical (Angluin '87) observation-table counterexample handling, which
 * lstar-rocq's extracted L* uses. Measured directly on a smaller (30-state) instance of the
 * same shape (see changes.md): per-round query count grows faster than quadratically with
 * round number, so these two run for a long time (on the order of an hour or more each, not
 * seconds like the rest of this suite or like KV/TTT on the same examples -- see
 * {@link KVOCamlDFAIT}/{@link TTTOCamlDFAIT}, which use a discrimination tree instead of an
 * observation table and don't have this problem). Kept in rather than excluded, on request,
 * so the full 5/5 LearnLib suite runs and is reported for every algorithm, not just KV/TTT.
 */
public class LstarOCamlDFAIT extends AbstractDFALearnerIT {

    @Override
    protected <I> void addLearnerVariants(
            Alphabet<I> alphabet, int targetSize, DFAMembershipOracle<I> mqOracle, DFALearnerVariantList<I> variants) {
        variants.addLearnerVariant(
                "lstar-rocq", new OCamlLearningAlgorithm<>("lstar", OCamlBinary.SOCKET_LEARN, alphabet, targetSize, mqOracle));
    }
}
