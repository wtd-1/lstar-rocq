package org.lstarrocq.harness.it;

import de.learnlib.algorithm.LearningAlgorithm;
import de.learnlib.oracle.EquivalenceOracle;
import de.learnlib.oracle.MembershipOracle;
import de.learnlib.oracle.equivalence.MooreWpMethodEQOracle;
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
import net.automatalib.automaton.transducer.MooreMachine;
import net.automatalib.automaton.transducer.impl.CompactMoore;
import net.automatalib.word.Word;
import net.automatalib.word.WordBuilder;
import org.lstarrocq.harness.Connection;
import org.lstarrocq.harness.Protocol;

/**
 * The Moore analogue of {@link OCamlLearningAlgorithm}: bridges lstar-rocq's extracted
 * Moore-L*, Moore-KV, Moore-TTT to LearnLib's {@link LearningAlgorithm.MooreLearner}. See {@link
 * OutputAlphabetDiscovery} for why this needs its own upfront discovery pass that {@link
 * OCamlLearningAlgorithm} doesn't: Moore's hypothesis type carries an output alphabet that
 * {@code AbstractMooreLearnerIT.addLearnerVariants} never hands over, unlike DFA's fixed
 * two-value {@code Boolean}.
 */
final class MooreOCamlLearningAlgorithm<I, O> implements LearningAlgorithm.MooreLearner<I, O> {

    private static final int ACCEPT_TIMEOUT_SECONDS = 30;
    private static final int EXIT_TIMEOUT_SECONDS = 30;

    private final String algo;
    private final Path binary;
    private final Alphabet<I> alphabet;
    private final int targetSize;
    private final MembershipOracle.MooreMembershipOracle<I, O> mqOracle;
    private final List<DefaultQuery<I, Word<O>>> externalCounterexamples = new ArrayList<>();

    private final List<O> outputByIndex;
    private final Map<O, Integer> outputIndex;

    private CompactMoore<I, O> hypothesis;

    MooreOCamlLearningAlgorithm(
            String algo,
            Path binary,
            Alphabet<I> alphabet,
            int targetSize,
            MembershipOracle.MooreMembershipOracle<I, O> mqOracle) {
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
    public MooreMachine<?, I, ?, O> getHypothesisModel() {
        return hypothesis;
    }

    private EquivalenceOracle<MooreMachine<?, I, ?, O>, I, Word<O>> wpMethodOracle(int hypothesisSize) {
        int extraStates = Math.max(1, targetSize - hypothesisSize);
        return new MooreWpMethodEQOracle<>(mqOracle, extraStates);
    }

    private CompactMoore<I, O> runSession() {
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

            CompactMoore<I, O> lastVerified;
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
                    } else if (Protocol.isMqMoore(line)) {
                        mqCount++;
                        Word<I> word = decodeWord(Protocol.wordOf(line));
                        O last = mqOracle.answerQuery(word).lastSymbol();
                        conn.sendLine(Integer.toString(outputIndex.get(last)));
                    } else if (Protocol.isEqMoore(line)) {
                        eqCount++;
                        CompactMoore<I, O> candidate = toCompactMoore(Protocol.parseMooreHypothesis(line));
                        long eqStart = System.nanoTime();
                        Word<I> counterexample = findCounterExample(candidate);
                        double eqSeconds = (System.nanoTime() - eqStart) / 1e9;
                        double elapsed = (System.nanoTime() - sessionStart) / 1e9;
                        System.err.printf(
                                "  [%s] eq #%-4d hyp_states=%-4d mq_so_far=%-8d eq_check=%.3fs elapsed=%.1fs"
                                        + " counterexample=%b%n",
                                algo, eqCount, candidate.size(), mqCount, eqSeconds, elapsed, counterexample != null);
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

    private Word<I> findCounterExample(CompactMoore<I, O> candidate) {
        for (DefaultQuery<I, Word<O>> ce : externalCounterexamples) {
            if (!Objects.equals(candidate.computeOutput(ce.getInput()), ce.getOutput())) {
                return trimToFirstDivergence(candidate, ce.getInput());
            }
        }
        DefaultQuery<I, Word<O>> found = wpMethodOracle(candidate.size()).findCounterExample(candidate, alphabet);
        return found == null ? null : trimToFirstDivergence(candidate, found.getInput());
    }

    /** Same reasoning as {@code MealyOCamlLearningAlgorithm.trimToFirstDivergence}: {@link
     * MooreWpMethodEQOracle} can return a word whose full per-prefix output sequences differ
     * somewhere in the middle but happen to coincide again by the end, which is not a
     * counterexample a Moore learner can act on (it wants the shortest word ending in a state
     * whose output is actually wrong). Trim to the first point of real divergence. */
    private Word<I> trimToFirstDivergence(CompactMoore<I, O> candidate, Word<I> word) {
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

    private CompactMoore<I, O> toCompactMoore(Protocol.MooreHypothesis hyp) {
        int numStates = hyp.stateOutputs().size();
        Map<Integer, O> outputById = new HashMap<>();
        for (Protocol.StateOutputEntry e : hyp.stateOutputs()) {
            outputById.put(e.id(), outputByIndex.get(Integer.parseInt(e.output())));
        }
        CompactMoore<I, O> moore = new CompactMoore<>(alphabet);
        for (int i = 0; i < numStates; i++) {
            moore.addState(outputById.get(i));
        }
        moore.setInitial(hyp.initialState(), true);
        for (Protocol.TransitionEntry t : hyp.transitions()) {
            moore.addTransition(t.from(), alphabet.getSymbol(Integer.parseInt(t.input())), t.to(), null);
        }
        return moore;
    }
}
