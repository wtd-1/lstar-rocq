package org.lstarrocq.harness;

import de.learnlib.algorithm.LearningAlgorithm;
import de.learnlib.oracle.EquivalenceOracle;
import de.learnlib.oracle.MembershipOracle;
import de.learnlib.query.DefaultQuery;
import de.learnlib.query.Query;
import de.learnlib.util.Experiment;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.automatalib.alphabet.Alphabet;
import net.automatalib.alphabet.impl.Alphabets;
import net.automatalib.automaton.fsa.DFA;
import net.automatalib.word.Word;

/**
 * Direction B of the LearnLib interop harness: LearnLib's own
 * {@code ClassicLStarDFA}/{@code KearnsVaziraniDFA}/{@code TTTLearnerDFA} is
 * the socket client, and lstar-rocq's examples/socket_teach.ml is the
 * teacher. This exercises the wire protocol and target definitions with a
 * learner implementation completely independent of the extracted one --
 * Direction A ({@link TeacherServer}) is what actually exercises our
 * extracted learners.
 */
public final class LearnerClient {

    private LearnerClient() {}

    public static void run(String host, int port, String algo) throws IOException {
        try (Connection conn = Connection.connectTo(host, port)) {
            while (true) {
                String line = conn.readNonBlankLine();
                if (line == null) {
                    throw new IOException("connection closed waiting for config/done");
                }
                if (Protocol.isDone(line)) {
                    System.out.println("done");
                    return;
                }
                Protocol.Config cfg =
                        Protocol.parseConfig(line)
                                .orElseThrow(
                                        () -> new IllegalStateException("expected config or done, got: " + line));
                runTarget(conn, cfg, algo);
            }
        }
    }

    private static void runTarget(Connection conn, Protocol.Config cfg, String algo) throws IOException {
        Alphabet<String> alphabet = Alphabets.fromList(cfg.alphabet());
        MembershipOracle<String, Boolean> memOracle = new SocketMembershipOracle(conn);
        EquivalenceOracle<DFA<?, String>, String, Boolean> eqOracle =
                new SocketEquivalenceOracle(conn, memOracle);

        LearningAlgorithm<DFA<?, String>, String, Boolean> learner = Learners.build(algo, alphabet, memOracle);

        long start = System.nanoTime();
        Experiment<DFA<?, String>> experiment = new Experiment<>(learner, eqOracle, alphabet);
        experiment.setLogModels(false);
        DFA<?, String> hypothesis = experiment.run();
        double seconds = (System.nanoTime() - start) / 1e9;
        System.out.printf(
                "target=%-20s algo=%-5s alphabet=%-3d states=%-5d time=%.3fs%n",
                cfg.target(), algo, alphabet.size(), hypothesis.size(), seconds);

        // Some learners (KV, TTT) rebuild their final hypothesis right after the last
        // equivalence query comes back empty, issuing a few more membership queries in the
        // process (see Protocol's doc comment) -- all of that has already happened by the
        // time experiment.run() returned, so it's safe to tell the teacher we're done now.
        conn.sendLine(Protocol.ACK_LINE);
    }

    /** Wraps our {@code "mq"} request/reply around LearnLib's batch membership oracle
     * interface: one socket round-trip per query in the batch, in order. */
    private static final class SocketMembershipOracle implements MembershipOracle<String, Boolean> {
        private final Connection conn;

        SocketMembershipOracle(Connection conn) {
            this.conn = conn;
        }

        @Override
        public void processQueries(Collection<? extends Query<String, Boolean>> queries) {
            for (Query<String, Boolean> query : queries) {
                List<String> word = query.getInput().asList();
                conn.sendLine(Protocol.mqRequest(word));
                String reply;
                try {
                    reply = conn.readNonBlankLine();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                if (reply == null) {
                    throw new IllegalStateException("teacher closed connection during membership query");
                }
                query.answer(Boolean.valueOf(reply));
            }
        }
    }

    /** Wraps our {@code "eq"} request/reply around LearnLib's equivalence oracle interface.
     * The teacher only replies with the counterexample word itself, unlabelled, so this
     * issues one extra membership query to find its correct output, mirroring what this
     * project's own Rocq-extracted algorithms do internally with the word a teacher hands
     * back (see e.g. how RS.v's counterexample analysis treats an equivalence oracle's
     * result). */
    private static final class SocketEquivalenceOracle
            implements EquivalenceOracle<DFA<?, String>, String, Boolean> {
        private final Connection conn;
        private final MembershipOracle<String, Boolean> memOracle;

        SocketEquivalenceOracle(Connection conn, MembershipOracle<String, Boolean> memOracle) {
            this.conn = conn;
            this.memOracle = memOracle;
        }

        @Override
        public DefaultQuery<String, Boolean> findCounterExample(
                DFA<?, String> hypothesis, Collection<? extends String> alphabet) {
            List<String> alphabetOrder = new ArrayList<>(alphabet);
            conn.sendLine(serialize(hypothesis, alphabetOrder));
            String reply;
            try {
                reply = conn.readNonBlankLine();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            if (reply == null) {
                throw new IllegalStateException("teacher closed connection during equivalence query");
            }
            if (reply.equals("NONE")) {
                return null;
            }
            Word<String> word = Word.fromList(List.of(reply.split(",")));
            boolean output = Boolean.TRUE.equals(memOracle.answerQuery(word));
            return new DefaultQuery<>(word, output);
        }

        // Captures the hypothesis's wildcarded state type into a fixed type parameter so
        // Protocol.eqRequest's generic helper (which needs to name the state type) applies.
        private static <S> String serialize(DFA<S, String> hypothesis, List<String> alphabetOrder) {
            List<S> states = new ArrayList<>(hypothesis.getStates());
            return Protocol.eqRequest(
                    states, hypothesis.getInitialState(), hypothesis::isAccepting, hypothesis::getSuccessor,
                    alphabetOrder);
        }
    }

    public static void main(String[] args) throws IOException {
        String host = args.length > 0 ? args[0] : "localhost";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 8888;
        String algo = args.length > 2 ? args[2] : "lstar";
        run(host, port, algo);
    }
}
