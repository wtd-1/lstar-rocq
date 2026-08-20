package org.lstarrocq.harness;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Wire format shared with the OCaml side of the bridge (lib/SocketProtocol.ml
 * on the lstar-rocq repo). One JSON object per line:
 *
 * <ul>
 *   <li>Teacher -&gt; Learner, sent once per target before any query for it:
 *       {@code {"type":"config","alphabet":["0","1"],"target":"name"}}
 *   <li>Teacher -&gt; Learner, sent once every target has been exhausted:
 *       {@code {"type":"done"}}
 *   <li>Learner -&gt; Teacher, membership query: {@code {"type":"mq","word":"0,1,0"}};
 *       reply is the literal string {@code "true"} or {@code "false"}
 *   <li>Learner -&gt; Teacher, equivalence query (serialized hypothesis DFA):
 *       {@code {"type":"eq","initial_state":N,"states":[{"id":I,"accept":B},...],
 *       "transitions":[{"from":F,"input":"S","to":T},...]}}; reply is the
 *       literal string {@code "NONE"} or a comma-separated counterexample word
 *   <li>Learner -&gt; Teacher, sent once the learner is done with a target:
 *       {@code {"type":"ack"}}
 * </ul>
 *
 * <p>The {@code ack} message exists because some learners (this project's KV
 * and TTT) rebuild their final hypothesis right after an equivalence query
 * comes back empty, and that rebuild can itself issue a few more membership
 * queries. A teacher must not advance to the next target the instant it
 * replies {@code NONE} -- it has to keep answering that target's queries
 * until it sees {@code ack}.
 *
 * <p>This is a small, fixed grammar that both ends control, so it's parsed
 * with the same lightweight regexes as the OCaml side rather than pulling in
 * a JSON library.
 */
public final class Protocol {

    private Protocol() {}

    public static final String DONE_LINE = "{\"type\":\"done\"}";
    public static final String ACK_LINE = "{\"type\":\"ack\"}";

    // ---- requests/replies this side sends -------------------------------

    public static String mqRequest(List<String> word) {
        return "{\"type\":\"mq\",\"word\":\"" + String.join(",", word) + "\"}";
    }

    public static String configLine(List<String> alphabet, String target) {
        return "{\"type\":\"config\",\"alphabet\":[" + jsonStringArray(alphabet) + "],\"target\":\""
                + escape(target) + "\"}";
    }

    /** The Mealy/Moore handshake: same as the two-arg {@code config} line, plus an
     * {@code "output_alphabet"} field -- a Mealy/Moore hypothesis carries an output alphabet
     * that {@code AbstractMealyLearnerIT}/{@code AbstractMooreLearnerIT} never hand over (see
     * {@link org.lstarrocq.harness.it.OutputAlphabetDiscovery}), unlike DFA/NFA's fixed
     * two-value {@code Boolean}. */
    public static String configLine(List<String> alphabet, List<String> outputAlphabet, String target) {
        return "{\"type\":\"config\",\"alphabet\":[" + jsonStringArray(alphabet) + "],\"output_alphabet\":["
                + jsonStringArray(outputAlphabet) + "],\"target\":\"" + escape(target) + "\"}";
    }

    private static String jsonStringArray(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append('"').append(escape(items.get(i))).append('"');
        }
        return sb.toString();
    }

    /**
     * Serializes a hypothesis DFA into an {@code "eq"} request. Mirrors
     * lib/SocketTeacher.ml's {@code serialize_dfa_to_json}: states are
     * renumbered 0..n-1 (order doesn't matter, since the other end
     * reconstructs purely from the ids), and every (state, symbol) pair in
     * {@code alphabetOrder} gets a transition entry.
     */
    public static <S, I> String eqRequest(
            List<S> states,
            S initialState,
            java.util.function.Predicate<S> isAccepting,
            java.util.function.BiFunction<S, I, S> transition,
            List<I> alphabetOrder) {
        java.util.Map<S, Integer> ids = new java.util.IdentityHashMap<>();
        for (int i = 0; i < states.size(); i++) {
            ids.put(states.get(i), i);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"eq\",\"initial_state\":")
                .append(ids.get(initialState))
                .append(",\"states\":[");
        for (int i = 0; i < states.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append("{\"id\":")
                    .append(i)
                    .append(",\"accept\":")
                    .append(isAccepting.test(states.get(i)))
                    .append("}");
        }
        sb.append("],\"transitions\":[");
        boolean first = true;
        for (S s : states) {
            int from = ids.get(s);
            for (I sym : alphabetOrder) {
                S dst = transition.apply(s, sym);
                if (dst == null) {
                    continue;
                }
                if (!first) {
                    sb.append(",");
                }
                first = false;
                sb.append("{\"from\":")
                        .append(from)
                        .append(",\"input\":\"")
                        .append(escape(sym.toString()))
                        .append("\",\"to\":")
                        .append(ids.get(dst))
                        .append("}");
            }
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String escape(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                default:
                    out.append(c);
            }
        }
        return out.toString();
    }

    // ---- parsing what the other side sent --------------------------------

    public static Optional<String> type(String json) {
        return extractStringField("type", json);
    }

    public static boolean isMembershipQuery(String json) {
        return type(json).map("mq"::equals).orElse(false);
    }

    public static boolean isDone(String json) {
        return type(json).map("done"::equals).orElse(false);
    }

    public static boolean isAck(String json) {
        return type(json).map("ack"::equals).orElse(false);
    }

    public static boolean isConfig(String json) {
        return type(json).map("config"::equals).orElse(false);
    }

    public static boolean isEq(String json) {
        return type(json).map("eq"::equals).orElse(false);
    }

    public static boolean isMqMealy(String json) {
        return type(json).map("mq_mealy"::equals).orElse(false);
    }

    public static boolean isEqMealy(String json) {
        return type(json).map("eq_mealy"::equals).orElse(false);
    }

    public static boolean isMqMoore(String json) {
        return type(json).map("mq_moore"::equals).orElse(false);
    }

    public static boolean isEqMoore(String json) {
        return type(json).map("eq_moore"::equals).orElse(false);
    }

    public static boolean isEqNfa(String json) {
        return type(json).map("eq_nfa"::equals).orElse(false);
    }

    /** Raw comma-separated symbol tokens carried by an {@code "mq"} request. */
    public static List<String> wordOf(String json) {
        Optional<String> raw = extractStringField("word", json);
        if (raw.isEmpty() || raw.get().isEmpty()) {
            return List.of();
        }
        return List.of(raw.get().split(","));
    }

    public record Config(List<String> alphabet, String target) {}

    public static Optional<Config> parseConfig(String json) {
        if (!isConfig(json)) {
            return Optional.empty();
        }
        List<String> alphabet = extractStringArrayField("alphabet", json);
        String target = extractStringField("target", json).orElse("");
        return Optional.of(new Config(alphabet, target));
    }

    public record TransitionEntry(int from, String input, int to) {}

    public record Hypothesis(
            int initialState, List<Integer> acceptingIds, List<TransitionEntry> transitions, int numStates) {}

    /** Parses an {@code "eq"} request's hypothesis DFA, as sent by
     * lib/SocketTeacher.ml's {@code serialize_dfa_to_json}. */
    public static Hypothesis parseHypothesis(String json) {
        int initial = extractIntField("initial_state", json);
        List<Integer> ids = new ArrayList<>();
        Matcher stateMatcher =
                Pattern.compile("\\{\"id\":\\s*(\\d+),\\s*\"accept\":\\s*true\\}").matcher(json);
        while (stateMatcher.find()) {
            ids.add(Integer.parseInt(stateMatcher.group(1)));
        }
        Matcher idMatcher = Pattern.compile("\\{\"id\":\\s*(\\d+),\\s*\"accept\":\\s*(true|false)\\}").matcher(json);
        int maxId = -1;
        while (idMatcher.find()) {
            maxId = Math.max(maxId, Integer.parseInt(idMatcher.group(1)));
        }
        List<TransitionEntry> transitions = new ArrayList<>();
        Matcher transMatcher =
                Pattern.compile(
                                "\\{\"from\":\\s*(\\d+),\\s*\"input\":\\s*\"([^\"]*)\",\\s*\"to\":\\s*(\\d+)\\}")
                        .matcher(json);
        while (transMatcher.find()) {
            transitions.add(
                    new TransitionEntry(
                            Integer.parseInt(transMatcher.group(1)),
                            transMatcher.group(2),
                            Integer.parseInt(transMatcher.group(3))));
        }
        return new Hypothesis(initial, ids, transitions, maxId + 1);
    }

    public record MealyTransitionEntry(int from, String input, int to, String output) {}

    public record MealyHypothesis(int initialState, List<Integer> stateIds, List<MealyTransitionEntry> transitions) {}

    /** Parses an {@code "eq_mealy"} request's hypothesis, as sent by
     * lib/SocketTeacher.ml's {@code MakeProtocolMealyTeacher.serialize_mealy_to_json}: states
     * carry no attribute of their own (Mealy output lives on transitions), so {@code "states"}
     * entries are bare {@code {"id":I}}. */
    public static MealyHypothesis parseMealyHypothesis(String json) {
        int initial = extractIntField("initial_state", json);
        List<Integer> stateIds = new ArrayList<>();
        Matcher stateMatcher = Pattern.compile("\\{\"id\":\\s*(\\d+)\\}").matcher(json);
        while (stateMatcher.find()) {
            stateIds.add(Integer.parseInt(stateMatcher.group(1)));
        }
        List<MealyTransitionEntry> transitions = new ArrayList<>();
        Matcher transMatcher =
                Pattern.compile(
                                "\\{\"from\":\\s*(\\d+),\\s*\"input\":\\s*\"([^\"]*)\",\\s*\"to\":\\s*(\\d+),\\s*"
                                        + "\"output\":\\s*\"([^\"]*)\"\\}")
                        .matcher(json);
        while (transMatcher.find()) {
            transitions.add(
                    new MealyTransitionEntry(
                            Integer.parseInt(transMatcher.group(1)),
                            transMatcher.group(2),
                            Integer.parseInt(transMatcher.group(3)),
                            transMatcher.group(4)));
        }
        return new MealyHypothesis(initial, stateIds, transitions);
    }

    public record StateOutputEntry(int id, String output) {}

    public record MooreHypothesis(
            int initialState, List<StateOutputEntry> stateOutputs, List<TransitionEntry> transitions) {}

    /** Parses an {@code "eq_moore"} request's hypothesis, as sent by
     * lib/SocketTeacher.ml's {@code MakeProtocolMooreTeacher.serialize_moore_to_json}: output
     * lives on states ({@code "state_outputs"}), not transitions, unlike Mealy. */
    public static MooreHypothesis parseMooreHypothesis(String json) {
        int initial = extractIntField("initial_state", json);
        List<StateOutputEntry> stateOutputs = new ArrayList<>();
        Matcher outputMatcher =
                Pattern.compile("\\{\"id\":\\s*(\\d+),\\s*\"output\":\\s*\"([^\"]*)\"\\}").matcher(json);
        while (outputMatcher.find()) {
            stateOutputs.add(new StateOutputEntry(Integer.parseInt(outputMatcher.group(1)), outputMatcher.group(2)));
        }
        List<TransitionEntry> transitions = new ArrayList<>();
        Matcher transMatcher =
                Pattern.compile(
                                "\\{\"from\":\\s*(\\d+),\\s*\"input\":\\s*\"([^\"]*)\",\\s*\"to\":\\s*(\\d+)\\}")
                        .matcher(json);
        while (transMatcher.find()) {
            transitions.add(
                    new TransitionEntry(
                            Integer.parseInt(transMatcher.group(1)),
                            transMatcher.group(2),
                            Integer.parseInt(transMatcher.group(3))));
        }
        return new MooreHypothesis(initial, stateOutputs, transitions);
    }

    public record NFAHypothesis(
            int numStates, List<Integer> acceptingIds, List<Integer> initialStates, List<TransitionEntry> transitions) {}

    /** Parses an {@code "eq_nfa"} request's hypothesis, as sent by lib/SocketTeacher.ml's
     * {@code MakeProtocolNFATeacher.serialize_nfa_to_json}: a set of initial states
     * ({@code "initial_states"}) rather than one, and repeated {@code "transitions"} entries
     * for the same (state, symbol) pair accumulate into a genuine relation rather than
     * overwriting, since NL*'s hypothesis is an RFSA, not a DFA. */
    public static NFAHypothesis parseNFAHypothesis(String json) {
        List<Integer> initialStates = extractIntArrayField("initial_states", json);
        List<Integer> acceptingIds = new ArrayList<>();
        Matcher stateMatcher =
                Pattern.compile("\\{\"id\":\\s*(\\d+),\\s*\"accept\":\\s*true\\}").matcher(json);
        while (stateMatcher.find()) {
            acceptingIds.add(Integer.parseInt(stateMatcher.group(1)));
        }
        Matcher idMatcher = Pattern.compile("\\{\"id\":\\s*(\\d+),\\s*\"accept\":\\s*(true|false)\\}").matcher(json);
        int maxId = -1;
        while (idMatcher.find()) {
            maxId = Math.max(maxId, Integer.parseInt(idMatcher.group(1)));
        }
        List<TransitionEntry> transitions = new ArrayList<>();
        Matcher transMatcher =
                Pattern.compile(
                                "\\{\"from\":\\s*(\\d+),\\s*\"input\":\\s*\"([^\"]*)\",\\s*\"to\":\\s*(\\d+)\\}")
                        .matcher(json);
        while (transMatcher.find()) {
            transitions.add(
                    new TransitionEntry(
                            Integer.parseInt(transMatcher.group(1)),
                            transMatcher.group(2),
                            Integer.parseInt(transMatcher.group(3))));
        }
        return new NFAHypothesis(maxId + 1, acceptingIds, initialStates, transitions);
    }

    private static List<Integer> extractIntArrayField(String field, String json) {
        Matcher arrayMatcher =
                Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*\\[([^]]*)\\]").matcher(json);
        if (!arrayMatcher.find()) {
            return List.of();
        }
        String inner = arrayMatcher.group(1).trim();
        if (inner.isEmpty()) {
            return List.of();
        }
        List<Integer> items = new ArrayList<>();
        for (String tok : inner.split(",")) {
            items.add(Integer.parseInt(tok.trim()));
        }
        return items;
    }

    private static Pattern fieldStringPattern(String field) {
        return Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*\"([^\"]*)\"");
    }

    private static Optional<String> extractStringField(String field, String json) {
        Matcher m = fieldStringPattern(field).matcher(json);
        return m.find() ? Optional.of(m.group(1)) : Optional.empty();
    }

    private static int extractIntField(String field, String json) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*(\\d+)").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private static List<String> extractStringArrayField(String field, String json) {
        Matcher arrayMatcher =
                Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*\\[([^]]*)\\]").matcher(json);
        if (!arrayMatcher.find()) {
            return List.of();
        }
        String inner = arrayMatcher.group(1);
        List<String> items = new ArrayList<>();
        Matcher itemMatcher = Pattern.compile("\"([^\"]*)\"").matcher(inner);
        while (itemMatcher.find()) {
            items.add(itemMatcher.group(1));
        }
        return items;
    }
}
