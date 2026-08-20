package org.lstarrocq.harness.it;

import de.learnlib.oracle.MembershipOracle.MealyMembershipOracle;
import de.learnlib.testsupport.it.learner.AbstractMealyLearnerIT;
import de.learnlib.testsupport.it.learner.LearnerVariantList.MealyLearnerVariantList;
import net.automatalib.alphabet.Alphabet;

/** Runs LearnLib's own Mealy learner integration test suite against lstar-rocq's extracted
 * Mealy-KV implementation, via {@link MealyOCamlLearningAlgorithm}. */
public class KVMealyOCamlIT extends AbstractMealyLearnerIT {

    @Override
    protected <I, O> void addLearnerVariants(
            Alphabet<I> alphabet, int targetSize, MealyMembershipOracle<I, O> mqOracle, MealyLearnerVariantList<I, O> variants) {
        variants.addLearnerVariant(
                "kv_mealy-rocq",
                new MealyOCamlLearningAlgorithm<>("kv_mealy", OCamlBinary.SOCKET_LEARN, alphabet, targetSize, mqOracle));
    }
}
