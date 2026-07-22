open Unix
open Lstar
open DFA
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
