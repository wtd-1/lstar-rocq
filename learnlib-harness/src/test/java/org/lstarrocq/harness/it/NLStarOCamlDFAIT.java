package org.lstarrocq.harness.it;

import de.learnlib.oracle.MembershipOracle.DFAMembershipOracle;
import de.learnlib.oracle.equivalence.SimulatorEQOracle;
import de.learnlib.oracle.membership.DFASimulatorOracle;
import de.learnlib.testsupport.example.LearningExample.DFALearningExample;
import de.learnlib.testsupport.example.dfa.ExampleAngluin;
import de.learnlib.testsupport.example.dfa.ExampleKeylock;
import de.learnlib.testsupport.example.dfa.ExamplePaulAndMary;
import de.learnlib.testsupport.example.dfa.ExampleTinyDFA;
import de.learnlib.testsupport.it.learner.AbstractDFALearnerIT;
import de.learnlib.testsupport.it.learner.LearnerITUtil;
import de.learnlib.testsupport.it.learner.LearnerVariantList.DFALearnerVariantList;
import de.learnlib.testsupport.it.learner.LearnerVariantListImpl.DFALearnerVariantListImpl;
import de.learnlib.testsupport.it.learner.UniversalDeterministicLearnerITCase;
import java.util.ArrayList;
import java.util.List;
import net.automatalib.alphabet.Alphabet;
import net.automatalib.automaton.fsa.DFA;
import org.testng.annotations.Factory;

/**
 * Runs LearnLib's own DFA learner integration test suite against lstar-rocq's extracted NL*
 * implementation, via {@link NLStarOCamlLearningAlgorithm}. Mirrors LearnLib's own {@code
 * NLStarIT} (algorithms/active/nlstar/src/test/java/.../it/NLStarIT.java), which also extends
 * {@code AbstractDFALearnerIT} and also passes {@code targetSize * targetSize} as the round
 * cap -- NL* is a black-box learner over a nondeterministic hypothesis, so it can plausibly
 * need more equivalence-query rounds to converge than a DFA-hypothesis learner does for the
 * same target, and LearnLib's own maintainers already picked that bound for exactly this
 * situation.
 *
 * <p>The two {@code ExampleKeylock} cases are run at a substituted size (see {@code
 * scale-sizes.properties}, key {@code keylock.nlstar}, default 9) rather than LearnLib's real
 * size 100 -- the same kind of substitution {@link LstarOCamlDFAIT} makes for L* (there,
 * key {@code keylock.lstar}, default 25), but NL* needs a much smaller bound: measured
 * directly, NL*'s wall time on this chain shape roughly doubles per additional state (2.7s at
 * n=5, 55.4s at n=9, 102.2s at n=10), so 9 is the largest size confirmed to stay under a
 * minute -- well below L*'s ceiling of 25, consistent with NL*'s residual-language-inclusion
 * checks being strictly more expensive per table cell than L*'s simple equality checks (see
 * changes.md).
 */
public class NLStarOCamlDFAIT extends AbstractDFALearnerIT {

    @Override
    @Factory
    public Object[] createExampleITCases() {
        int keylockSize = ScaleSizes.get("keylock.nlstar");
        List<DFALearningExample<?>> examples =
                List.of(
                        ExampleAngluin.createExample(),
                        ExamplePaulAndMary.createExample(),
                        ExampleKeylock.createExample(keylockSize, false),
                        ExampleKeylock.createExample(keylockSize, true),
                        ExampleTinyDFA.createExample());
        List<UniversalDeterministicLearnerITCase<?, ?, ?>> result = new ArrayList<>();
        for (DFALearningExample<?> example : examples) {
            result.addAll(createAllVariantsITCase(example));
        }
        return result.toArray();
    }

    private <I> List<UniversalDeterministicLearnerITCase<I, Boolean, DFA<?, I>>> createAllVariantsITCase(
            DFALearningExample<I> example) {
        Alphabet<I> alphabet = example.getAlphabet();
        DFAMembershipOracle<I> mqOracle = new DFASimulatorOracle<>(example.getReferenceAutomaton());
        DFALearnerVariantListImpl<I> variants = new DFALearnerVariantListImpl<>();
        addLearnerVariants(alphabet, example.getReferenceAutomaton().size(), mqOracle, variants);
        return LearnerITUtil.createExampleITCases(
                example, variants, new SimulatorEQOracle<>(example.getReferenceAutomaton()));
    }

    @Override
    protected <I> void addLearnerVariants(
            Alphabet<I> alphabet, int targetSize, DFAMembershipOracle<I> mqOracle, DFALearnerVariantList<I> variants) {
        variants.addLearnerVariant(
                "nlstar-rocq",
                new NLStarOCamlLearningAlgorithm<>(OCamlBinary.SOCKET_LEARN, alphabet, targetSize, mqOracle),
                targetSize * targetSize);
    }
}
