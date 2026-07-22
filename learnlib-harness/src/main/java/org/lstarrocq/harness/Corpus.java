package org.lstarrocq.harness;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import net.automatalib.alphabet.Alphabet;
import net.automatalib.alphabet.impl.Alphabets;
import net.automatalib.automaton.fsa.impl.CompactDFA;
import net.automatalib.util.automaton.random.RandomAutomata;

/**
 * Target DFAs for Direction A ({@link TeacherServer}): our extracted L*, KV, TTT
 * learner (examples/socket_learn.ml) is the socket client, and each of these
 * is fed to it in turn as ground truth, checked with an exact equivalence
 * oracle ({@code Automata.findSeparatingWord}).
 *
 * <p>Three small hand-built targets mirror exactly the languages
 * examples/socket_teach.ml serves on the OCaml side (Direction B), so the
 * same languages get learned from both directions. The rest are randomly
 * generated DFAs of increasing size, per the "LearnLib's built-in examples +
 * random automata" corpus the harness was scoped to use.
 */
public final class Corpus {

    private Corpus() {}

    public record Target(String name, Alphabet<String> alphabet, CompactDFA<String> dfa) {}

    /** The three hand-built targets only -- same languages examples/socket_teach.ml serves,
     * useful as a fast smoke test before running the full corpus. */
    public static List<Target> small() {
        return List.of(alternating(), endsIn01(), mod3());
    }

    public static List<Target> all() {
        List<Target> targets = new ArrayList<>();
        targets.add(alternating());
        targets.add(endsIn01());
        targets.add(mod3());
        targets.addAll(random());
        return targets;
    }

    private static Alphabet<String> binaryAlphabet() {
        return Alphabets.fromArray("0", "1");
    }

    /** Same language as examples/alternating.ml / socket_teach.ml's "alternating" target. */
    public static Target alternating() {
        Alphabet<String> alphabet = binaryAlphabet();
        CompactDFA<String> dfa = new CompactDFA<>(alphabet, 4);
        int q0 = dfa.addInitialState(true);
        int q1 = dfa.addState(true);
        int q2 = dfa.addState(true);
        int q3 = dfa.addState(false);
        dfa.addTransition(q0, "0", q1, null);
        dfa.addTransition(q0, "1", q2, null);
        dfa.addTransition(q1, "0", q3, null);
        dfa.addTransition(q1, "1", q2, null);
        dfa.addTransition(q2, "0", q1, null);
        dfa.addTransition(q2, "1", q3, null);
        dfa.addTransition(q3, "0", q3, null);
        dfa.addTransition(q3, "1", q3, null);
        return new Target("alternating", alphabet, dfa);
    }

    /** Same language as examples/socket_teach.ml's "ends_in_01" target: minimal 3-state DFA
     * tracking whether the last two symbols read were "0" then "1". */
    public static Target endsIn01() {
        Alphabet<String> alphabet = binaryAlphabet();
        CompactDFA<String> dfa = new CompactDFA<>(alphabet, 3);
        int notZero = dfa.addInitialState(false);
        int lastZero = dfa.addState(false);
        int endsOne = dfa.addState(true);
        dfa.addTransition(notZero, "0", lastZero, null);
        dfa.addTransition(notZero, "1", notZero, null);
        dfa.addTransition(lastZero, "0", lastZero, null);
        dfa.addTransition(lastZero, "1", endsOne, null);
        dfa.addTransition(endsOne, "0", lastZero, null);
        dfa.addTransition(endsOne, "1", notZero, null);
        return new Target("ends_in_01", alphabet, dfa);
    }

    /** Same language as examples/socket_teach.ml's "mod3" target: binary value mod 3 == 0. */
    public static Target mod3() {
        Alphabet<String> alphabet = binaryAlphabet();
        CompactDFA<String> dfa = new CompactDFA<>(alphabet, 3);
        int r0 = dfa.addInitialState(true);
        int r1 = dfa.addState(false);
        int r2 = dfa.addState(false);
        dfa.addTransition(r0, "0", r0, null);
        dfa.addTransition(r0, "1", r1, null);
        dfa.addTransition(r1, "0", r2, null);
        dfa.addTransition(r1, "1", r0, null);
        dfa.addTransition(r2, "0", r1, null);
        dfa.addTransition(r2, "1", r2, null);
        return new Target("mod3", alphabet, dfa);
    }

    /** Randomly generated DFAs of increasing size and alphabet, for scaling the corpus up
     * beyond the small hand-written targets. Seeded for reproducibility. */
    public static List<Target> random() {
        List<Target> targets = new ArrayList<>();
        Random rng = new Random(0xC0FFEE);
        int[] sizes = {5, 10, 20, 50, 100};
        int[] alphabetSizes = {2, 4};
        for (int alphabetSize : alphabetSizes) {
            Alphabet<String> alphabet =
                    Alphabets.fromList(
                            IntStream.range(0, alphabetSize).mapToObj(String::valueOf).collect(Collectors.toList()));
            for (int size : sizes) {
                CompactDFA<String> dfa = RandomAutomata.randomDFA(rng, size, alphabet, true);
                targets.add(new Target("random_n" + size + "_a" + alphabetSize, alphabet, dfa));
            }
        }
        return targets;
    }
}
