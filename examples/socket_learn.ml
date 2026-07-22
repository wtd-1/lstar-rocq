(** Direction A of the LearnLib interop harness: our extracted learner is the
    socket *client*, and a Java/LearnLib process (see
    [learnlib-harness/TeacherServer.java]) is the *teacher*, answering
    membership/equivalence queries for a whole corpus of target DFAs over one
    persistent connection.

    Wire protocol is {!SocketProtocol}: the server sends a ["config"]
    handshake naming each target's alphabet size before querying begins for
    it, and a ["done"] line once the corpus is exhausted. Since the alphabet
    size isn't known until the handshake arrives, the [Symbol] module for
    each target is built at runtime via {!SocketProtocol.MakeIntSymbol}. *)

open Unix
open Teacher

let usage () : 'a =
  prerr_endline "usage: socket_learn <lstar|kv|ttt> [port]" ;
  exit 1

let algo, port =
  match Sys.argv with
  | [|_; a|] ->
      (a, 8888)
  | [|_; a; p|] ->
      (a, int_of_string p)
  | _ ->
      usage ()

let () =
  match algo with "lstar" | "kv" | "ttt" -> () | _ -> usage ()

let sock = socket PF_INET SOCK_STREAM 0

(* Learning makes many small blocking request/response round-trips; without
   this, Nagle's algorithm interacting with delayed ACKs adds tens of
   milliseconds to each one. *)
let () = setsockopt sock TCP_NODELAY true

let () = connect sock (ADDR_INET (inet_addr_loopback, port))

let oc = out_channel_of_descr sock

let ic = in_channel_of_descr sock

(** Both queries for a target and the handshake lines between targets travel
    over this one connection, so every [ST] we build below shares it. *)
module Conn = struct
  let get_channels () = (oc, ic)
end

let rec read_control_line () : string =
  let line = input_line ic in
  if String.trim line = "" then
    read_control_line ()
  else
    line

let targets_run = ref 0

let run_target (cfg : SocketProtocol.config) : unit =
  let k = Stdlib.List.length cfg.alphabet in
  if k = 0 then failwith "target has an empty alphabet" ;
  let module K = struct
    let k = k
  end in
  let module S = SocketProtocol.MakeIntSymbol (K) in
  let module ST = SocketTeacher.MakeProtocolTeacher (Conn) (S) in
  let start = Unix.gettimeofday () in
  let dfa =
    match algo with
    | "lstar" ->
        let module L = LstarLearner (ST) in
        L.lstar ()
    | "kv" ->
        let module L = KVLearner (ST) in
        L.kv ()
    | "ttt" ->
        let module L = TTTLearner (ST) in
        L.ttt ()
    | _ ->
        assert false
  in
  let elapsed = Unix.gettimeofday () -. start in
  (* [ST.D.states] is a plain, already-computed field: unlike
     [ST.D.transition]/[ST.D.accept], reading it issues no further membership
     queries. That matters here because the teacher only advances to the next
     target once it sees our ["ack"] below, but it has no way to know we're
     done until we send it -- any post-hoc exploration of the hypothesis
     that (re-)triggers queries (e.g. walking [D.transition] to print or
     export the automaton, as {!Teacher.DFAPrinter.discover} does) would
     itself need to happen before that ["ack"], and there's no reason to
     delay it for that. *)
  let num_states = Stdlib.List.length (ST.D.states dfa) in
  incr targets_run ;
  Printf.printf "target=%-20s algo=%-5s alphabet=%-3d states=%-5d time=%.3fs\n%!"
    cfg.target algo k num_states elapsed ;
  (* Some algorithms (KV, TTT) rebuild their final hypothesis right after the
     last equivalence query comes back empty, and that rebuild can itself
     issue a few more membership queries (see {!SocketProtocol}'s doc
     comment). Those have already happened by the time [L.kv ()]/[L.ttt ()]
     returned above, so it's safe to tell the teacher we're done now. *)
  SocketProtocol.send_line oc SocketProtocol.ack_line

let rec loop () =
  let line = read_control_line () in
  if SocketProtocol.is_done line then
    ()
  else
    match SocketProtocol.parse_config line with
    | None ->
        failwith (Printf.sprintf "expected config or done, got: %s" line)
    | Some cfg ->
        run_target cfg ; loop ()

let () =
  loop () ;
  (* [ic] and [oc] wrap the same underlying fd as [sock], so only one of them
     should be closed -- see the matching note in socket_teach.ml. *)
  close_out oc ;
  Printf.printf "Ran %d target(s) with %s\n%!" !targets_run algo
