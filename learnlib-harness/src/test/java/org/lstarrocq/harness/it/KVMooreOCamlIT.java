package org.lstarrocq.harness.it;

import de.learnlib.oracle.MembershipOracle.MooreMembershipOracle;
import de.learnlib.testsupport.it.learner.AbstractMooreLearnerIT;
import de.learnlib.testsupport.it.learner.LearnerVariantList.MooreLearnerVariantList;
import net.automatalib.alphabet.Alphabet;

/** Runs LearnLib's own Moore learner integration test suite against lstar-rocq's extracted
 * Moore-KV implementation, via {@link MooreOCamlLearningAlgorithm}. */
public class KVMooreOCamlIT extends AbstractMooreLearnerIT {

    @Override
    protected <I, O> void addLearnerVariants(
            Alphabet<I> alphabet, int targetSize, MooreMembershipOracle<I, O> mqOracle, MooreLearnerVariantList<I, O> variants) {
        variants.addLearnerVariant(
                "kv_moore-rocq",
                new MooreOCamlLearningAlgorithm<>("kv_moore", OCamlBinary.SOCKET_LEARN, alphabet, targetSize, mqOracle));
    }
}
