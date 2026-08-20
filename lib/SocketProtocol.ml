(** Shared parsing/serialization helpers for the newline-delimited JSON socket
    protocol spoken between a learner and a teacher (whether both ends are
    OCaml, or one end is a bridge to another AAL framework such as LearnLib).

    Wire format, one JSON object per line:
    - Teacher -> Learner, sent once per target before any query for that
      target:
        {"type":"config","alphabet":["0","1"],"target":"name"}
    - Teacher -> Learner, sent once every target has been exhausted:
        {"type":"done"}
    - Learner -> Teacher, membership query:
        {"type":"mq","word":"0,1,0"}
      Teacher -> Learner reply: ["true"] or ["false"]
    - Learner -> Teacher, equivalence query (serialized hypothesis DFA):
        {"type":"eq","initial_state":N,"states":[{"id":I,"accept":B},...],
         "transitions":[{"from":F,"input":"S","to":T},...]}
      Teacher -> Learner reply: ["NONE"] or a comma-separated counterexample
      word
    - Learner -> Teacher, sent once the learner is done with a target (see
      note below): {"type":"ack"}

    A subtlety that makes the ["ack"] message necessary: several learners'
    hypothesis automata are not fully materialized tables. Angluin-style
    implementations that represent states as prefix strings or leaves of a
    discrimination tree (this project's KV and TTT, in particular) rebuild
    their *final* hypothesis right after an equivalence query comes back
    empty, and that rebuild can itself issue fresh membership queries (e.g.
    resolving the initial state by walking a discrimination tree). So a
    teacher must not assume a target is finished the instant it answers
    ["NONE"] -- it must keep answering that target's queries until the
    learner explicitly sends ["ack"], and only then send the next
    ["config"]/["done"]. *)

let send_line (oc : out_channel) (line : string) : unit =
  output_string oc (line ^ "\n") ;
  flush oc

let extract_int_field (field : string) (json : string) : int =
  let re = Str.regexp (Printf.sprintf {|"%s"[ \t]*:[ \t]*\([0-9]+\)|} field) in
  try
    ignore (Str.search_forward re json 0) ;
    int_of_string (Str.matched_group 1 json)
  with Not_found -> 0

let extract_string_field (field : string) (json : string) : string option =
  let re =
    Str.regexp (Printf.sprintf {|"%s"[ \t]*:[ \t]*"\([^"]*\)"|} field)
  in
  match Str.search_forward re json 0 with
  | _ ->
      Some (Str.matched_group 1 json)
  | exception Not_found ->
      None

(** Extracts every quoted string inside the (first) ["field":[ ... ]] array. *)
let extract_string_array_field (field : string) (json : string) : string list =
  let array_re =
    Str.regexp (Printf.sprintf {|"%s"[ \t]*:[ \t]*\[\([^]]*\)\]|} field)
  in
  match Str.search_forward array_re json 0 with
  | exception Not_found ->
      []
  | _ ->
      let inner = Str.matched_group 1 json in
      let item_re = Str.regexp {|"\([^"]*\)"|} in
      let rec find pos acc =
        match Str.search_forward item_re inner pos with
        | _ ->
            find (Str.match_end ()) (Str.matched_group 1 inner :: acc)
        | exception Not_found ->
            Stdlib.List.rev acc
      in
      find 0 []

let extract_type (json : string) : string option =
  extract_string_field "type" json

let parse_transitions (json : string) : (int * string * int) list =
  let re =
    Str.regexp
      {|{"from":[ \t]*\([0-9]+\),[ \t]*"input":[ \t]*"\([^"]*\)",[ \t]*"to":[ \t]*\([0-9]+\)}|}
  in
  let rec find pos acc =
    match Str.search_forward re json pos with
    | _ ->
        let from_id = int_of_string (Str.matched_group 1 json) in
        let input = Str.matched_group 2 json in
        let to_id = int_of_string (Str.matched_group 3 json) in
        find (Str.match_end ()) ((from_id, input, to_id) :: acc)
    | exception Not_found ->
        acc
  in
  find 0 []

let parse_accepting_states (json : string) : int list =
  let re = Str.regexp {|{"id":[ \t]*\([0-9]+\),[ \t]*"accept":[ \t]*true}|} in
  let rec find pos acc =
    match Str.search_forward re json pos with
    | _ ->
        find (Str.match_end ())
          (int_of_string (Str.matched_group 1 json) :: acc)
    | exception Not_found ->
        acc
  in
  find 0 []

let is_membership_query (json : string) : bool =
  extract_type json = Some "mq"

let is_equiv_query (json : string) : bool = extract_type json = Some "eq"

let is_config (json : string) : bool = extract_type json = Some "config"

let is_done (json : string) : bool = extract_type json = Some "done"

let is_ack (json : string) : bool = extract_type json = Some "ack"

let ack_line = {|{"type":"ack"}|}

(** Raw comma-separated symbol tokens carried by an ["mq"] request, in the
    order they appear in the word. Decoding tokens into a specific alphabet's
    [S.t] (via [S.t_of_string]) is left to the caller. *)
let word_of_json (json : string) : string list =
  match extract_string_field "word" json with
  | None | Some "" ->
      []
  | Some raw ->
      String.split_on_char ',' raw

type config = {alphabet: string list; output_alphabet: string list; target: string}

(** [output_alphabet] is only present on the handshake for Mealy/Moore
    targets (a DFA/NFA hypothesis carries no output alphabet at all); when
    the field is absent, [extract_string_array_field] already returns
    [[]], so this is safe to read unconditionally. *)
let parse_config (json : string) : config option =
  if is_config json then
    Some
      { alphabet= extract_string_array_field "alphabet" json
      ; output_alphabet= extract_string_array_field "output_alphabet" json
      ; target=
          ( match extract_string_field "target" json with
          | Some t ->
              t
          | None ->
              "" ) }
  else
    None

let config_line ?(output_alphabet : string list = [])
    ~(alphabet : string list) ~(target : string) () : string =
  let quote_list l =
    String.concat "," (Stdlib.List.map (Printf.sprintf "%S") l)
  in
  let output_field =
    if output_alphabet = [] then
      ""
    else
      Printf.sprintf {|,"output_alphabet":[%s]|} (quote_list output_alphabet)
  in
  Printf.sprintf {|{"type":"config","alphabet":[%s]%s,"target":%S}|}
    (quote_list alphabet) output_field target

let done_line = {|{"type":"done"}|}

(** Collapses a list of Rocq-extracted [result]s (as produced by e.g.
    [S.t_of_string]) into a single [result], short-circuiting on the first
    [Error]. *)
let rec results_to_list (l : ('a, 'b) Datatypes.result list) :
    ('a list, 'b) result =
  match l with
  | [] ->
      Ok []
  | Ok h :: t -> (
    match results_to_list t with
    | Error e ->
        Error e
    | Ok t' ->
        Ok (h :: t') )
  | Error e :: _ ->
      Error e

(** A [Symbol] whose alphabet is ["0"], ["1"], ..., [string_of_int (k - 1)],
    for a size [k] known only at runtime (e.g. learned from a ["config"]
    handshake). Applying this functor is an ordinary runtime module
    application, so [k] need not be a compile-time constant: a driver reads
    the handshake, then does

    {[
      let module K = struct let k = ... end in
      let module S = SocketProtocol.MakeIntSymbol (K) in
      ...
    ]} *)
module MakeIntSymbol (K : sig
  val k : int
end) : Alphabet.Symbol with type t = int and type str = int list = struct
  type t = int

  let eq_dec (x : t) (y : t) = x = y

  let enum : t list = Stdlib.List.init K.k (fun i -> i)

  let string_of_t (i : t) : string = string_of_int i

  let t_of_string (s : string) : (t, string) Datatypes.result =
    match int_of_string_opt s with
    | Some i when i >= 0 && i < K.k ->
        Ok i
    | _ ->
        Error (Printf.sprintf "t_of_string: %S is not a valid symbol" s)

  type str = t list

  let string_of_str (s : str) : string =
    String.concat "," (Stdlib.List.map string_of_t s)
end
