package org.lstarrocq.harness.it;

import de.learnlib.oracle.MembershipOracle.DFAMembershipOracle;
import de.learnlib.testsupport.it.learner.AbstractDFALearnerIT;
import de.learnlib.testsupport.it.learner.LearnerVariantList.DFALearnerVariantList;
import net.automatalib.alphabet.Alphabet;

/** Runs LearnLib's own DFA learner integration test suite against lstar-rocq's extracted
 * Kearns-Vazirani implementation, via {@link OCamlLearningAlgorithm}. Compare with
 * LearnLib's own {@code KearnsVaziraniDFAIT} for the same suite against
 * {@code KearnsVaziraniDFA}. */
public class KVOCamlDFAIT extends AbstractDFALearnerIT {

    @Override
    protected <I> void addLearnerVariants(
            Alphabet<I> alphabet, int targetSize, DFAMembershipOracle<I> mqOracle, DFALearnerVariantList<I> variants) {
        variants.addLearnerVariant(
                "kv-rocq", new OCamlLearningAlgorithm<>("kv", OCamlBinary.SOCKET_LEARN, alphabet, targetSize, mqOracle));
    }
}
