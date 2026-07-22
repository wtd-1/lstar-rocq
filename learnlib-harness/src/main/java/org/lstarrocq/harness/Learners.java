package org.lstarrocq.harness;

import de.learnlib.acex.AcexAnalyzers;
import de.learnlib.algorithm.LearningAlgorithm;
import de.learnlib.algorithm.kv.dfa.KearnsVaziraniDFABuilder;
import de.learnlib.algorithm.lstar.dfa.ClassicLStarDFABuilder;
import de.learnlib.algorithm.ttt.dfa.TTTLearnerDFABuilder;
import de.learnlib.oracle.MembershipOracle;
import net.automatalib.alphabet.Alphabet;
import net.automatalib.automaton.fsa.DFA;

/**
 * Builds one of LearnLib's own DFA learners by name, shared between
 * {@link LearnerClient} (which wires one up to talk to our OCaml teacher
 * over a socket) and {@link TeacherServer}'s agreement check (which runs one
 * fully locally, against the same target our extracted learner just saw, to
 * see whether the two implementations converge to the same automaton).
 */
public final class Learners {

    private Learners() {}

    public static LearningAlgorithm<DFA<?, String>, String, Boolean> build(
            String algo, Alphabet<String> alphabet, MembershipOracle<String, Boolean> oracle) {
        return switch (algo) {
            case "lstar" -> new ClassicLStarDFABuilder<String>()
                    .withAlphabet(alphabet)
                    .withOracle(oracle)
                    .create();
            case "kv" -> new KearnsVaziraniDFABuilder<String>()
                    .withAlphabet(alphabet)
                    .withOracle(oracle)
                    .withCounterexampleAnalyzer(AcexAnalyzers.LINEAR_FWD)
                    .create();
            case "ttt" -> new TTTLearnerDFABuilder<String>()
                    .withAlphabet(alphabet)
                    .withOracle(oracle)
                    .withAnalyzer(AcexAnalyzers.LINEAR_FWD)
                    .create();
            default -> throw new IllegalArgumentException("unknown algorithm: " + algo);
        };
    }
}
