(** Direction A of the LearnLib interop harness: our extracted learner is the
    socket *client*, and a Java/LearnLib process (see
    [learnlib-harness/TeacherServer.java]) is the *teacher*, answering
    membership/equivalence queries for a whole corpus of target automata over
    one persistent connection.

    Wire protocol is {!SocketProtocol}: the server sends a ["config"]
    handshake naming each target's alphabet size (and, for Mealy/Moore
    targets, its output alphabet size too) before querying begins for it,
    and a ["done"] line once the corpus is exhausted. Since alphabet sizes
    aren't known until the handshake arrives, the [Symbol] module for each
    target (input, and output where relevant) is built at runtime via
    {!SocketProtocol.MakeIntSymbol}. *)

open Unix
open Teacher

let usage () : 'a =
  prerr_endline
    "usage: socket_learn \
     <lstar|kv|ttt|nlstar|lstar_mealy|kv_mealy|ttt_mealy|lstar_moore|kv_moore|ttt_moore> \
     [port]" ;
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
  match algo with
  | "lstar" | "kv" | "ttt" | "nlstar" | "lstar_mealy" | "kv_mealy"
  | "ttt_mealy" | "lstar_moore" | "kv_moore" | "ttt_moore" ->
      ()
  | _ ->
      usage ()

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

(** Common tail of a target run: report it, then send ["ack"]. Some
    algorithms (KV, TTT) rebuild their final hypothesis right after the last
    equivalence query comes back empty, and that rebuild can itself issue a
    few more membership queries (see {!SocketProtocol}'s doc comment). Those
    have already happened by the time the caller's [num_states] was read, so
    it's safe to tell the teacher we're done now. *)
let finish (cfg : SocketProtocol.config) (k : int) (elapsed : float)
    (num_states : int) : unit =
  incr targets_run ;
  Printf.printf "target=%-20s algo=%-12s alphabet=%-3d states=%-5d time=%.3fs\n%!"
    cfg.target algo k num_states elapsed ;
  SocketProtocol.send_line oc SocketProtocol.ack_line

let run_target (cfg : SocketProtocol.config) : unit =
  let k = Stdlib.List.length cfg.alphabet in
  if k = 0 then failwith "target has an empty alphabet" ;
  let module K = struct
    let k = k
  end in
  let module S = SocketProtocol.MakeIntSymbol (K) in
  let start = Unix.gettimeofday () in
  match algo with
  | "lstar" | "kv" | "ttt" ->
      let module ST = SocketTeacher.MakeProtocolTeacher (Conn) (S) in
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
         [ST.D.transition]/[ST.D.accept], reading it issues no further
         membership queries -- see the module-level doc comment for why that
         matters (the teacher only advances once it sees our ["ack"]). *)
      let num_states = Stdlib.List.length (ST.D.states dfa) in
      finish cfg k elapsed num_states
  | "nlstar" ->
      let module NT = SocketTeacher.MakeProtocolNFATeacher (Conn) (S) in
      let module L = NLstarLearner (NT) in
      let rfsa = L.nlstar () in
      let elapsed = Unix.gettimeofday () -. start in
      let num_states = Stdlib.List.length (NT.R.N.states (NT.R.nfa rfsa)) in
      finish cfg k elapsed num_states
  | "lstar_mealy" | "kv_mealy" | "ttt_mealy" | "lstar_moore" | "kv_moore"
  | "ttt_moore" ->
      let out_k = Stdlib.List.length cfg.output_alphabet in
      if out_k = 0 then failwith "target has an empty output alphabet" ;
      let module OK = struct
        let k = out_k
      end in
      let module O = SocketProtocol.MakeIntSymbol (OK) in
      ( match algo with
      | "lstar_mealy" | "kv_mealy" | "ttt_mealy" ->
          let module MT = SocketTeacher.MakeProtocolMealyTeacher (Conn) (S) (O) in
          let mealy =
            match algo with
            | "lstar_mealy" ->
                let module L = MealyLstarLearner (MT) in
                L.mlstar ()
            | "kv_mealy" ->
                let module L = MealyKVLearner (MT) in
                L.mkv ()
            | "ttt_mealy" ->
                let module L = MealyTTTLearner (MT) in
                L.mkv ()
            | _ ->
                assert false
          in
          let elapsed = Unix.gettimeofday () -. start in
          let num_states = Stdlib.List.length (MT.M.states mealy) in
          finish cfg k elapsed num_states
      | _ ->
          let module MT = SocketTeacher.MakeProtocolMooreTeacher (Conn) (S) (O) in
          let moore =
            match algo with
            | "lstar_moore" ->
                let module L = MooreLstarLearner (MT) in
                L.mlstar ()
            | "kv_moore" ->
                let module L = MooreKVLearner (MT) in
                L.mkv ()
            | "ttt_moore" ->
                let module L = MooreTTTLearner (MT) in
                L.mttt ()
            | _ ->
                assert false
          in
          let elapsed = Unix.gettimeofday () -. start in
          let num_states = Stdlib.List.length (MT.M.states moore) in
          finish cfg k elapsed num_states )
  | _ ->
      assert false

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
