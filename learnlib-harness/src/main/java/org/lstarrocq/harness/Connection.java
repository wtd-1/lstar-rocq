package org.lstarrocq.harness;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * A line-oriented socket connection used for both harness directions: one
 * side dials out ({@link #connectTo}), the other accepts
 * ({@link #of(Socket)}). {@code TCP_NODELAY} is set on both constructions --
 * learning makes many small blocking request/response round-trips, and
 * without it Nagle's algorithm interacting with delayed ACKs adds tens of
 * milliseconds to each one (this bit the OCaml side of the bridge too; see
 * lib/SocketTeacher.ml).
 */
public final class Connection implements AutoCloseable {

    private final Socket socket;
    private final BufferedReader in;
    private final PrintWriter out;

    private Connection(Socket socket) throws IOException {
        socket.setTcpNoDelay(true);
        this.socket = socket;
        this.in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        this.out = new PrintWriter(
                new java.io.OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), false);
    }

    public static Connection of(Socket socket) throws IOException {
        return new Connection(socket);
    }

    public static Connection connectTo(String host, int port) throws IOException {
        return new Connection(new Socket(host, port));
    }

    public void sendLine(String line) {
        out.print(line);
        out.print('\n');
        out.flush();
    }

    /** Blocks until a line arrives; returns {@code null} at end of stream. */
    public String readLine() throws IOException {
        return in.readLine();
    }

    /** Skips blank lines, since the OCaml side tolerates them between messages. */
    public String readNonBlankLine() throws IOException {
        String line;
        do {
            line = readLine();
        } while (line != null && line.isBlank());
        return line;
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
