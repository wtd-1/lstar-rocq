(** Direction B of the LearnLib interop harness: a small self-contained OCaml
    process plays *teacher* (server) for a corpus of target languages, while
    a Java/LearnLib process (see [learnlib-harness/LearnerClient.java]) plays
    *learner* (client), running LearnLib's own L*/KV/TTT implementations
    against it. This exercises the wire protocol and target definitions
    independently of our extracted learners -- Direction A
    ([socket_learn.ml]) is what actually exercises them.

    Equivalence here is checked by bounded BFS over words up to [max_len],
    exactly as the original single-target mock oracle in
    [alternating_socket.ml] did -- unlike the Java teacher in Direction A,
    this is *not* an exact oracle, so it is a cross-check on the protocol and
    target definitions rather than a proof that the client learned exactly. *)

open Unix

type target = {name: string; alphabet_size: int; member: string list -> bool}

(** Words are lists of decimal symbol tokens ["0"] .. [string_of_int
    (alphabet_size - 1)], matching {!SocketProtocol}'s wire convention. *)

let alternating : target =
  { name= "alternating"
  ; alphabet_size= 2
  ; member=
      (fun w ->
        let rec go = function
          | [] | [_] ->
              true
          | a :: (b :: _ as rest) ->
              a <> b && go rest
        in
        go w ) }

let ends_in_01 : target =
  { name= "ends_in_01"
  ; alphabet_size= 2
  ; member=
      (fun w ->
        match Stdlib.List.rev w with
        | "1" :: "0" :: _ ->
            true
        | _ ->
            false ) }

let mod3 : target =
  { name= "mod3"
  ; alphabet_size= 2
  ; member=
      (fun w ->
        let value =
          Stdlib.List.fold_left (fun acc s -> (2 * acc) + int_of_string s) 0 w
        in
        value mod 3 = 0 ) }

let targets = [alternating; ends_in_01; mod3]

let config_line (t : target) : string =
  SocketProtocol.config_line
    ~alphabet:(Stdlib.List.init t.alphabet_size string_of_int)
    ~target:t.name

(** Runs [word] through the client's hypothesis automaton, as described by
    the ["eq"] request's parsed fields (see {!SocketProtocol.parse_transitions}
    and {!SocketProtocol.parse_accepting_states}). Unlisted transitions are
    treated as self-loops, matching the client-side serializer's convention
    of only sending complete DFAs (every state has one outgoing edge per
    symbol), so this only matters for a malformed hypothesis. *)
let hypothesis_accepts ~(transitions : (int * string * int) list)
    ~(accepting : int list) ~(initial : int) (word : string list) : bool =
  let step state sym =
    match
      Stdlib.List.find_opt (fun (f, s, _) -> f = state && s = sym) transitions
    with
    | Some (_, _, dst) ->
        dst
    | None ->
        state
  in
  let final = Stdlib.List.fold_left step initial word in
  Stdlib.List.mem final accepting

let find_counterexample ~(alphabet : string list) ~(max_len : int)
    ~(member : string list -> bool) ~(initial : int)
    ~(transitions : (int * string * int) list) ~(accepting : int list) :
    string option =
  (* Level-by-level rather than a single queue with [rest @ next]: appending
     to the end of a growing list is O(length), so a naive single-queue BFS
     over an exponential number of words (up to [|alphabet| ^ max_len]) is
     quadratic in the word count. Rebuilding each depth's frontier via
     [concat_map] keeps this linear. *)
  let rec go depth frontier =
    if depth > max_len then
      None
    else
      match
        Stdlib.List.find_opt
          (fun word ->
            hypothesis_accepts ~transitions ~accepting ~initial word
            <> member word )
          frontier
      with
      | Some word ->
          Some (String.concat "," word)
      | None ->
          let next =
            Stdlib.List.concat_map
              (fun word -> Stdlib.List.map (fun c -> word @ [c]) alphabet)
              frontier
          in
          go (depth + 1) next
  in
  go 0 [[]]

(** Answers queries for a single target until the client sends ["ack"], then
    returns so the caller can move to the next target. Critically, this does
    *not* stop as soon as an equivalence query comes back ["NONE"]: some
    learners issue a few more membership queries after that (see
    {!SocketProtocol}'s doc comment), and only the client's explicit ["ack"]
    means it's truly finished with this target. *)
let handle_target (ic : in_channel) (oc : out_channel) (t : target) : unit =
  SocketProtocol.send_line oc (config_line t) ;
  let alphabet = Stdlib.List.init t.alphabet_size string_of_int in
  let rec loop () =
    let line = input_line ic in
    if String.trim line = "" then
      loop ()
    else if SocketProtocol.is_ack line then
      ()
    else if SocketProtocol.is_membership_query line then begin
      let word = SocketProtocol.word_of_json line in
      SocketProtocol.send_line oc (string_of_bool (t.member word)) ;
      loop ()
    end
    else if SocketProtocol.is_equiv_query line then begin
      let initial = SocketProtocol.extract_int_field "initial_state" line in
      let transitions = SocketProtocol.parse_transitions line in
      let accepting = SocketProtocol.parse_accepting_states line in
      ( match
          find_counterexample ~alphabet ~max_len:15 ~member:t.member ~initial
            ~transitions ~accepting
        with
      | None ->
          SocketProtocol.send_line oc "NONE"
      | Some ce ->
          SocketProtocol.send_line oc ce ) ;
      loop ()
    end
    else
      failwith (Printf.sprintf "unexpected line for target %s: %s" t.name line)
  in
  loop ()

let () =
  let port =
    if Array.length Sys.argv > 1 then
      int_of_string Sys.argv.(1)
    else
      8888
  in
  let server = socket PF_INET SOCK_STREAM 0 in
  setsockopt server SO_REUSEADDR true ;
  bind server (ADDR_INET (inet_addr_loopback, port)) ;
  listen server 1 ;
  Printf.printf
    "Direction-B teacher listening on port %d, serving %d target(s)\n%!" port
    (Stdlib.List.length targets) ;
  let client, _ = accept server in
  (* Many small blocking request/response round-trips happen per target;
     without this, Nagle's algorithm interacting with delayed ACKs adds tens
     of milliseconds to each one. *)
  setsockopt client TCP_NODELAY true ;
  let ic = in_channel_of_descr client in
  let oc = out_channel_of_descr client in
  Stdlib.List.iter (handle_target ic oc) targets ;
  SocketProtocol.send_line oc SocketProtocol.done_line ;
  (* [ic] and [oc] wrap the same underlying fd as [client]/[server], so only
     one of them should be closed -- closing [oc] flushes it, and closing the
     already-closed fd again below would otherwise raise [Sys_error]. *)
  close_out oc ;
  Unix.close server
