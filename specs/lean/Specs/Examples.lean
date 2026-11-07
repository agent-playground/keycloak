import Specs.Model

/-!
Non-vacuity witnesses for the headline statements (proofs phase).

The witness traces and states below are unchanged from the G3-signed statements
phase; the proofs in `Specs/Proofs.lean` import this module and discharge the
headline existentials with these concrete executions.

These are *concrete executions*, not proofs of the quotients.  The witness
traces are ordered so that each request loads its cached snapshot **before**
the first commit; this is the faithful ordering for the stale-snapshot
hypothesis (RULES.md ENV-5 / ENV-6), and it avoids relying on a read that
happens after the counter has already moved.

The witness state is described by the `run` result, so the examples are
machine-checked computations of the model.  `Specs/Proofs.lean` assembles
`Reachable`/`StepRel` from these concrete facts.
-/
namespace Hotp

/-- Both requests load snapshot 0, both validate `matched = 1`, and both commit.
The second commit writes `1 + 1 = 2` again, so the persisted counter advances
only once while two `isValid` calls returned `true` for counter 1. -/
def traceInv1 : List Action :=
  [ .read 1 0, .validate 1 1, .read 0 0, .validate 0 1, .commit 0, .commit 1 ]

/-- State after `traceInv1`. -/
def finalInv1 : State where
  counter := 2
  history := [0, 2, 2]
  reqs := [.done 1, .done 1]
  successes := [{ request := 0, matched := 1 }, { request := 1, matched := 1 }]

/--
Both requests load snapshot 0 before either commit.  Request 0 matches counter
1 and writes counter 2.  Request 1 still holds snapshot 0, matches counter 0
(inside the window `0..1`), and writes `0 + 1 = 1`, lowering the persisted
counter from 2 to 1.
-/
def traceInv2 : List Action :=
  [ .read 0 0, .read 1 0, .validate 0 1, .commit 0, .validate 1 0, .commit 1 ]

/-- State after `traceInv2`: counter 1, successes `(req0, 1)` and `(req1, 0)`. -/
def finalInv2 : State where
  counter := 1
  history := [0, 2, 1]
  reqs := [.done 1, .done 0]
  successes := [{ request := 0, matched := 1 }, { request := 1, matched := 0 }]

/-- `traceInv2` without the final (lowering) commit. -/
def traceInv3Before : List Action :=
  [ .read 0 0, .read 1 0, .validate 0 1, .commit 0, .validate 1 0 ]

/-- State just before the lowering commit: counter 2, request 1 validated at 0. -/
def beforeInv3 : State where
  counter := 2
  history := [0, 2]
  reqs := [.done 1, .validated 0 0]
  successes := [{ request := 0, matched := 1 }]

-- INV-1 witness: the two successful requests share `matched = 1`.
example : run defaultConfig (initialState defaultConfig) traceInv1 = some finalInv1 := by
  decide

example : hasReusedMatchedCounter finalInv1 = true := by
  decide

-- INV-2 witness: counter 1 is not strictly above the accepted `matched = 1`.
example : run defaultConfig (initialState defaultConfig) traceInv2 = some finalInv2 := by
  decide

example : counterAheadOfAllMatches finalInv2 = false := by
  decide

-- INV-3 witness: the final commit is a real model transition that lowers the counter.
example : run defaultConfig (initialState defaultConfig) traceInv3Before = some beforeInv3 := by
  decide

example : step defaultConfig beforeInv3 (.commit 1) = some finalInv2 := by
  decide

example : finalInv2.counter < beforeInv3.counter := by
  decide

end Hotp
