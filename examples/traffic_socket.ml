(* European-style 4-phase traffic light, over a socket: self-contained smoke
   test for the ["mq_moore"]/["eq_moore"] wire format (single output symbol
   per membership query; per-state output rather than per-transition or
   accept/reject). Same target as traffic.ml. *)

open Specif
open Teacher
open SocketTeacher
open Unix

(** Input alphabet: a clock [Tick] and a [Reset] line *)
module S = struct
  type t = Tick | Reset

  let string_of_t = function Tick -> "t" | Reset -> "r"

  let t_of_string : string -> (t, string) Datatypes.result = function
    | "t" ->
        Ok Tick
    | "r" ->
        Ok Reset
    | _ ->
        Error "t_of_string"

  let eq_dec x y = x = y

  let enum = [Tick; Reset]

  type str = t list

  let string_of_str s = String.concat "" (Stdlib.List.map string_of_t s)
end

(** Output alphabet: the light state *)
module O = struct
  type t = Red | Green | Yellow | RedYellow

  let string_of_t = function
    | Red ->
        "RED"
    | Green ->
        "GREEN"
    | Yellow ->
        "YELLOW"
    | RedYellow ->
        "RED+YELLOW"

  let t_of_string : string -> (t, string) Datatypes.result = function
    | "RED" ->
        Ok Red
    | "GREEN" ->
        Ok Green
    | "YELLOW" ->
        Ok Yellow
    | "RED+YELLOW" ->
        Ok RedYellow
    | _ ->
        Error "t_of_string"

  let eq_dec x y = x = y

  type str = t list

  let enum = [Red; Green; Yellow; RedYellow]
end

(** Phases cycle on [Tick]: Red -> Red+Yellow -> Green -> Yellow -> Red -> ...
    [Reset] forces the lamp back to Red *)
let output_lang (s : S.str) : O.t =
  let phase =
    Stdlib.List.fold_left
      (fun p -> function S.Reset -> 0 | S.Tick -> (p + 1) mod 4)
      0 s
  in
  [|O.Red; O.RedYellow; O.Green; O.Yellow|].(phase)

let extract_int_field field json =
  let re = Str.regexp (Printf.sprintf {|"%s"[ \t]*:[ \t]*\([0-9]+\)|} field) in
  try
    ignore (Str.search_forward re json 0) ;
    int_of_string (Str.matched_group 1 json)
  with Not_found -> 0

let parse_transitions json =
  let re =
    Str.regexp
      {|{"from":[ \t]*\([0-9]+\),[ \t]*"input":[ \t]*"\([^"]+\)",[ \t]*"to":[ \t]*\([0-9]+\)}|}
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

(** [{"id":I,"output":"O"}] entries, as {!SocketTeacher.MakeProtocolMooreTeacher}'s
    serializer emits. *)
let parse_state_outputs json =
  let re =
    Str.regexp {|{"id":[ \t]*\([0-9]+\),[ \t]*"output":[ \t]*"\([^"]*\)"}|}
  in
  let rec find pos acc =
    match Str.search_forward re json pos with
    | _ ->
        let id = int_of_string (Str.matched_group 1 json) in
        let output = Str.matched_group 2 json in
        find (Str.match_end ()) ((id, output) :: acc)
    | exception Not_found ->
        acc
  in
  find 0 []

let is_membership_query line =
  match Str.search_forward (Str.regexp {|"type"[ \t]*:[ \t]*"mq_moore"|}) line 0 with
  | _ ->
      true
  | exception Not_found ->
      false

let word_of_json line =
  let re = Str.regexp {|"word"[ \t]*:[ \t]*"\([^"]*\)"|} in
  let raw =
    match Str.search_forward re line 0 with
    | _ ->
        Str.matched_group 1 line
    | exception Not_found ->
        ""
  in
  if raw = "" then
    []
  else
    Stdlib.List.filter_map
      (function "t" -> Some S.Tick | "r" -> Some S.Reset | _ -> None)
      (String.split_on_char ',' raw)

(** Deterministic run: unlike the NFA mock's step function, each (state,
    symbol) has exactly one destination -- Moore/Mealy machines are
    deterministic (see theories/automata/Moore.v/Mealy.v), so this can just
    look up the single matching transition instead of unioning a reachable
    set. *)
let hyp_output (initial : int) (transitions : (int * string * int) list)
    (outputs : (int * string) list) (word : S.str) : string =
  let step state sym =
    let sym_s = S.string_of_t sym in
    match
      Stdlib.List.find_opt (fun (f, i, _) -> f = state && i = sym_s) transitions
    with
    | Some (_, _, dst) ->
        dst
    | None ->
        state
  in
  let final = Stdlib.List.fold_left step initial word in
  match Stdlib.List.assoc_opt final outputs with Some o -> o | None -> ""

let find_counterexample ~max_len (initial : int)
    (transitions : (int * string * int) list) (outputs : (int * string) list)
    : string option =
  let rec bfs = function
    | [] ->
        None
    | word :: rest ->
        if Stdlib.List.length word > max_len then
          None
        else if
          hyp_output initial transitions outputs word
          <> O.string_of_t (output_lang word)
        then
          Some (String.concat "," (Stdlib.List.map S.string_of_t word))
        else
          bfs (rest @ Stdlib.List.map (fun c -> word @ [c]) S.enum)
  in
  bfs [[]]

let start_mock_server () =
  let server = socket PF_INET SOCK_STREAM 0 in
  setsockopt server SO_REUSEADDR true ;
  bind server (ADDR_INET (inet_addr_loopback, 8888)) ;
  listen server 1 ;
  let client, _ = accept server in
  setsockopt client TCP_NODELAY true ;
  let ic = in_channel_of_descr client in
  let oc = out_channel_of_descr client in
  let reply s =
    output_string oc (s ^ "\n") ;
    flush oc
  in
  try
    while true do
      let line = input_line ic in
      if String.trim line <> "" then
        if is_membership_query line then
          reply (O.string_of_t (output_lang (word_of_json line)))
        else
          let initial = extract_int_field "initial_state" line in
          let transitions = parse_transitions line in
          let outputs = parse_state_outputs line in
          match find_counterexample ~max_len:8 initial transitions outputs with
          | None ->
              reply "NONE"
          | Some ce ->
              reply ce
    done
  with End_of_file -> close_in ic ; close_out oc ; Unix.close server

let () =
  print_endline "Starting mock Moore oracle on port 8888..." ;
  ignore (Thread.create start_mock_server ()) ;
  Thread.delay 0.2

module MT = MakeSocketMooreTeacher (S) (O)
module NetworkLstar = MooreLstarLearner (MT)
module MP = MoorePrinter (MT)

let rec enumerate (n : int) : S.str list =
  if n <= 0 then
    [[]]
  else
    let prev = enumerate (n - 1) in
    let prepend c l = Stdlib.List.map (fun s -> [c] @ s) l in
    [[]] @ prepend S.Tick prev @ prepend S.Reset prev

let dedup l =
  Stdlib.List.fold_left
    (fun acc x -> if Stdlib.List.mem x acc then acc else x :: acc)
    [] l
  |> Stdlib.List.rev

let print_results name m n =
  Printf.printf "\n=== %s ===\n" name ;
  MP.print_moore m ;
  let strings = dedup (enumerate n) in
  let col_w = max 12 (n + 2) in
  Printf.printf "%-*s  %-11s  %-11s  %-8s\n" col_w "Input" "Expected" "Got"
    "Correct" ;
  let correct = ref 0 in
  Stdlib.List.iter
    (fun (c : S.str) ->
      let exp = output_lang c and comp = MT.M.output_string m c in
      if exp = comp then incr correct ;
      Printf.printf "%-*s  %-11s  %-11s  %s\n" col_w
        (Printf.sprintf "[%s]" (S.string_of_str c))
        (O.string_of_t exp) (O.string_of_t comp)
        ( if exp = comp then
            "Y"
          else
            "N" ) )
    strings ;
  Printf.printf "Accuracy: %d/%d\n" !correct (Stdlib.List.length strings)

let () =
  let m = NetworkLstar.mlstar () in
  print_results "Isolated Network Test Moore-L*" m 4
