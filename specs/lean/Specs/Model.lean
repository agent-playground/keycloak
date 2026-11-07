/-
Lean model of the Keycloak HOTP credential-validation path (ADR-029 §5, phase ③ — statements).

Source of record: `specs/quint/RULES.md` (G1, confirmed) and
`specs/quint/TRIAGE.md` (G2, which selects `inv1-default`, `inv2-default`,
`inv3-default` for Lean).  This file *transcribes* that model; it does not
re-interview the owner and does not change a rule.

Scope (RULES.md ENV-1, ENV-2, ENV-6, ENV-8):

* one realm, one user, one HOTP credential;
* default deployment: user cache enabled, so a request may validate against a
  cached credential snapshot that is older than the persisted counter;
* `lookAheadWindow = 1`, `initialCounter = 0` (RULES.md ENV-1 / the
  `hotpDefault` Quint instance);
* two concurrent requests (`hotpDefault` N=2).

The model preserves the decisions that make the lost update possible:

* `read` loads a possibly stale `counter` snapshot into the request
  (`OTPCredentialProvider.java:102-104`, `UserAdapter.java:355-361`);
* `validate` scans `snapshot .. snapshot + window`
  (`HmacOTP.java:82-93`);
* `commit` overwrites the whole `credentialData` with `matched + 1` and has
  no compare-and-set on the counter (`OTPCredentialProvider.java:110-115`,
  `OTPCredentialModel.java:94-101`, `JpaUserCredentialStore.java:63-76`).

Dropped plumbing is listed in `MODEL-MAP.md` (abstraction ledger).
-/
namespace Hotp

/-- The policy / deployment parameters that matter to the transition relation.
`defaultConfig` is the Keycloak default HOTP deployment used by the G2 traces. -/
structure Config where
  /-- `OTPPolicy.lookAheadWindow` (RULES.md ENV-1). -/
  window : Nat
  /-- `OTPPolicy.initialCounter` (RULES.md ENV-1). -/
  initialCounter : Nat
  deriving DecidableEq, Repr

/-- `hotpDefault`: `lookAheadWindow = 1`, `initialCounter = 0` (RULES.md ENV-1). -/
def defaultConfig : Config := { window := 1, initialCounter := 0 }

/-- Request identity.  `hotpDefault` models exactly two concurrent requests. -/
abbrev ReqId := Fin 2

/-- The per-request state machine of `OTPCredentialProvider.isValid`.
`validated` and `done` remember the `matched` counter value, because
`isValid` writes `matched + 1` on success. -/
inductive Phase where
  | idle
  | read (snapshot : Nat)
  | validated (snapshot matched : Nat)
  | rejected
  | failed
  | done (matched : Nat)
  deriving DecidableEq, Repr

/-- A successful `isValid` event: which request returned `true`, and which
counter value its token matched.  This is the ghost history used by INV-1 and
INV-2; it records real return values, not a program field. -/
structure Success where
  request : ReqId
  matched : Nat
  deriving DecidableEq, Repr

/-- The state of one HOTP credential plus the two in-flight requests.

`counter` is the persisted `OTPCredentialData.counter`; `history` records every
value the persisted counter has held (so a cache snapshot must be one of them);
`reqs` is the local phase of each request (index `r.val`); `successes` is the
ghost history of `isValid == true` events (RULES.md ACT-9). -/
structure State where
  counter : Nat
  history : List Nat
  reqs : List Phase
  successes : List Success
  deriving DecidableEq, Repr

/-- Read the phase of request `r`; the default is unreachable and only keeps
the definition total. -/
def getPhase (s : State) (r : ReqId) : Phase :=
  s.reqs.getD r.val .idle

/-- Replace the phase of request `r`. -/
def setPhase (s : State) (r : ReqId) (p : Phase) : State :=
  { s with reqs := s.reqs.set r.val p }

/-- The initial state of `hotpDefault`: persisted counter 0, both requests idle. -/
def initialState (cfg : Config) : State :=
  { counter := cfg.initialCounter
    history := [cfg.initialCounter]
    reqs := [.idle, .idle]
    successes := [] }

/-- An action is one step of one request.  This is the interleaving alphabet;
a trace is a list of actions. -/
inductive Action where
  /-- RULES.md ACT-1: empty challenge / credentialId is rejected. -/
  | reject (r : ReqId)
  /-- RULES.md ACT-2 / ENV-5 / ENV-6: load a possibly stale counter snapshot. -/
  | read (r : ReqId) (snapshot : Nat)
  /-- RULES.md ACT-3 / ENV-3: the token matches `matched` inside the window. -/
  | validate (r : ReqId) (matched : Nat)
  /-- RULES.md ACT-3 / ACT-4: the token is outside the window; no write. -/
  | fail (r : ReqId)
  /-- RULES.md ACT-4 / ACT-5 / ACT-6: write `matched + 1` unconditionally. -/
  | commit (r : ReqId)
  deriving DecidableEq, Repr

/-- One step of the as-is model.

The guard/replacement pairs below are the model's transcription of the Java
code; every dropped detail is recorded in `MODEL-MAP.md`.

* `read`: only `snapshot ≤ counter` **and** `snapshot ∈ history` is allowed.
  This is the conservative direction: a cache snapshot must be a value the
  persisted counter actually held (RULES.md ENV-5 / ENV-6), while a snapshot
  newer than the current persisted value is not needed for the G2 traces and
  is left out.
* `validate`: the abstraction of `validateHOTP`'s scan; the token is
  represented by the counter it matches (RULES.md ENV-9).
* `commit`: no `counter == snapshot` precondition and no compare-and-set;
  this is RULES.md ACT-5 / ACT-6. -/
def step (cfg : Config) (s : State) : Action → Option State
  | .reject r =>
      match getPhase s r with
      | .idle => some (setPhase s r .rejected)
      | _ => none
  | .read r snapshot =>
      match getPhase s r with
      | .idle =>
          if snapshot ≤ s.counter && s.history.elem snapshot then
            some (setPhase s r (.read snapshot))
          else
            none
      | _ => none
  | .validate r matched =>
      match getPhase s r with
      | .read snapshot =>
          if snapshot ≤ matched ∧ matched ≤ snapshot + cfg.window then
            some (setPhase s r (.validated snapshot matched))
          else
            none
      | _ => none
  | .fail r =>
      match getPhase s r with
      | .read _ => some (setPhase s r .failed)
      | _ => none
  | .commit r =>
      match getPhase s r with
      | .validated _ matched =>
          some
            { counter := matched + 1
              history := s.history ++ [matched + 1]
              reqs := (setPhase s r (.done matched)).reqs
              successes := s.successes ++ [{ request := r, matched := matched }] }
      | _ => none

/-- Execute a list of actions from a state. -/
def run (cfg : Config) (s : State) : List Action → Option State
  | [] => some s
  | a :: rest => (step cfg s a).bind (fun s' => run cfg s' rest)

/-- `s` is reachable from the initial state by some interleaving. -/
def Reachable (cfg : Config) (s : State) : Prop :=
  ∃ actions : List Action, run cfg (initialState cfg) actions = some s

/-- There is a single model transition from `s` to `t`. -/
def StepRel (cfg : Config) (s : State) (t : State) : Prop :=
  ∃ a : Action, step cfg s a = some t

/-- INV-1 as an executable check: two distinct requests were both accepted for
the same `matched` counter value.  `= true` is a violation of RULES.md INV-1
(the invariant is `hasReusedMatchedCounter s = false`). -/
def hasReusedMatchedCounter (s : State) : Bool :=
  s.successes.any fun x =>
    s.successes.any fun y =>
      (x.matched == y.matched) && (x.request != y.request)

/-- INV-2 as an executable check: every accepted `matched` value is strictly
below the persisted counter.  `= false` is a violation of RULES.md INV-2. -/
def counterAheadOfAllMatches (s : State) : Bool :=
  s.successes.all fun x => x.matched < s.counter

end Hotp
