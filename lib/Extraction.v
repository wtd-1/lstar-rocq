From Stdlib Require Import Extraction.

From lstar Require Import Lstar KV DFA.

Extraction Language OCaml.

(* Comment this line to turn off standard OCaml types *)
From lstar Require Import ExtrOptimizations.

(* Linear let + beta reduction *)
Set Extraction Flag 1536.

Separate Extraction Lstar KV.
