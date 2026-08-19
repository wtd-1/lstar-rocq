package org.lstarrocq.harness.it;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Locates the dune-built {@code socket_learn.exe}, which {@link OCamlLearningAlgorithm}
 * spawns fresh per learning session. Maven runs with its working directory set to the
 * module's basedir ({@code learnlib-harness/}), so the repo root is always its parent. */
final class OCamlBinary {

    static final Path SOCKET_LEARN = resolve();

    private OCamlBinary() {}

    private static Path resolve() {
        Path repoRoot = Paths.get(System.getProperty("user.dir")).toAbsolutePath().getParent();
        Path bin = repoRoot.resolve("_build/default/examples/socket_learn.exe");
        if (!Files.isExecutable(bin)) {
            throw new IllegalStateException(
                    "socket_learn.exe not found at " + bin + " -- run `dune build` in the repo root first");
        }
        return bin;
    }
}
