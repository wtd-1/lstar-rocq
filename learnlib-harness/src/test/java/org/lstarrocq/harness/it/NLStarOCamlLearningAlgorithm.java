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
import net.automatalib.automaton.fsa.impl.CompactNFA;
import net.automatalib.util.automaton.fsa.NFAs;
import net.automatalib.word.Word;
import net.automatalib.word.WordBuilder;
import org.lstarrocq.harness.Connection;
import org.lstarrocq.harness.Protocol;

/**
 * The NL* analogue of {@link OCamlLearningAlgorithm}: bridges lstar-rocq's extracted NL*
 * (examples/socket_learn.ml's {@code nlstar} mode) to LearnLib's {@link LearningAlgorithm}
 * interface, the same way LearnLib bridges its own {@code NLStarLearner} in via
 * {@code NLStarLearner<>(alphabet, mqOracle).asDFALearner()} (see {@code NLStarIT.java}
 * upstream) -- {@code AbstractDFALearnerIT} only ever drives {@code LearningAlgorithm.DFALearner},
 * so a learner that internally produces a nondeterministic hypothesis still needs to present
 * a deterministic one at the boundary.
 *
 * <p>The one real difference from {@link OCamlLearningAlgorithm}: NL*'s hypothesis is an NFA
 * (in fact an RFSA -- a residual finite state automaton, a specific canonical NFA subtype),
 * not a DFA. lstar-rocq's socket bridge already reflects this at the wire level -- {@code
 * "eq_nfa"} requests carry a genuine from/input -&gt; {to} relation and a list of initial
 * states, not a single-target function and a single initial state -- so parsing needs a
 * different hypothesis record, and reporting it back through LearnLib requires an actual
 * determinization step ({@link NFAs#determinize}), not just a type change: a {@code DFA<?,I>}
 * has one deterministic current state, so there is no way to "just present" a nondeterministic
 * automaton as one without materializing its subset construction. The internal equivalence
 * check ({@link DFAWpMethodEQOracle}) runs against that determinized DFA -- language
 * equivalence is what matters, and the determinized automaton's language is exactly the RFSA's.
 */
final class NLStarOCamlLearningAlgorithm<I> implements LearningAlgorithm.DFALearner<I> {

    private static final int ACCEPT_TIMEOUT_SECONDS = 30;
    private static final int EXIT_TIMEOUT_SECONDS = 30;

    private final Path binary;
    private final Alphabet<I> alphabet;
    private final int targetSize;
    private final MembershipOracle.DFAMembershipOracle<I> mqOracle;
    private final List<DefaultQuery<I, Boolean>> externalCounterexamples = new ArrayList<>();

    private CompactDFA<I> hypothesis;

    NLStarOCamlLearningAlgorithm(
            Path binary, Alphabet<I> alphabet, int targetSize, MembershipOracle.DFAMembershipOracle<I> mqOracle) {
        this.binary = binary;
        this.alphabet = alphabet;
        this.targetSize = targetSize;
        this.mqOracle = mqOracle;
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

    private EquivalenceOracle<DFA<?, I>, I, Boolean> wpMethodOracle(int hypothesisSize) {
        int extraStates = Math.max(1, targetSize - hypothesisSize);
        return new DFAWpMethodEQOracle<>(mqOracle, extraStates);
    }

    private CompactDFA<I> runSession() {
        Process process = null;
        try (ServerSocket server = new ServerSocket(0)) {
            server.setSoTimeout((int) TimeUnit.SECONDS.toMillis(ACCEPT_TIMEOUT_SECONDS));
            int port = server.getLocalPort();

            ProcessBuilder pb = new ProcessBuilder(binary.toString(), "nlstar", Integer.toString(port));
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
                        "nlstar: socket_learn.exe never connected within " + ACCEPT_TIMEOUT_SECONDS + "s");
            }

            CompactDFA<I> lastVerified;
            try (Connection conn = Connection.of(client)) {
                List<String> wireAlphabet =
                        IntStream.range(0, alphabet.size()).mapToObj(String::valueOf).collect(Collectors.toList());
                conn.sendLine(Protocol.configLine(wireAlphabet, "it_nlstar"));

                lastVerified = null;
                while (true) {
                    String line = conn.readNonBlankLine();
                    if (line == null) {
                        throw new IllegalStateException("nlstar: connection closed mid-session");
                    }
                    if (Protocol.isAck(line)) {
                        conn.sendLine(Protocol.DONE_LINE);
                        break;
                    } else if (Protocol.isMembershipQuery(line)) {
                        Word<I> word = decodeWord(Protocol.wordOf(line));
                        boolean accept = Boolean.TRUE.equals(mqOracle.answerQuery(word));
                        conn.sendLine(Boolean.toString(accept));
                    } else if (Protocol.isEqNfa(line)) {
                        CompactDFA<I> candidate = determinize(Protocol.parseNFAHypothesis(line));
                        Word<I> counterexample = findCounterExample(candidate);
                        if (counterexample == null) {
                            lastVerified = candidate;
                            conn.sendLine("NONE");
                        } else {
                            conn.sendLine(encodeWord(counterexample));
                        }
                    } else {
                        throw new IllegalStateException("nlstar: unexpected line: " + line);
                    }
                }
            }
            if (lastVerified == null) {
                throw new IllegalStateException("nlstar: learner acked without a verified hypothesis");
            }
            if (!process.waitFor(EXIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("nlstar: socket_learn.exe did not exit after ack");
            }
            return lastVerified;
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("nlstar: bridging to OCaml learner failed", e);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

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

    /** Builds a {@link CompactNFA} from the wire's from/input -&gt; {to} relation (multiple
     * {@code addTransition} calls for the same (state, symbol) pair accumulate, they don't
     * overwrite -- unlike {@link CompactDFA}, this is exactly what {@link CompactNFA} is for),
     * then determinizes it into the {@link CompactDFA} {@link #getHypothesisModel()} needs. */
    private CompactDFA<I> determinize(Protocol.NFAHypothesis hyp) {
        CompactNFA<I> nfa = new CompactNFA<>(alphabet, hyp.numStates());
        for (int i = 0; i < hyp.numStates(); i++) {
            nfa.addState(hyp.acceptingIds().contains(i));
        }
        for (int initialState : hyp.initialStates()) {
            nfa.setInitial(initialState, true);
        }
        for (Protocol.TransitionEntry t : hyp.transitions()) {
            nfa.addTransition(t.from(), alphabet.getSymbol(Integer.parseInt(t.input())), t.to(), null);
        }
        return NFAs.determinize(nfa, alphabet);
    }
}
