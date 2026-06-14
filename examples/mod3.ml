open Lstar
open DFA
open Specif
open Teacher
open Stdlib

module S = struct
  type t = Zero | One

  let string_of_t = function Zero -> "0" | One -> "1"

  let eq_dec x y = x = y

  let enum = [Zero; One]

  type string = t list

  let string_of_string s = String.concat "" (List.map string_of_t s)

  let str_eq (l1 : string) (l2 : string) =
    List.length l1 = List.length l2
    && List.for_all (fun (x, y) -> x = y) (List.combine l1 l2)
end

(** Language: strings over {0,1} where the number of 1s is divisible by 3.
    The minimal DFA has exactly 3 states (one per residue class mod 3),
    so L* must discover a nontrivial 3-state machine. *)
module Teacher : TEACHER with module S = S = struct
  module S = S
  module D = DFA (S)

  let member (s : S.string) : bool =
    let count =
      List.fold_left
        (fun acc c ->
          if c = S.One then
            acc + 1
          else
            acc )
        0 s
    in
    count mod 3 = 0

  let equiv_query (dfa : 'a D.t) : S.string option =
    let rec bfs depth queue =
      if depth >= 4096 then
        None
      else
        match queue with
        | [] ->
            None
        | s :: rest ->
            if D.accept_string dfa s <> member s then
              Some s
            else
              bfs (depth + 1)
                (rest @ List.map (fun c -> s @ [c]) [S.Zero; S.One])
    in
    bfs 0 [[]]
end

module Lstar = LstarLearner (Teacher)

(** Kearns-Vazirani (discrimination-tree) implementation *)
module KV = KVLearner (Teacher)

let rec enumerate n =
  if n <= 0 then
    [[]]
  else
    let prev = enumerate (n - 1) in
    List.map (fun s -> S.Zero :: s) prev @ List.map (fun s -> S.One :: s) prev

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
      Lstar.print_dfa d ; print_endline "DFA found" ; print_results d 4

let () =
  run_learner "L*"
    (Lstar.lstar_opt Int.max_int
       { coq_Q= (fun x -> x = [])
       ; coq_T= (fun x -> x = [])
       ; clos= (fun _ _ _ -> [])
       ; fin_Q= [[]]
       ; fin_T= [[]] } ) ;
  run_learner "KV" (KV.kv_run Int.max_int)
