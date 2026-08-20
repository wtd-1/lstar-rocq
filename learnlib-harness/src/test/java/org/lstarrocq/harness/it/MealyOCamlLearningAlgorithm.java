package org.lstarrocq.harness.it;

import de.learnlib.algorithm.LearningAlgorithm;
import de.learnlib.oracle.EquivalenceOracle;
import de.learnlib.oracle.MembershipOracle;
import de.learnlib.oracle.equivalence.MealyWpMethodEQOracle;
import de.learnlib.query.DefaultQuery;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import net.automatalib.alphabet.Alphabet;
import net.automatalib.automaton.transducer.MealyMachine;
import net.automatalib.automaton.transducer.impl.CompactMealy;
import net.automatalib.word.Word;
import net.automatalib.word.WordBuilder;
import org.lstarrocq.harness.Connection;
import org.lstarrocq.harness.Protocol;

/**
 * The Mealy analogue of {@link OCamlLearningAlgorithm} (see {@link MooreOCamlLearningAlgorithm}
 * for the Moore one, which shares the output-alphabet-discovery problem this also has -- see
 * {@link OutputAlphabetDiscovery}). The one Mealy-specific wrinkle: {@code
 * Teacher.MEALYTEACHER.output_lang} wants the one-step output for a (prefix, next symbol) pair,
 * not a whole word's output sequence, so {@code "mq_mealy"} requests send {@code prefix @
 * [sym]} as one word and expect back only that last step's output symbol -- computed here by
 * querying the real oracle with the full word and taking {@link Word#lastSymbol()}.
 */
final class MealyOCamlLearningAlgorithm<I, O> implements LearningAlgorithm.MealyLearner<I, O> {

    private static final int ACCEPT_TIMEOUT_SECONDS = 30;
    private static final int EXIT_TIMEOUT_SECONDS = 30;

    private final String algo;
    private final Path binary;
    private final Alphabet<I> alphabet;
    private final int targetSize;
    private final MembershipOracle.MealyMembershipOracle<I, O> mqOracle;
    private final List<DefaultQuery<I, Word<O>>> externalCounterexamples = new ArrayList<>();

    private final List<O> outputByIndex;
    private final Map<O, Integer> outputIndex;

    private CompactMealy<I, O> hypothesis;

    MealyOCamlLearningAlgorithm(
            String algo,
            Path binary,
            Alphabet<I> alphabet,
            int targetSize,
            MembershipOracle.MealyMembershipOracle<I, O> mqOracle) {
        this.algo = algo;
        this.binary = binary;
        this.alphabet = alphabet;
        this.targetSize = targetSize;
        this.mqOracle = mqOracle;
        this.outputByIndex = OutputAlphabetDiscovery.discover(alphabet, mqOracle);
        this.outputIndex = new HashMap<>();
        for (int i = 0; i < outputByIndex.size(); i++) {
            outputIndex.put(outputByIndex.get(i), i);
        }
    }

    @Override
    public void startLearning() {
        hypothesis = runSession();
    }

    @Override
    public boolean refineHypothesis(DefaultQuery<I, Word<O>> ceQuery) {
        if (Objects.equals(hypothesis.computeOutput(ceQuery.getInput()), ceQuery.getOutput())) {
            return false;
        }
        externalCounterexamples.add(ceQuery);
        hypothesis = runSession();
        return true;
    }

    @Override
    public MealyMachine<?, I, ?, O> getHypothesisModel() {
        return hypothesis;
    }

    private EquivalenceOracle<MealyMachine<?, I, ?, O>, I, Word<O>> wpMethodOracle(int hypothesisSize) {
        int extraStates = Math.max(1, targetSize - hypothesisSize);
        return new MealyWpMethodEQOracle<>(mqOracle, extraStates);
    }

    private CompactMealy<I, O> runSession() {
        Process process = null;
        try (ServerSocket server = new ServerSocket(0)) {
            server.setSoTimeout((int) TimeUnit.SECONDS.toMillis(ACCEPT_TIMEOUT_SECONDS));
            int port = server.getLocalPort();

            ProcessBuilder pb = new ProcessBuilder(binary.toString(), algo, Integer.toString(port));
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.PIPE);
            process = pb.start();
            Process spawned = process;
            Thread drain = new Thread(() -> {
                try {
                    spawned.getInputStream().readAllBytes();
                } catch (IOException ignored) {
                    // process exited/closed; nothing left to drain
                }
            });
            drain.setDaemon(true);
            drain.start();

            Socket client;
            try {
                client = server.accept();
            } catch (SocketTimeoutException e) {
                throw new IllegalStateException(
                        algo + ": socket_learn.exe never connected within " + ACCEPT_TIMEOUT_SECONDS + "s");
            }

            CompactMealy<I, O> lastVerified;
            try (Connection conn = Connection.of(client)) {
                List<String> wireAlphabet =
                        IntStream.range(0, alphabet.size()).mapToObj(String::valueOf).collect(Collectors.toList());
                List<String> wireOutputAlphabet = IntStream.range(0, outputByIndex.size())
                        .mapToObj(String::valueOf)
                        .collect(Collectors.toList());
                conn.sendLine(Protocol.configLine(wireAlphabet, wireOutputAlphabet, "it_" + algo));

                lastVerified = null;
                long sessionStart = System.nanoTime();
                long mqCount = 0;
                int eqCount = 0;
                while (true) {
                    String line = conn.readNonBlankLine();
                    if (line == null) {
                        throw new IllegalStateException(algo + ": connection closed mid-session");
                    }
                    if (Protocol.isAck(line)) {
                        conn.sendLine(Protocol.DONE_LINE);
                        break;
                    } else if (Protocol.isMqMealy(line)) {
                        mqCount++;
                        Word<I> word = decodeWord(Protocol.wordOf(line));
                        O last = mqOracle.answerQuery(word).lastSymbol();
                        conn.sendLine(Integer.toString(outputIndex.get(last)));
                    } else if (Protocol.isEqMealy(line)) {
                        eqCount++;
                        Protocol.MealyHypothesis parsed = Protocol.parseMealyHypothesis(line);
                        CompactMealy<I, O> candidate = toCompactMealy(parsed);
                        long eqStart = System.nanoTime();
                        Word<I> counterexample = findCounterExample(candidate);
                        double eqSeconds = (System.nanoTime() - eqStart) / 1e9;
                        double elapsed = (System.nanoTime() - sessionStart) / 1e9;
                        System.err.printf(
                                "  [%s] eq #%-4d wire_states=%-4d wire_trans=%-4d hyp_states=%-4d mq_so_far=%-8d"
                                        + " eq_check=%.3fs elapsed=%.1fs counterexample=%s%n",
                                algo,
                                eqCount,
                                parsed.stateIds().size(),
                                parsed.transitions().size(),
                                candidate.size(),
                                mqCount,
                                eqSeconds,
                                elapsed,
                                counterexample);
                        if (counterexample == null) {
                            lastVerified = candidate;
                            conn.sendLine("NONE");
                        } else {
                            conn.sendLine(encodeWord(counterexample));
                        }
                    } else {
                        throw new IllegalStateException(algo + ": unexpected line: " + line);
                    }
                }
            }
            if (lastVerified == null) {
                throw new IllegalStateException(algo + ": learner acked without a verified hypothesis");
            }
            if (!process.waitFor(EXIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException(algo + ": socket_learn.exe did not exit after ack");
            }
            return lastVerified;
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(algo + ": bridging to OCaml learner failed", e);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private Word<I> findCounterExample(CompactMealy<I, O> candidate) {
        for (DefaultQuery<I, Word<O>> ce : externalCounterexamples) {
            if (!Objects.equals(candidate.computeOutput(ce.getInput()), ce.getOutput())) {
                return trimToFirstDivergence(candidate, ce.getInput());
            }
        }
        DefaultQuery<I, Word<O>> found = wpMethodOracle(candidate.size()).findCounterExample(candidate, alphabet);
        return found == null ? null : trimToFirstDivergence(candidate, found.getInput());
    }

    /** {@code Teacher.MEALYTEACHER.output_lang}'s counterexample-incorporation reasons about
     * one step's output at a time (the last symbol of whatever word it's given), so a
     * counterexample must actually diverge <em>at its last symbol</em> -- {@link
     * MealyWpMethodEQOracle} instead finds any word where the full output sequences differ
     * anywhere, which can "converge back" to matching outputs by the end even though some
     * earlier step genuinely differed (confirmed directly: on {@code ExampleCoffeeMachine},
     * it returned a word whose candidate/real outputs were {@code "ok ok error error"} vs
     * {@code "ok ok coffee! error"} -- diverging at position 3, but identical again at the
     * last position). Untrimmed, the OCaml learner never recognizes such a word as a real
     * counterexample and gets stuck re-asking forever. Trimming to the first point of actual
     * divergence is what every Mealy counterexample-processing algorithm expects. */
    private Word<I> trimToFirstDivergence(CompactMealy<I, O> candidate, Word<I> word) {
        Word<O> candidateOutput = candidate.computeOutput(word);
        Word<O> realOutput = mqOracle.answerQuery(word);
        for (int i = 0; i < word.length(); i++) {
            if (!Objects.equals(candidateOutput.getSymbol(i), realOutput.getSymbol(i))) {
                return word.prefix(i + 1);
            }
        }
        return word;
    }

    private Word<I> decodeWord(List<String> tokens) {
        WordBuilder<I> wb = new WordBuilder<>(tokens.size());
        for (String t : tokens) {
            wb.add(alphabet.getSymbol(Integer.parseInt(t)));
        }
        return wb.toWord();
    }

    private String encodeWord(Word<I> word) {
        return word.asList().stream()
                .map(sym -> Integer.toString(alphabet.getSymbolIndex(sym)))
                .collect(Collectors.joining(","));
    }

    private CompactMealy<I, O> toCompactMealy(Protocol.MealyHypothesis hyp) {
        int numStates = hyp.stateIds().size();
        CompactMealy<I, O> mealy = new CompactMealy<>(alphabet, numStates);
        for (int i = 0; i < numStates; i++) {
            mealy.addState();
        }
        mealy.setInitial(hyp.initialState(), true);
        for (Protocol.MealyTransitionEntry t : hyp.transitions()) {
            O output = outputByIndex.get(Integer.parseInt(t.output()));
            mealy.addTransition(t.from(), alphabet.getSymbol(Integer.parseInt(t.input())), t.to(), output);
        }
        return mealy;
    }
}
