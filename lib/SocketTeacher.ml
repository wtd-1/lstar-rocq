open Unix
open Lstar_DFA
open DFA
open Mealy
open Moore
open NFA
open Alphabet
open Teacher

(** How a protocol teacher obtains its socket channels. Splitting this out of
    {!MakeProtocolTeacher} lets a driver that needs to hold one persistent
    connection across several targets (e.g. reading a ["config"]/["done"]
    handshake between them, as {!SocketProtocol} defines) supply its own
    already-open channels, while {!MakeSocketTeacher} keeps the original
    single-target behaviour of dialing its own connection on first use. *)
module type CONN = sig
  val get_channels : unit -> out_channel * in_channel
end

(** A {!DFATEACHER} that answers membership/equivalence queries by sending
    {!SocketProtocol}'s ["mq"]/["eq"] requests over whatever channels [C]
    provides, and reading the single-line reply. *)
module MakeProtocolTeacher (C : CONN) (S : Symbol) : DFATEACHER with module S = S =
struct
  module S = S
  module D = DFA (S)

  let transaction (payload : string) : string =
    let oc, ic = C.get_channels () in
    SocketProtocol.send_line oc payload ;
    input_line ic

  (** Membership Query *)
  let member (str : S.str) : bool =
    let encoded_str = String.concat "," (Stdlib.List.map S.string_of_t str) in
    let json_req =
      Printf.sprintf "{\"type\":\"mq\",\"word\":\"%s\"}" encoded_str
    in
    let res = transaction json_req in
    res = "true"

  (** Self-contained DFA Serialization Helper *)
  let serialize_dfa_to_json (d : 'st D.t) : string =
    let ids = Hashtbl.create 16 in
    let order = ref [] in
    let next = ref 0 in
    let id_of s = Hashtbl.find ids s in
    let rec dfs s =
      if not (Hashtbl.mem ids s) then begin
        Hashtbl.add ids s !next ;
        incr next ;
        order := s :: !order ;
        Stdlib.List.iter (fun c -> dfs (D.transition d s c)) S.enum
      end
    in
    dfs (D.initial d) ;
    let states = Stdlib.List.rev !order in
    let init_id = id_of (D.initial d) in
    let buf = Buffer.create 1024 in
    Buffer.add_string buf "{" ;
    Buffer.add_string buf "\"type\":\"eq\"," ;
    Printf.bprintf buf "\"initial_state\":%d," init_id ;
    Buffer.add_string buf "\"states\":[" ;
    let first_state = ref true in
    Stdlib.List.iter
      (fun s ->
        let i = id_of s in
        let is_accept = D.accept d s in
        if not !first_state then
          Buffer.add_string buf ","
        else
          first_state := false ;
        Printf.bprintf buf "{\"id\":%d,\"accept\":%b}" i is_accept )
      states ;
    Buffer.add_string buf "]," ;
    Buffer.add_string buf "\"transitions\":[" ;
    let first_trans = ref true in
    Stdlib.List.iter
      (fun s ->
        let i = id_of s in
        Stdlib.List.iter
          (fun c ->
            let dst = D.transition d s c in
            if not !first_trans then
              Buffer.add_string buf ","
            else
              first_trans := false ;
            Printf.bprintf buf "{\"from\":%d,\"input\":\"%s\",\"to\":%d}" i
              (String.escaped (S.string_of_t c))
              (id_of dst) )
          S.enum )
      states ;
    Buffer.add_string buf "]" ;
    Buffer.add_string buf "}" ;
    Buffer.contents buf

  (** Equivalence Query *)
  let equiv_query (dfa : 'a D.t) : S.str option =
    let json_dfa = serialize_dfa_to_json dfa in
    let res = transaction json_dfa in
    if res = "NONE" then
      None
    else
      let tokens = String.split_on_char ',' res in
      let filtered_tokens =
        Stdlib.List.filter (fun s -> String.length s > 0) tokens
      in
      match
        SocketProtocol.results_to_list
          (Stdlib.List.map S.t_of_string filtered_tokens)
      with
      | Error e ->
          failwith e
      | Ok l ->
          Some l

  let fuel = Int.max_int
end

(** Single-target teacher that dials its own connection to [localhost:8888]
    the first time it's needed, and reuses it thereafter. This is the
    original behaviour used by e.g. [examples/alternating_socket.ml]. *)
module MakeSocketTeacher (S : Symbol) : DFATEACHER with module S = S =
  MakeProtocolTeacher
    (struct
      let server_addr : sockaddr = ADDR_INET (inet_addr_loopback, 8888)

      let session_channels = ref None

      let get_channels () =
        match !session_channels with
        | Some (oc, ic) ->
            (oc, ic)
        | None ->
            let sock = socket PF_INET SOCK_STREAM 0 in
            (* Learning makes many small blocking request/response
               round-trips; without this, Nagle's algorithm interacting with
               delayed ACKs adds tens of milliseconds to each one. *)
            setsockopt sock TCP_NODELAY true ;
            connect sock server_addr ;
            let oc = out_channel_of_descr sock in
            let ic = in_channel_of_descr sock in
            session_channels := Some (oc, ic) ;
            (oc, ic)
    end)
    (S)

(** Same shared-connection dial-once behaviour as [get_channels] above,
    factored out so the Mealy/Moore/NFA single-target teachers below don't
    each repeat it. *)
let dial_once_channels (addr : sockaddr) : unit -> out_channel * in_channel =
  let session_channels = ref None in
  fun () ->
    match !session_channels with
    | Some (oc, ic) ->
        (oc, ic)
    | None ->
        let sock = socket PF_INET SOCK_STREAM 0 in
        setsockopt sock TCP_NODELAY true ;
        connect sock addr ;
        let oc = out_channel_of_descr sock in
        let ic = in_channel_of_descr sock in
        session_channels := Some (oc, ic) ;
        (oc, ic)

(** A {!MEALYTEACHER} that answers {!SocketProtocol}'s ["mq_mealy"]/
    ["eq_mealy"] requests over whatever channels [C] provides. Mirrors
    {!MakeProtocolTeacher}: ["mq_mealy"]'s word is [prefix @ [a]] (the
    teacher replies with just that last step's output, not a whole output
    word -- see {!MEALYTEACHER.output_lang}), and ["eq_mealy"]'s
    transitions each carry their own ["output"] field, since Mealy output
    lives on transitions rather than states. *)
module MakeProtocolMealyTeacher (C : CONN) (S : Symbol) (O : Symbol) :
  MEALYTEACHER with module S = S and module O = O = struct
  module S = S
  module O = O
  module M = Mealy (S) (O)

  let transaction (payload : string) : string =
    let oc, ic = C.get_channels () in
    SocketProtocol.send_line oc payload ;
    input_line ic

  let output_lang (prefix : S.str) (a : S.t) : O.t =
    let word = Stdlib.List.append prefix (a :: []) in
    let encoded_str = String.concat "," (Stdlib.List.map S.string_of_t word) in
    let json_req =
      Printf.sprintf "{\"type\":\"mq_mealy\",\"word\":\"%s\"}" encoded_str
    in
    let res = transaction json_req in
    match O.t_of_string res with
    | Ok o ->
        o
    | Error e ->
        failwith e

  let serialize_mealy_to_json (m : 'st M.t) : string =
    let states = M.states m in
    let ids = Hashtbl.create 16 in
    Stdlib.List.iteri (fun i s -> Hashtbl.add ids s i) states ;
    let id_of s = Hashtbl.find ids s in
    let init_id = id_of (M.initial m) in
    let buf = Buffer.create 1024 in
    Buffer.add_string buf "{" ;
    Buffer.add_string buf "\"type\":\"eq_mealy\"," ;
    Printf.bprintf buf "\"initial_state\":%d," init_id ;
    Buffer.add_string buf "\"states\":[" ;
    let first_state = ref true in
    Stdlib.List.iter
      (fun s ->
        if not !first_state then
          Buffer.add_string buf ","
        else
          first_state := false ;
        Printf.bprintf buf "{\"id\":%d}" (id_of s) )
      states ;
    Buffer.add_string buf "]," ;
    Buffer.add_string buf "\"transitions\":[" ;
    let first_trans = ref true in
    Stdlib.List.iter
      (fun s ->
        let i = id_of s in
        Stdlib.List.iter
          (fun c ->
            let dst = M.transition m s c in
            let out = M.output m s c in
            if not !first_trans then
              Buffer.add_string buf ","
            else
              first_trans := false ;
            Printf.bprintf buf
              "{\"from\":%d,\"input\":\"%s\",\"to\":%d,\"output\":\"%s\"}" i
              (String.escaped (S.string_of_t c))
              (id_of dst)
              (String.escaped (O.string_of_t out)) )
          S.enum )
      states ;
    Buffer.add_string buf "]" ;
    Buffer.add_string buf "}" ;
    Buffer.contents buf

  let equiv_query (m : 'a M.t) : S.str option =
    let json_m = serialize_mealy_to_json m in
    let res = transaction json_m in
    if res = "NONE" then
      None
    else
      let tokens = String.split_on_char ',' res in
      let filtered_tokens =
        Stdlib.List.filter (fun s -> String.length s > 0) tokens
      in
      match
        SocketProtocol.results_to_list
          (Stdlib.List.map S.t_of_string filtered_tokens)
      with
      | Error e ->
          failwith e
      | Ok l ->
          Some l

  let fuel = Int.max_int
end

module MakeSocketMealyTeacher (S : Symbol) (O : Symbol) :
  MEALYTEACHER with module S = S and module O = O =
  MakeProtocolMealyTeacher
    (struct
      let get_channels = dial_once_channels (ADDR_INET (inet_addr_loopback, 8888))
    end)
    (S)
    (O)

(** A {!MOORETEACHER} that answers {!SocketProtocol}'s ["mq_moore"]/
    ["eq_moore"] requests. Unlike Mealy, ["mq_moore"]'s word is the whole
    query word (the teacher replies with the output of the state it ends
    in -- see {!MOORETEACHER.output_lang}), and ["eq_moore"]'s output lives
    on states (["state_outputs"]), not transitions. *)
module MakeProtocolMooreTeacher (C : CONN) (S : Symbol) (O : Symbol) :
  MOORETEACHER with module S = S and module O = O = struct
  module S = S
  module O = O
  module M = Moore (S) (O)

  let transaction (payload : string) : string =
    let oc, ic = C.get_channels () in
    SocketProtocol.send_line oc payload ;
    input_line ic

  let output_lang (w : S.str) : O.t =
    let encoded_str = String.concat "," (Stdlib.List.map S.string_of_t w) in
    let json_req =
      Printf.sprintf "{\"type\":\"mq_moore\",\"word\":\"%s\"}" encoded_str
    in
    let res = transaction json_req in
    match O.t_of_string res with
    | Ok o ->
        o
    | Error e ->
        failwith e

  let serialize_moore_to_json (m : 'st M.t) : string =
    let states = M.states m in
    let ids = Hashtbl.create 16 in
    Stdlib.List.iteri (fun i s -> Hashtbl.add ids s i) states ;
    let id_of s = Hashtbl.find ids s in
    let init_id = id_of (M.initial m) in
    let buf = Buffer.create 1024 in
    Buffer.add_string buf "{" ;
    Buffer.add_string buf "\"type\":\"eq_moore\"," ;
    Printf.bprintf buf "\"initial_state\":%d," init_id ;
    Buffer.add_string buf "\"state_outputs\":[" ;
    let first_state = ref true in
    Stdlib.List.iter
      (fun s ->
        if not !first_state then
          Buffer.add_string buf ","
        else
          first_state := false ;
        Printf.bprintf buf "{\"id\":%d,\"output\":\"%s\"}" (id_of s)
          (String.escaped (O.string_of_t (M.output m s))) )
      states ;
    Buffer.add_string buf "]," ;
    Buffer.add_string buf "\"transitions\":[" ;
    let first_trans = ref true in
    Stdlib.List.iter
      (fun s ->
        let i = id_of s in
        Stdlib.List.iter
          (fun c ->
            let dst = M.transition m s c in
            if not !first_trans then
              Buffer.add_string buf ","
            else
              first_trans := false ;
            Printf.bprintf buf "{\"from\":%d,\"input\":\"%s\",\"to\":%d}" i
              (String.escaped (S.string_of_t c))
              (id_of dst) )
          S.enum )
      states ;
    Buffer.add_string buf "]" ;
    Buffer.add_string buf "}" ;
    Buffer.contents buf

  let equiv_query (m : 'a M.t) : S.str option =
    let json_m = serialize_moore_to_json m in
    let res = transaction json_m in
    if res = "NONE" then
      None
    else
      let tokens = String.split_on_char ',' res in
      let filtered_tokens =
        Stdlib.List.filter (fun s -> String.length s > 0) tokens
      in
      match
        SocketProtocol.results_to_list
          (Stdlib.List.map S.t_of_string filtered_tokens)
      with
      | Error e ->
          failwith e
      | Ok l ->
          Some l

  let fuel = Int.max_int
end

module MakeSocketMooreTeacher (S : Symbol) (O : Symbol) :
  MOORETEACHER with module S = S and module O = O =
  MakeProtocolMooreTeacher
    (struct
      let get_channels = dial_once_channels (ADDR_INET (inet_addr_loopback, 8888))
    end)
    (S)
    (O)

(** An {!NFATEACHER} that answers {!SocketProtocol}'s plain ["mq"] (NFA
    membership is still just boolean, same as DFA) and ["eq_nfa"] requests.
    Unlike ["eq"], ["eq_nfa"]'s hypothesis carries a set of initial states
    (["initial_states"]) and a genuine from/input -&gt; {to} relation
    (repeated ["transitions"] entries for the same (state, symbol) pair
    accumulate rather than overwrite), since NL*'s hypothesis is an RFSA,
    not a DFA. *)
module MakeProtocolNFATeacher (C : CONN) (S : Symbol) :
  NFATEACHER with module S = S = struct
  module S = S
  module R = RFSA (S)

  let transaction (payload : string) : string =
    let oc, ic = C.get_channels () in
    SocketProtocol.send_line oc payload ;
    input_line ic

  let member (str : S.str) : bool =
    let encoded_str = String.concat "," (Stdlib.List.map S.string_of_t str) in
    let json_req =
      Printf.sprintf "{\"type\":\"mq\",\"word\":\"%s\"}" encoded_str
    in
    transaction json_req = "true"

  let serialize_nfa_to_json (n : 'st R.N.t) : string =
    let states = R.N.states n in
    let ids = Hashtbl.create 16 in
    Stdlib.List.iteri (fun i s -> Hashtbl.add ids s i) states ;
    let id_of s = Hashtbl.find ids s in
    let init_ids = Stdlib.List.map id_of (R.N.initial n) in
    let buf = Buffer.create 1024 in
    Buffer.add_string buf "{" ;
    Buffer.add_string buf "\"type\":\"eq_nfa\"," ;
    Buffer.add_string buf "\"initial_states\":[" ;
    Buffer.add_string buf
      (String.concat "," (Stdlib.List.map string_of_int init_ids)) ;
    Buffer.add_string buf "]," ;
    Buffer.add_string buf "\"states\":[" ;
    let first_state = ref true in
    Stdlib.List.iter
      (fun s ->
        if not !first_state then
          Buffer.add_string buf ","
        else
          first_state := false ;
        Printf.bprintf buf "{\"id\":%d,\"accept\":%b}" (id_of s)
          (R.N.accept n s) )
      states ;
    Buffer.add_string buf "]," ;
    Buffer.add_string buf "\"transitions\":[" ;
    let first_trans = ref true in
    Stdlib.List.iter
      (fun s ->
        let i = id_of s in
        Stdlib.List.iter
          (fun c ->
            Stdlib.List.iter
              (fun dst ->
                if not !first_trans then
                  Buffer.add_string buf ","
                else
                  first_trans := false ;
                Printf.bprintf buf "{\"from\":%d,\"input\":\"%s\",\"to\":%d}" i
                  (String.escaped (S.string_of_t c))
                  (id_of dst) )
              (R.N.transition n s c) )
          S.enum )
      states ;
    Buffer.add_string buf "]" ;
    Buffer.add_string buf "}" ;
    Buffer.contents buf

  let equiv_query (n : 'a R.N.t) : S.str option =
    let json_n = serialize_nfa_to_json n in
    let res = transaction json_n in
    if res = "NONE" then
      None
    else
      let tokens = String.split_on_char ',' res in
      let filtered_tokens =
        Stdlib.List.filter (fun s -> String.length s > 0) tokens
      in
      match
        SocketProtocol.results_to_list
          (Stdlib.List.map S.t_of_string filtered_tokens)
      with
      | Error e ->
          failwith e
      | Ok l ->
          Some l

  let fuel = Int.max_int
end

module MakeSocketNFATeacher (S : Symbol) : NFATEACHER with module S = S =
  MakeProtocolNFATeacher
    (struct
      let get_channels = dial_once_channels (ADDR_INET (inet_addr_loopback, 8888))
    end)
    (S)
