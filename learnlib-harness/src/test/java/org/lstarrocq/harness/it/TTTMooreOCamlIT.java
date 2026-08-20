package org.lstarrocq.harness.it;

import de.learnlib.oracle.MembershipOracle.MooreMembershipOracle;
import de.learnlib.testsupport.it.learner.AbstractMooreLearnerIT;
import de.learnlib.testsupport.it.learner.LearnerVariantList.MooreLearnerVariantList;
import net.automatalib.alphabet.Alphabet;

/** Runs LearnLib's own Moore learner integration test suite against lstar-rocq's extracted
 * Moore-TTT implementation, via {@link MooreOCamlLearningAlgorithm}. */
public class TTTMooreOCamlIT extends AbstractMooreLearnerIT {

    @Override
    protected <I, O> void addLearnerVariants(
            Alphabet<I> alphabet, int targetSize, MooreMembershipOracle<I, O> mqOracle, MooreLearnerVariantList<I, O> variants) {
        variants.addLearnerVariant(
                "ttt_moore-rocq",
                new MooreOCamlLearningAlgorithm<>("ttt_moore", OCamlBinary.SOCKET_LEARN, alphabet, targetSize, mqOracle));
    }
}
