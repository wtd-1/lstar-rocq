package org.lstarrocq.harness.it;

import de.learnlib.oracle.MembershipOracle.DFAMembershipOracle;
import de.learnlib.testsupport.it.learner.AbstractDFALearnerIT;
import de.learnlib.testsupport.it.learner.LearnerVariantList.DFALearnerVariantList;
import net.automatalib.alphabet.Alphabet;

/** Runs LearnLib's own DFA learner integration test suite against lstar-rocq's extracted
 * TTT implementation, via {@link OCamlLearningAlgorithm}. Compare with LearnLib's own
 * {@code TTTLearnerDFAIT} for the same suite against {@code TTTLearnerDFA}. */
public class TTTOCamlDFAIT extends AbstractDFALearnerIT {

    @Override
    protected <I> void addLearnerVariants(
            Alphabet<I> alphabet, int targetSize, DFAMembershipOracle<I> mqOracle, DFALearnerVariantList<I> variants) {
        variants.addLearnerVariant(
                "ttt-rocq", new OCamlLearningAlgorithm<>("ttt", OCamlBinary.SOCKET_LEARN, alphabet, targetSize, mqOracle));
    }
}
