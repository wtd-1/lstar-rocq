package org.lstarrocq.harness;

import de.learnlib.algorithm.LearningAlgorithm;
import de.learnlib.oracle.EquivalenceOracle;
import de.learnlib.oracle.MembershipOracle;
import de.learnlib.query.DefaultQuery;
import de.learnlib.query.Query;
import de.learnlib.util.Experiment;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import net.automatalib.alphabet.Alphabet;
import net.automatalib.automaton.fsa.DFA;
import net.automatalib.automaton.fsa.impl.CompactDFA;
import net.automatalib.util.automaton.Automata;
import net.automatalib.word.Word;

/**
 * Direction A of the LearnLib interop harness: LearnLib/AutomataLib supplies
 * the ground-truth target ({@link Corpus}) and an <em>exact</em> equivalence
 * check ({@code Automata.findSeparatingWord}); our extracted L*, KV, TTT
 * learner (lstar-rocq's examples/socket_learn.ml) is the socket client.
 *
 * <p>Because the client's learning algorithm may issue a few more membership
 * queries right after an equivalence query comes back with no
 * counterexample (see {@link Protocol}'s doc comment on {@code ack}), this
 * server keeps answering a target's queries until the client sends
 * {@code ack}, rather than advancing the moment it replies {@code "NONE"}.
 *
 * <p>When a {@code compareAlgo} is given, this also runs LearnLib's own
 * learner for that same algorithm fully locally (no socket involved) against
 * each target right after our extracted learner finishes it, and checks
 * whether the two hypotheses agree -- i.e. whether lstar-rocq's extracted
 * implementation and LearnLib's own actually converge to the same automaton,
 * not just that each independently matches the target.
 */
public final class TeacherServer {

    private TeacherServer() {}

    public static void run(int port, List<Corpus.Target> targets, String compareAlgo) throws IOException {
        try (ServerSocket server = new ServerSocket(port)) {
            System.out.printf(
                    "TeacherServer listening on port %d, serving %d target(s)%n", port, targets.size());
            Socket client = server.accept();
            try (Connection conn = Connection.of(client)) {
                for (Corpus.Target target : targets) {
                    runTarget(conn, target, compareAlgo);
                }
                conn.sendLine(Protocol.DONE_LINE);
            }
        }
    }

    private static void runTarget(Connection conn, Corpus.Target target, String compareAlgo) throws IOException {
        List<String> alphabetSymbols = target.alphabet().stream().collect(Collectors.toList());
        conn.sendLine(Protocol.configLine(alphabetSymbols, target.name()));

        long start = System.nanoTime();
        int queries = 0;
        CompactDFA<String> lastVerifiedHypothesis = null;
        while (true) {
            String line = conn.readNonBlankLine();
            if (line == null) {
                throw new IOException("connection closed mid-target: " + target.name());
            }
            if (Protocol.isAck(line)) {
                double seconds = (System.nanoTime() - start) / 1e9;
                System.out.printf(
                        "target=%-20s states=%-5d queries=%-6d time=%.3fs%n",
                        target.name(), target.dfa().size(), queries, seconds);
                if (compareAlgo != null) {
                    if (lastVerifiedHypothesis == null) {
                        throw new IllegalStateException(
                                "ack received for " + target.name() + " with no verified hypothesis to compare");
                    }
                    compareWithLearnLib(target, compareAlgo, lastVerifiedHypothesis);
                }
                return;
            } else if (Protocol.isMembershipQuery(line)) {
                queries++;
                List<String> word = Protocol.wordOf(line);
                boolean accept = Boolean.TRUE.equals(target.dfa().computeOutput(word));
                conn.sendLine(Boolean.toString(accept));
            } else if (Protocol.isEq(line)) {
                queries++;
                Protocol.Hypothesis hyp = Protocol.parseHypothesis(line);
                CompactDFA<String> hypothesisDfa = toCompactDFA(hyp, target.alphabet());
                Word<String> counterexample =
                        Automata.findSeparatingWord(target.dfa(), hypothesisDfa, target.alphabet());
                if (counterexample == null) {
                    lastVerifiedHypothesis = hypothesisDfa;
                    conn.sendLine("NONE");
                } else {
                    conn.sendLine(String.join(",", counterexample.asList()));
                }
            } else {
                throw new IOException("unexpected line for target " + target.name() + ": " + line);
            }
        }
    }

    /** Runs LearnLib's own learner for {@code algo} against {@code target} with no socket
     * involved (a direct in-process {@link MembershipOracle} and an exact
     * {@link EquivalenceOracle} both backed by {@code target.dfa()} directly), then checks
     * whether its hypothesis is language-equivalent to {@code ourHypothesis} -- the one our
     * extracted learner just converged to for the same target. */
    private static void compareWithLearnLib(Corpus.Target target, String algo, DFA<?, String> ourHypothesis) {
        CountingMembershipOracle oracle = new CountingMembershipOracle(target.dfa());
        LearningAlgorithm<DFA<?, String>, String, Boolean> learner =
                Learners.build(algo, target.alphabet(), oracle);
        EquivalenceOracle<DFA<?, String>, String, Boolean> exactEquivalence =
                (hypothesis, alphabetCol) -> {
                    Word<String> sep = Automata.findSeparatingWord(target.dfa(), hypothesis, alphabetCol);
                    if (sep == null) {
                        return null;
                    }
                    return new DefaultQuery<>(sep, Boolean.TRUE.equals(target.dfa().computeOutput(sep)));
                };

        long start = System.nanoTime();
        Experiment<DFA<?, String>> experiment = new Experiment<>(learner, exactEquivalence, target.alphabet());
        experiment.setLogModels(false);
        DFA<?, String> learnlibHypothesis = experiment.run();
        double seconds = (System.nanoTime() - start) / 1e9;

        boolean agree = Automata.findSeparatingWord(ourHypothesis, learnlibHypothesis, target.alphabet()) == null;
        System.out.printf(
                "agree  target=%-20s algo=%-5s ours_states=%-5d learnlib_states=%-5d learnlib_queries=%-7d"
                        + " learnlib_time=%.3fs agree=%b%n",
                target.name(),
                algo,
                ourHypothesis.size(),
                learnlibHypothesis.size(),
                oracle.queries,
                seconds,
                agree);
    }

    /** Counts queries the same way {@link #runTarget} does for our extracted learner, so the
     * two query counts in the log are directly comparable. */
    private static final class CountingMembershipOracle implements MembershipOracle<String, Boolean> {
        private final DFA<?, String> target;
        private long queries = 0;

        CountingMembershipOracle(DFA<?, String> target) {
            this.target = target;
        }

        @Override
        public void processQueries(Collection<? extends Query<String, Boolean>> queries) {
            for (Query<String, Boolean> query : queries) {
                this.queries++;
                query.answer(Boolean.TRUE.equals(target.computeOutput(query.getInput())));
            }
        }
    }

    /** Rebuilds the client's hypothesis as a {@link CompactDFA}, so it can be compared against
     * the target with {@code Automata.findSeparatingWord}. State ids in the wire format are
     * already 0..n-1 (see lib/SocketTeacher.ml's serializer), so adding states in id order
     * makes CompactDFA's own (equally sequential) internal ids line up directly. */
    private static CompactDFA<String> toCompactDFA(Protocol.Hypothesis hyp, Alphabet<String> alphabet) {
        CompactDFA<String> dfa = new CompactDFA<>(alphabet, hyp.numStates());
        for (int i = 0; i < hyp.numStates(); i++) {
            dfa.addState(hyp.acceptingIds().contains(i));
        }
        dfa.setInitial(hyp.initialState(), true);
        for (Protocol.TransitionEntry t : hyp.transitions()) {
            dfa.addTransition(t.from(), t.input(), t.to(), null);
        }
        return dfa;
    }

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8888;
        String corpus = args.length > 1 ? args[1] : "full";
        String compareAlgo = args.length > 2 ? args[2] : null;
        List<Corpus.Target> targets =
                switch (corpus) {
                    case "small" -> Corpus.small();
                    case "builtin" -> Corpus.builtinExamples();
                    default -> Corpus.all();
                };
        run(port, targets, compareAlgo);
    }
}
