From Stdlib Require Import Extraction ExtrOcamlNativeString.
From Stdlib Require Import Bool List PeanoNat String.

Extract Inductive nat => int [ "0" "succ" ]
   "(fun fO fS n -> if n=0 then fO () else fS (n-1))".
Extract Inductive bool => "bool" [ "true" "false" ].
Extract Inductive list => "list" [ "[]" "(::)" ].
Extract Inductive prod => "(*)"  [ "(,)" ].
Extract Inductive option => "option" ["Some" "None"].
Extract Inductive result => "result" ["Ok" "Error"].
Extract Inductive string => "Stdlib.String.t"
[

"(* If this appears, you're using String internals. Please don't *) """" "

"(* If this appears, you're using String internals. Please don't *) (fun (c, s) -> String.make 1 c ^ s) "
]
"(* If this appears, you're using String internals. Please don't *) (fun f0 f1 s -> let l = String.length s in if l = 0 then f0 else f1 (String.get s 0) (String.sub s 1 (l-1))) ".

Extract Inlined Constant Bool.eqb => "(=)".
Extract Inlined Constant negb => "not".
Extract Inlined Constant List.app => "(@)".
Extract Inlined Constant List.length => "Stdlib.List.length".
Extract Inlined Constant List.existsb => "Stdlib.List.exists".
Extract Inlined Constant List.forallb => "Stdlib.List.for_all".
Extract Inlined Constant List.find => "Stdlib.List.find_opt".
Extract Inlined Constant List.map => "Stdlib.List.map".
Extract Inlined Constant List.nth_error => "Stdlib.List.nth_opt".
Extract Constant Nat.pow =>
"let rec pow x n =
    if n = 0 then 1
    else x * pow x (n - 1)
   in pow".
