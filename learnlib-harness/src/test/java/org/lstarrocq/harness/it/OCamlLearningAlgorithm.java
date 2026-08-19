package org.lstarrocq.harness.it;

import de.learnlib.algorithm.LearningAlgorithm;
import de.learnlib.oracle.EquivalenceOracle;
import de.learnlib.oracle.MembershipOracle;
import de.learnlib.oracle.equivalence.DFAWpMethodEQOracle;
import de.learnlib.query.DefaultQuery;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import net.automatalib.alphabet.Alphabet;
import net.automatalib.automaton.fsa.DFA;
import net.automatalib.automaton.fsa.impl.CompactDFA;
import net.automatalib.word.Word;
import net.automatalib.word.WordBuilder;
import org.lstarrocq.harness.Connection;
import org.lstarrocq.harness.Protocol;

/**
 * Adapts lstar-rocq's extracted OCaml learner (examples/socket_learn.ml) to LearnLib's
 * {@link LearningAlgorithm} interface, so LearnLib's own {@code AbstractDFALearnerIT} test
 * cases -- built for its own learners -- can drive our extracted algorithm directly.
 *
 * <p>LearnLib's IT framework only ever hands a learner a black-box {@link MembershipOracle}
 * (see {@code AbstractDFALearnerIT.addLearnerVariants}); the equivalence oracle it uses to
 * drive the test loop (an exact, white-box {@code SimulatorEQOracle}) is never exposed. Our
 * extracted L*, KV, TTT need some equivalence check to know when to stop, since they run as
 * one self-contained recursive computation rather than pausing between hypotheses. We give
 * them one built from nothing but the membership oracle: a {@link DFAWpMethodEQOracle} sized
 * with {@code targetSize}, which is an <em>exact</em> black-box conformance check as long as
 * the true target has at most {@code targetSize} states -- true by construction here, since
 * {@code targetSize} is the reference automaton's own size.
 *
 * <p>Each call to {@link #startLearning()} or {@link #refineHypothesis} spawns a fresh
 * {@code socket_learn.exe} subprocess and runs one full learning session against a small
 * local bridge server: membership queries are forwarded to the given oracle, equivalence
 * queries are answered by the Wp-method oracle above (checking any externally-supplied
 * counterexamples first). This is a full restart rather than true incremental resumption --
 * the extracted algorithms don't expose a pause point mid-computation -- but since the
 * internal equivalence check is exact, {@code startLearning()} alone converges to the correct
 * hypothesis in practice and {@code refineHypothesis} is mainly exercised by the IT
 * framework's final "no spurious refinement" sanity check.
 */
final class OCamlLearningAlgorithm<I> implements LearningAlgorithm.DFALearner<I> {

    private static final int ACCEPT_TIMEOUT_SECONDS = 30;
    private static final int EXIT_TIMEOUT_SECONDS = 30;

    private final String algo;
    private final Path binary;
    private final Alphabet<I> alphabet;
    private final int targetSize;
    private final MembershipOracle.DFAMembershipOracle<I> mqOracle;
    private final List<DefaultQuery<I, Boolean>> externalCounterexamples = new ArrayList<>();

    private CompactDFA<I> hypothesis;

    OCamlLearningAlgorithm(
            String algo,
            Path binary,
            Alphabet<I> alphabet,
            int targetSize,
            MembershipOracle.DFAMembershipOracle<I> mqOracle) {
        this.algo = algo;
        this.binary = binary;
        this.alphabet = alphabet;
        this.targetSize = targetSize;
        this.mqOracle = mqOracle;
    }

    /** A fresh Wp-method oracle per equivalence check, with the "extra states" lookahead
     * shrunk to what's still needed given the current hypothesis size ({@code targetSize} is
     * an exact upper bound on the true target's size, so {@code targetSize - hypothesisSize}
     * is still a sound bound, and a much smaller one once the hypothesis has grown). Keeps
     * the exactness guarantee while keeping the test suite from staying maximally sized
     * throughout the whole run. */
    private EquivalenceOracle<DFA<?, I>, I, Boolean> wpMethodOracle(int hypothesisSize) {
        int extraStates = Math.max(1, targetSize - hypothesisSize);
        return new DFAWpMethodEQOracle<>(mqOracle, extraStates);
    }

    @Override
    public void startLearning() {
        hypothesis = runSession();
    }

    @Override
    public boolean refineHypothesis(DefaultQuery<I, Boolean> ceQuery) {
        if (Objects.equals(hypothesis.computeOutput(ceQuery.getInput()), ceQuery.getOutput())) {
            return false;
        }
        externalCounterexamples.add(ceQuery);
        hypothesis = runSession();
        return true;
    }

    @Override
    public DFA<?, I> getHypothesisModel() {
        return hypothesis;
    }

    /** Runs one full OCaml learning session end to end: spawn the subprocess, act as its
     * teacher for the whole session, and return whatever hypothesis it last verified before
     * acking. */
    private CompactDFA<I> runSession() {
        Process process = null;
        try (ServerSocket server = new ServerSocket(0)) {
            server.setSoTimeout((int) TimeUnit.SECONDS.toMillis(ACCEPT_TIMEOUT_SECONDS));
            int port = server.getLocalPort();

            ProcessBuilder pb = new ProcessBuilder(binary.toString(), algo, Integer.toString(port));
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.PIPE);
            process = pb.start();
            Process spawned = process;
            // Drain the child's stdout in the background so it can't block on a full pipe
            // buffer; we don't need its content unless something goes wrong.
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
                        algo + ": socket_learn.exe never connected within "
                                + ACCEPT_TIMEOUT_SECONDS + "s");
            }

            CompactDFA<I> lastVerified;
            try (Connection conn = Connection.of(client)) {
                List<String> wireAlphabet =
                        IntStream.range(0, alphabet.size()).mapToObj(String::valueOf).collect(Collectors.toList());
                conn.sendLine(Protocol.configLine(wireAlphabet, "it_" + algo));

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
                    } else if (Protocol.isMembershipQuery(line)) {
                        mqCount++;
                        Word<I> word = decodeWord(Protocol.wordOf(line));
                        boolean accept = Boolean.TRUE.equals(mqOracle.answerQuery(word));
                        conn.sendLine(Boolean.toString(accept));
                    } else if (Protocol.isEq(line)) {
                        eqCount++;
                        CompactDFA<I> candidate = toCompactDFA(Protocol.parseHypothesis(line));
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

    /** Externally-supplied counterexamples (from {@link #refineHypothesis}) are checked
     * first -- they're known-good, no oracle calls needed -- before falling back to the
     * Wp-method oracle's own systematic search. */
    private Word<I> findCounterExample(CompactDFA<I> candidate) {
        for (DefaultQuery<I, Boolean> ce : externalCounterexamples) {
            if (!Objects.equals(candidate.computeOutput(ce.getInput()), ce.getOutput())) {
                return ce.getInput();
            }
        }
        DefaultQuery<I, Boolean> found = wpMethodOracle(candidate.size()).findCounterExample(candidate, alphabet);
        return found == null ? null : found.getInput();
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

    /** Mirrors org.lstarrocq.harness.TeacherServer#toCompactDFA, generalized to an arbitrary
     * input symbol type: wire-format symbols are always the decimal indices this class sent
     * in the config handshake, mapped back through {@code alphabet}. */
    private CompactDFA<I> toCompactDFA(Protocol.Hypothesis hyp) {
        CompactDFA<I> dfa = new CompactDFA<>(alphabet, hyp.numStates());
        for (int i = 0; i < hyp.numStates(); i++) {
            dfa.addState(hyp.acceptingIds().contains(i));
        }
        dfa.setInitial(hyp.initialState(), true);
        for (Protocol.TransitionEntry t : hyp.transitions()) {
            dfa.addTransition(t.from(), alphabet.getSymbol(Integer.parseInt(t.input())), t.to(), null);
        }
        return dfa;
    }
}
