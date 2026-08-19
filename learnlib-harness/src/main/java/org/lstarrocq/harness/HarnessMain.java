package org.lstarrocq.harness;

import java.io.IOException;

/**
 * CLI entry point for both directions of the harness.
 *
 * <pre>
 *   java -jar learnlib-harness.jar teacher [port] [small|builtin|full] [lstar|kv|ttt]
 *   java -jar learnlib-harness.jar learner [host] [port] [lstar|kv|ttt]
 * </pre>
 *
 * <p>{@code teacher} runs {@link TeacherServer} (Direction A): pair it with
 * lstar-rocq's {@code dune exec examples/socket_learn.exe -- <algo> [port]}.
 * The third argument, if given, also runs LearnLib's own learner for that
 * algorithm locally against each target and logs whether it agrees with our
 * extracted learner's hypothesis (see {@link TeacherServer}'s doc comment).
 * {@code learner} runs {@link LearnerClient} (Direction B): pair it with
 * {@code dune exec examples/socket_teach.exe -- [port]}.
 */
public final class HarnessMain {

    private HarnessMain() {}

    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            usage();
        }
        String mode = args[0];
        String[] rest = java.util.Arrays.copyOfRange(args, 1, args.length);
        switch (mode) {
            case "teacher" -> TeacherServer.main(rest);
            case "learner" -> LearnerClient.main(rest);
            default -> usage();
        }
    }

    private static void usage() {
        System.err.println("usage: HarnessMain teacher [port] [small]");
        System.err.println("       HarnessMain learner [host] [port] [lstar|kv|ttt]");
        System.exit(1);
    }
}
