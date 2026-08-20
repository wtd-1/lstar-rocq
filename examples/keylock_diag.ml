(* Scratch diagnostic, not a permanent example: local (no socket, no Java) L*
   against a chain/keylock-shaped DFA of parametric size, to get a clean
   empirical growth curve isolated from any bridge/network confound.
   Counts membership queries directly and times each size. Delete once the
   growth-curve question is answered. *)

open DFA
open Specif
open Teacher
open Stdlib

module S = struct
  type t = A

  let string_of_t = function A -> "a"

  let t_of_string : string -> (t, string) Datatypes.result = function
    | "a" -> Ok A
    | _ -> Error "t_of_string"

  let eq_dec _ _ = true

  let enum = [A]

  type str = t list

  let string_of_str s = String.concat "" (List.map string_of_t s)
end

let query_count = ref 0

(* Chain of length n: accept iff the word has length exactly n-1 (i.e. depth
   saturates at n-1 and only THAT depth accepts) -- mirrors ExampleKeylock's
   non-cyclical shape: q0 -> q1 -> ... -> q(n-1), only q(n-1) accepts, no
   self-loop back. *)
let make_member n (s : S.str) : bool = List.length s = n - 1

module DTeacher (N : sig
  val n : int
end) : DFATEACHER with module S = S = struct
  module S = S
  module D = DFA (S)

  let member (s : S.str) : bool =
    incr query_count ;
    make_member N.n s

  let equiv_query (dfa : 'a D.t) : S.str option =
    let rec bfs depth queue =
      if depth > N.n + 2 then None
      else
        match queue with
        | [] -> None
        | s :: rest ->
            incr query_count ;
            if D.accept_string dfa s <> make_member N.n s then Some s
            else bfs (depth + 1) (rest @ [s @ [S.A]])
    in
    bfs 0 [[]]

  let fuel = Int.max_int
end

let run n =
  query_count := 0 ;
  let module N = struct
    let n = n
  end in
  let module T = DTeacher (N) in
  let module L = LstarLearner (T) in
  let start = Unix.gettimeofday () in
  let _dfa = L.lstar () in
  let elapsed = Unix.gettimeofday () -. start in
  Printf.printf "n=%-4d queries=%-10d time=%.3fs\n%!" n !query_count elapsed

let () = List.iter run [5; 10; 15; 20; 22; 24; 26; 28; 30]
