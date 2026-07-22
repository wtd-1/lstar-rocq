package org.lstarrocq.harness;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.stream.Collectors;
import net.automatalib.alphabet.Alphabet;
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
 */
public final class TeacherServer {

    private TeacherServer() {}

    public static void run(int port, List<Corpus.Target> targets) throws IOException {
        try (ServerSocket server = new ServerSocket(port)) {
            System.out.printf(
                    "TeacherServer listening on port %d, serving %d target(s)%n", port, targets.size());
            Socket client = server.accept();
            try (Connection conn = Connection.of(client)) {
                for (Corpus.Target target : targets) {
                    runTarget(conn, target);
                }
                conn.sendLine(Protocol.DONE_LINE);
            }
        }
    }

    private static void runTarget(Connection conn, Corpus.Target target) throws IOException {
        List<String> alphabetSymbols = target.alphabet().stream().collect(Collectors.toList());
        conn.sendLine(Protocol.configLine(alphabetSymbols, target.name()));

        long start = System.nanoTime();
        int queries = 0;
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
                    conn.sendLine("NONE");
                } else {
                    conn.sendLine(String.join(",", counterexample.asList()));
                }
            } else {
                throw new IOException("unexpected line for target " + target.name() + ": " + line);
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
        boolean small = args.length > 1 && args[1].equals("small");
        run(port, small ? Corpus.small() : Corpus.all());
    }
}
