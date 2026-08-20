(* NL* over a socket, self-contained smoke test for the ["eq_nfa"] wire
   format (multiple initial states, a genuine from/input -> {to} relation,
   not a function) added alongside the DFA-only ["eq"] format. Same target
   language as suffix.ml: L_n = Sigma* a Sigma^n over {a, b}, the words
   carrying an [a] in the (n+1)-th position from the right. *)

open Specif
open Teacher
open SocketTeacher
open Unix

let n = 2

(** Two-symbol alphabet. *)
module S = struct
  type t = A | B

  let string_of_t = function A -> "a" | B -> "b"

  let t_of_string : string -> (t, string) Datatypes.result = function
    | "a" ->
        Ok A
    | "b" ->
        Ok B
    | _ ->
        Error "t_of_string"

  let eq_dec x y = x = y

  let enum = [A; B]

  type str = t list

  let string_of_str s = String.concat "" (Stdlib.List.map string_of_t s)
end

let language_member (s : S.str) : bool =
  match Stdlib.List.nth_opt (Stdlib.List.rev s) n with
  | Some S.A ->
      true
  | _ ->
      false

let extract_int_field field json =
  let re = Str.regexp (Printf.sprintf {|"%s"[ \t]*:[ \t]*\([0-9]+\)|} field) in
  try
    ignore (Str.search_forward re json 0) ;
    int_of_string (Str.matched_group 1 json)
  with Not_found -> 0

let extract_int_list_field field json =
  let re =
    Str.regexp (Printf.sprintf {|"%s"[ \t]*:[ \t]*\[\([^]]*\)\]|} field)
  in
  try
    ignore (Str.search_forward re json 0) ;
    let inner = Str.matched_group 1 json in
    if String.trim inner = "" then
      []
    else
      Stdlib.List.map int_of_string
        (String.split_on_char ',' inner |> Stdlib.List.map String.trim)
  with Not_found -> []

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

let parse_accepting_states json =
  let re = Str.regexp {|{"id":[ \t]*\([0-9]+\),[ \t]*"accept":[ \t]*true}|} in
  let rec find pos acc =
    match Str.search_forward re json pos with
    | _ ->
        find (Str.match_end ()) (int_of_string (Str.matched_group 1 json) :: acc)
    | exception Not_found ->
        acc
  in
  find 0 []

let is_membership_query line =
  match Str.search_forward (Str.regexp {|"type"[ \t]*:[ \t]*"mq"|}) line 0 with
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
      (function "a" -> Some S.A | "b" -> Some S.B | _ -> None)
      (String.split_on_char ',' raw)

(** Nondeterministic run: the current position is a *set* of reachable
    states, which a step expands to the union of every enabled transition
    from every state currently in it -- not just one, the way the DFA mock's
    [transition] helper picks a single destination (or self-loops if none
    match). *)
let nfa_accepts (initial : int list) (transitions : (int * string * int) list)
    (accepting : int list) (word : S.str) : bool =
  let step states sym =
    let sym_s = S.string_of_t sym in
    Stdlib.List.sort_uniq compare
      (Stdlib.List.concat_map
         (fun s ->
           Stdlib.List.filter_map
             (fun (f, i, t) -> if f = s && i = sym_s then Some t else None)
             transitions )
         states )
  in
  let final = Stdlib.List.fold_left step initial word in
  Stdlib.List.exists (fun s -> Stdlib.List.mem s accepting) final

let find_counterexample ~max_len (initial : int list)
    (transitions : (int * string * int) list) (accepting : int list) :
    string option =
  let accepts = nfa_accepts initial transitions accepting in
  let rec bfs = function
    | [] ->
        None
    | word :: rest ->
        if Stdlib.List.length word > max_len then
          None
        else if accepts word <> language_member word then
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
          reply (string_of_bool (language_member (word_of_json line)))
        else
          let initial = extract_int_list_field "initial_states" line in
          let transitions = parse_transitions line in
          let accepting = parse_accepting_states line in
          match
            find_counterexample ~max_len:(3 * (n + 2)) initial transitions
              accepting
          with
          | None ->
              reply "NONE"
          | Some ce ->
              reply ce
    done
  with End_of_file -> close_in ic ; close_out oc ; Unix.close server

let () =
  print_endline "Starting mock NFA oracle on port 8888..." ;
  ignore (Thread.create start_mock_server ()) ;
  Thread.delay 0.2

module NTeacher = MakeSocketNFATeacher (S)
module NetworkNLstar = NLstarLearner (NTeacher)
module NP = NFAPrinter (NTeacher)

let rec enumerate k : S.str list =
  if k <= 0 then
    [[]]
  else
    let prev = enumerate (k - 1) in
    Stdlib.List.concat_map
      (fun s -> Stdlib.List.map (fun c -> c :: s) S.enum)
      prev

let print_results name nfa k =
  Printf.printf "\n=== %s ===\n" name ;
  NP.print_nfa nfa ;
  let strings = enumerate k in
  let col_w = max 10 (k + 2) in
  Printf.printf "%-*s  %-8s  %-8s  %-8s\n" col_w "Input" "Expected" "Got"
    "Correct" ;
  let correct = ref 0 in
  Stdlib.List.iter
    (fun (c : S.str) ->
      let exp = language_member c
      and got = NTeacher.R.N.accept_string_dedup ( = ) nfa c in
      if exp = got then incr correct ;
      Printf.printf "%-*s  %-8b  %-8b  %s\n" col_w
        (Printf.sprintf "[%s]" (S.string_of_str c))
        exp got
        ( if exp = got then
            "Y"
          else
            "N" ) )
    strings ;
  Printf.printf "Accuracy: %d/%d\n" !correct (Stdlib.List.length strings)

let () =
  let rfsa = NetworkNLstar.nlstar () in
  let nfa = NTeacher.R.nfa rfsa in
  print_results "Isolated Network Test NL*" nfa (3 * (n + 2))
