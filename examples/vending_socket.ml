(* Coin-operated vending machine, over a socket: self-contained smoke test
   for the ["mq_mealy"]/["eq_mealy"] wire format (membership query sends the
   whole [prefix @ [sym]] word, reply is the last step's output symbol;
   equivalence query's transitions carry a per-transition output, not
   per-state). Same target as vending.ml. *)

open Specif
open Teacher
open SocketTeacher
open Unix

module S = struct
  type t = Nickel | Dime | Quarter | Refund

  let string_of_t = function
    | Nickel ->
        "n"
    | Dime ->
        "d"
    | Quarter ->
        "q"
    | Refund ->
        "r"

  let t_of_string : string -> (t, string) Datatypes.result = function
    | "n" ->
        Ok Nickel
    | "d" ->
        Ok Dime
    | "q" ->
        Ok Quarter
    | "r" ->
        Ok Refund
    | _ ->
        Error "t_of_string"

  let eq_dec x y = x = y

  let enum = [Nickel; Dime; Quarter; Refund]

  type str = t list

  let string_of_str s = String.concat "" (Stdlib.List.map string_of_t s)
end

module O = struct
  type t = Nothing | Vend of int | Coins of int

  let string_of_t = function
    | Nothing ->
        "-"
    | Vend 0 ->
        "VEND"
    | Vend c ->
        Printf.sprintf "VEND+%d" c
    | Coins c ->
        Printf.sprintf "BACK %d" c

  let t_of_string : string -> (t, string) Datatypes.result = function
    | "-" ->
        Ok Nothing
    | "VEND" ->
        Ok (Vend 0)
    | s when String.length s > 5 && String.sub s 0 5 = "VEND+" ->
        Ok (Vend (int_of_string (String.sub s 5 (String.length s - 5))))
    | s when String.length s > 5 && String.sub s 0 5 = "BACK " ->
        Ok (Coins (int_of_string (String.sub s 5 (String.length s - 5))))
    | _ ->
        Error "t_of_string"

  let eq_dec x y = x = y

  type str = t list

  let enum =
    [Nothing]
    @ Stdlib.List.map (fun c -> Vend c) [0; 5; 10; 15; 20]
    @ Stdlib.List.map (fun c -> Coins c) [5; 10; 15; 20; 25]
end

let price = 30

let value = function
  | S.Nickel ->
      5
  | S.Dime ->
      10
  | S.Quarter ->
      25
  | S.Refund ->
      0

let credit (s : S.str) : int =
  Stdlib.List.fold_left
    (fun c -> function
      | S.Refund ->
          0
      | i ->
          let c' = c + value i in
          if c' >= price then
            0
          else
            c' )
    0 s

let output_lang (s : S.str) (a : S.t) : O.t =
  let c = credit s in
  match a with
  | S.Refund ->
      if c > 0 then
        O.Coins c
      else
        O.Nothing
  | i ->
      let c' = c + value i in
      if c' >= price then
        O.Vend (c' - price)
      else
        O.Nothing

let unsnoc (s : S.str) : (S.str * S.t) option =
  match Stdlib.List.rev s with
  | [] ->
      None
  | a :: rprefix ->
      Some (Stdlib.List.rev rprefix, a)

let extract_int_field field json =
  let re = Str.regexp (Printf.sprintf {|"%s"[ \t]*:[ \t]*\([0-9]+\)|} field) in
  try
    ignore (Str.search_forward re json 0) ;
    int_of_string (Str.matched_group 1 json)
  with Not_found -> 0

(** [{"from":F,"input":"S","to":T,"output":"O"}] entries, as
    {!SocketTeacher.MakeProtocolMealyTeacher}'s serializer emits. *)
let parse_transitions json =
  let re =
    Str.regexp
      {|{"from":[ \t]*\([0-9]+\),[ \t]*"input":[ \t]*"\([^"]+\)",[ \t]*"to":[ \t]*\([0-9]+\),[ \t]*"output":[ \t]*"\([^"]*\)"}|}
  in
  let rec find pos acc =
    match Str.search_forward re json pos with
    | _ ->
        let from_id = int_of_string (Str.matched_group 1 json) in
        let input = Str.matched_group 2 json in
        let to_id = int_of_string (Str.matched_group 3 json) in
        let output = Str.matched_group 4 json in
        find (Str.match_end ()) ((from_id, input, to_id, output) :: acc)
    | exception Not_found ->
        acc
  in
  find 0 []

let is_membership_query line =
  match Str.search_forward (Str.regexp {|"type"[ \t]*:[ \t]*"mq_mealy"|}) line 0 with
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
      (function
        | "n" -> Some S.Nickel
        | "d" -> Some S.Dime
        | "q" -> Some S.Quarter
        | "r" -> Some S.Refund
        | _ -> None )
      (String.split_on_char ',' raw)

(** Deterministic run that returns the *last* transition's output -- the one
    step [output_lang prefix a] answers for -- not the final state (Mealy has
    no per-state output at all). *)
let hyp_last_output (initial : int)
    (transitions : (int * string * int * string) list) (word : S.str) :
    string option =
  let step state sym =
    let sym_s = S.string_of_t sym in
    Stdlib.List.find_opt (fun (f, i, _, _) -> f = state && i = sym_s) transitions
  in
  let rec go state = function
    | [] ->
        None
    | [sym] -> (
      match step state sym with
      | Some (_, _, _, out) ->
          Some out
      | None ->
          None )
    | sym :: rest -> (
      match step state sym with
      | Some (_, _, dst, _) ->
          go dst rest
      | None ->
          None )
  in
  go initial word

let find_counterexample ~max_len (initial : int)
    (transitions : (int * string * int * string) list) : string option =
  let rec bfs = function
    | [] ->
        None
    | word :: rest ->
        if Stdlib.List.length word > max_len then
          None
        else
          match unsnoc word with
          | None ->
              bfs (rest @ Stdlib.List.map (fun c -> [c]) S.enum)
          | Some (prefix, a) ->
              let expected = O.string_of_t (output_lang prefix a) in
              let got = hyp_last_output initial transitions word in
              if got <> Some expected then
                Some (String.concat "," (Stdlib.List.map S.string_of_t word))
              else
                bfs (rest @ Stdlib.List.map (fun c -> word @ [c]) S.enum)
  in
  bfs (Stdlib.List.map (fun c -> [c]) S.enum)

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
          let word = word_of_json line in
          match unsnoc word with
          | None ->
              failwith "mq_mealy request with an empty word"
          | Some (prefix, a) ->
              reply (O.string_of_t (output_lang prefix a))
        else
          let initial = extract_int_field "initial_state" line in
          let transitions = parse_transitions line in
          match find_counterexample ~max_len:6 initial transitions with
          | None ->
              reply "NONE"
          | Some ce ->
              reply ce
    done
  with End_of_file -> close_in ic ; close_out oc ; Unix.close server

let () =
  print_endline "Starting mock Mealy oracle on port 8888..." ;
  ignore (Thread.create start_mock_server ()) ;
  Thread.delay 0.2

module MT = MakeSocketMealyTeacher (S) (O)
module NetworkLstar = MealyLstarLearner (MT)
module MP = MealyPrinter (MT)

let rec enumerate (n : int) : S.str list =
  if n <= 0 then
    [[]]
  else
    let prev = enumerate (n - 1) in
    let prepend c l = Stdlib.List.map (fun s -> [c] @ s) l in
    [[]] @ Stdlib.List.concat_map (fun c -> prepend c prev) S.enum

let dedup l =
  Stdlib.List.fold_left
    (fun acc x -> if Stdlib.List.mem x acc then acc else x :: acc)
    [] l
  |> Stdlib.List.rev

let print_results name m n =
  Printf.printf "\n=== %s ===\n" name ;
  MP.print_mealy m ;
  let strings =
    dedup (enumerate n) |> Stdlib.List.filter (fun (s : S.str) -> s <> [])
  in
  let col_w = max 12 (n + 2) in
  Printf.printf "%-*s  %-11s  %-11s  %-8s\n" col_w "Input" "Expected" "Got"
    "Correct" ;
  let correct = ref 0 in
  Stdlib.List.iter
    (fun (c : S.str) ->
      match unsnoc c with
      | None ->
          ()
      | Some (prefix, a) -> (
        let exp = output_lang prefix a in
        match c with
        | [] ->
            ()
        | hd :: tl ->
            let comp = MT.M.last_output m hd tl in
            if exp = comp then incr correct ;
            Printf.printf "%-*s  %-11s  %-11s  %s\n" col_w
              (Printf.sprintf "[%s]" (S.string_of_str c))
              (O.string_of_t exp) (O.string_of_t comp)
              ( if exp = comp then
                  "Y"
                else
                  "N" ) ) )
    strings ;
  Printf.printf "Accuracy: %d/%d\n" !correct (Stdlib.List.length strings)

let () =
  let m = NetworkLstar.mlstar () in
  print_results "Isolated Network Test Mealy-L*" m 3
