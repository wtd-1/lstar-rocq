open Lstar
open DFA
open Specif
open Teacher
open Stdlib

(** Alphabet *)
module S = struct
  type t = Zero | One

  let string_of_t = function Zero -> "0" | One -> "1"

  let eq_dec x y =
    if x = y then
      Coq_left
    else
      Coq_right

  let enum = [Zero; One]

  type string = t list

  let string_of_string s = String.concat "" (List.map string_of_t s)

  let str_eq (l1 : string) (l2 : string) =
    if
      List.length l1 = List.length l2
      && List.for_all (fun (x, y) -> x = y) (List.combine l1 l2)
    then
      Coq_left
    else
      Coq_right
end

(** Teacher: owns S and D, defines member and equiv_query against its own D *)
module Teacher : TEACHER with module S = S = struct
  module S = S
  module D = DFA (S)

  (** Language membership: the string is "alternating" *)
  let member (s : S.string) : bool =
    match s with
    | [] ->
        true
    | h :: t ->
        snd
          (List.fold_left
             (fun (last, mem) i -> (i, mem && last <> i))
             (h, true) t )

  (** Teacher equivalence query, typed against D.t (not a fresh DFA(S)) *)
  let equiv_query (dfa : 'a D.t) : S.string option =
    let rec find_counter_example depth current_strings =
      if depth >= int_of_float (2. ** 12.) then
        None
      else
        match current_strings with
        | [] ->
            None
        | s :: rest ->
            let dfa_acc = D.accept_string dfa s in
            let spec_acc = member s in
            if dfa_acc <> spec_acc then
              Some s
            else
              let next_gen = List.map (fun c -> s @ [c]) [S.Zero; S.One] in
              find_counter_example (depth + 1) (rest @ next_gen)
    in
    find_counter_example 0 [[]]
end

(** L* implementation *)
module Lstar = LstarLearner (Teacher)

(** Kearns-Vazirani (discrimination-tree) implementation *)
module KV = KVLearner (Teacher)

(** Generate all bit strings of length [n] *)
let rec enumerate (n : int) : S.string list =
  if n <= 0 then
    [[]]
  else
    let prev = enumerate (n - 1) in
    let prepend c l = List.map (fun s -> [c] @ s) l in
    prepend S.Zero prev @ prepend S.One prev

(** Run the DFA on test cases and pretty-print the results *)
let print_results dfa n =
  let strings = enumerate n in
  let col_w = max 10 (n + 2) in
  let header =
    Printf.sprintf "%-*s  %-8s  %-8s  %-8s" col_w "Input" "Expected" "Got"
      "Correct"
  in
  print_endline header ;
  List.iter
    (fun (c : S.string) ->
      let exp = Teacher.member c in
      let comp = Teacher.D.accept_string dfa c in
      Printf.printf "%-*s  %-8b  %-8b  %s\n" col_w
        (Printf.sprintf "[%s]" (S.string_of_string c))
        exp comp
        ( if exp = comp then
            "Y"
          else
            "N" ) )
    strings ;
  let correct =
    List.length
      (List.filter
         (fun (c : S.string) -> Teacher.member c = Teacher.D.accept_string dfa c)
         strings )
  in
  Printf.printf "Accuracy: %d/%d\n" correct (List.length strings)

(** Run one learner, reporting its result *)
let run_learner name result =
  Printf.printf "\n=== %s ===\n" name ;
  match result with
  | Error _ ->
      print_endline "No DFA found"
  | Ok (Coq_existT (_, d)) ->
      print_endline "DFA found" ; print_results d 3

(** Main: learn the language with both L* and KV, then test each *)
let () =
  run_learner "L*"
    (Lstar.lstar_opt Int.max_int
       { coq_Q= (fun x -> x = [])
       ; coq_T= (fun x -> x = [])
       ; clos= (fun _ _ _ -> [])
       ; fin_Q= [[]]
       ; fin_T= [[]] } ) ;
  run_learner "KV" (KV.kv_run Int.max_int)
