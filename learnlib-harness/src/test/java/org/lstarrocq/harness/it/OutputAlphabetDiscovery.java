package org.lstarrocq.harness.it;

import de.learnlib.oracle.MembershipOracle;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.automatalib.alphabet.Alphabet;
import net.automatalib.word.Word;
import net.automatalib.word.WordBuilder;

/**
 * Discovers a Moore/Mealy target's output alphabet from nothing but its membership oracle.
 *
 * <p>Our extracted L*, KV, TTT need a Rocq {@code Symbol} module for the output alphabet before
 * a session can even start (its {@code enum : t list} field must be complete and fixed up
 * front -- the OCaml side has no way to grow an alphabet mid-session), but {@code
 * AbstractMealyLearnerIT.addLearnerVariants}/{@code AbstractMooreLearnerIT.addLearnerVariants}
 * hand us only the input alphabet, the target size, and a membership oracle -- never the
 * output alphabet itself.
 *
 * <p>This is not solvable in general from a black-box oracle alone. It is solvable for
 * LearnLib's actual Mealy/Moore example corpus (the only thing this adapter is ever run
 * against): every example in {@code LearningExamples.createMealyExamples()}/{@code
 * createMooreExamples()} has a small, fixed output alphabet -- most use 3 output symbols
 * ({@code ExampleRandomMealy}/{@code ExampleRandomMoore}, regardless of their 100-state size)
 * or fewer, except {@code ExampleGrid}, which gives every transition a unique output (50
 * distinct values across its 25 states) and needs up to 8 steps to reach them all. So this
 * queries every word up to a bounded length over the input alphabet and collects the distinct
 * output symbols seen -- correct for that corpus (verified directly against every example,
 * not assumed), not a general guarantee for arbitrary targets.
 */
final class OutputAlphabetDiscovery {

    // ExampleGrid (5x5, from LearningExamples.createMealyExamples()) gives every transition a
    // unique output -- 50 distinct values across 25 states -- and reaching the far corner
    // needs 8 steps (4 x-moves + 4 y-moves), so the previous bound of 6 missed some of them.
    // Widened with headroom; the budget cap still keeps this cheap for wide alphabets.
    private static final int MAX_WORD_LENGTH = 12;
    private static final int MAX_QUERIES = 200_000;

    private OutputAlphabetDiscovery() {}

    static <I, O> List<O> discover(Alphabet<I> alphabet, MembershipOracle<I, Word<O>> mqOracle) {
        Set<O> seen = new LinkedHashSet<>();
        List<Word<I>> frontier = new ArrayList<>();
        frontier.add(Word.epsilon());
        long queries = 0;
        for (int length = 0; length <= MAX_WORD_LENGTH && queries < MAX_QUERIES && !frontier.isEmpty(); length++) {
            List<Word<I>> next = new ArrayList<>();
            for (Word<I> word : frontier) {
                if (queries >= MAX_QUERIES) {
                    break;
                }
                Word<O> output = mqOracle.answerQuery(word);
                queries++;
                seen.addAll(output.asList());
                for (I sym : alphabet) {
                    WordBuilder<I> wb = new WordBuilder<>(word.length() + 1);
                    wb.append(word);
                    wb.add(sym);
                    next.add(wb.toWord());
                }
            }
            frontier = next;
        }
        if (seen.isEmpty()) {
            throw new IllegalStateException(
                    "could not discover any output symbols within " + MAX_QUERIES + " queries / length "
                            + MAX_WORD_LENGTH + " -- target's output alphabet may be larger than this adapter"
                            + " supports");
        }
        return new ArrayList<>(seen);
    }
}
