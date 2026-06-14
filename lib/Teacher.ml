open Lstar
open KV
open DFA

module type TEACHER = sig
  module S : Symbol

  module D : module type of DFA (S)

  val member : S.string -> bool

  val equiv_query : 'a D.t -> S.string option
end

module LstarLearner (T : TEACHER) = struct
  module Impl =
    Lstar
      (T.S)
      (struct
        module D = T.D

        let member = T.member
      end)
      (struct
        let equiv_query = T.equiv_query
      end)

  include Impl
end

module KVLearner (T : TEACHER) = struct
  module Impl =
    KV
      (T.S)
      (struct
        module D = T.D

        let member = T.member
      end)
      (struct
        let equiv_query = T.equiv_query
      end)

  include Impl
end
