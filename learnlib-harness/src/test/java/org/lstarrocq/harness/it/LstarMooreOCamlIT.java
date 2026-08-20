package org.lstarrocq.harness.it;

import de.learnlib.oracle.MembershipOracle.MooreMembershipOracle;
import de.learnlib.testsupport.it.learner.AbstractMooreLearnerIT;
import de.learnlib.testsupport.it.learner.LearnerVariantList.MooreLearnerVariantList;
import net.automatalib.alphabet.Alphabet;

/** Runs LearnLib's own Moore learner integration test suite against lstar-rocq's extracted
 * Moore-L* implementation, via {@link MooreOCamlLearningAlgorithm}. Compare with LearnLib's
 * own {@code ExtensibleLStarMooreIT} for the same suite against its own Moore-L*. */
public class LstarMooreOCamlIT extends AbstractMooreLearnerIT {

    @Override
    protected <I, O> void addLearnerVariants(
            Alphabet<I> alphabet, int targetSize, MooreMembershipOracle<I, O> mqOracle, MooreLearnerVariantList<I, O> variants) {
        variants.addLearnerVariant(
                "lstar_moore-rocq",
                new MooreOCamlLearningAlgorithm<>("lstar_moore", OCamlBinary.SOCKET_LEARN, alphabet, targetSize, mqOracle));
    }
}
