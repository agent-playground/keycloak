import Specs.Examples

/-!
Headline theorem proofs for the G2-selected counterexamples
(`inv1-default`, `inv2-default`, `inv3-default`).

Phase: **proofs** (ADR-029 §5), after G3 sign-off recorded in
`specs/lean/G3-SIGNOFF.md` (philipz, 2026-11-07T14:30:12.030Z, approved).

The statements below are byte-identical to the G3-signed statements phase; only
the proof bodies changed from `sorry` to kernel-checked terms.  Each proof is
built from the concrete witness traces in `Specs/Examples.lean`, so the
existentials are discharged by an explicit reachable execution rather than by
an axiomatic shortcut.

Do not weaken these statements to make a proof pass.
-/
namespace Hotp

/--
**INV-1 violation** (RULES.md INV-1; G2 `inv1-default`).

The as-is HOTP path reaches a state in which two distinct requests have both
been accepted (`isValid == true`, hence both entries in `successes`) for the
same `matched` counter value.  This is exactly the reuse of one HOTP counter
that INV-1 forbids; the model is `hotpDefault` (two requests, window 1).

Source: `OTPCredentialProvider.java:102-115` (read, validate, unconditional
write), `HmacOTP.java:82-93` (window scan), `JpaUserCredentialStore.java:63-76`
(no compare-and-set).
-/
theorem inv1_same_matched_counter_accepted_twice :
    ∃ s : State, Reachable defaultConfig s ∧ hasReusedMatchedCounter s = true := by
  refine ⟨finalInv1, ?_, ?_⟩
  · exact ⟨traceInv1, by decide⟩
  · decide

/--
**INV-2 violation** (RULES.md INV-2; G2 `inv2-default`).

The as-is HOTP path reaches a state in which some accepted `matched` counter
is not strictly below the persisted counter, i.e. `counter ≤ matched`.  A
stale write can therefore leave an old code inside the future acceptance
window.

Source: `OTPCredentialProvider.java:109-115` (write `matched + 1` with no
compare-and-set), `HmacOTP.java:82-93` (acceptance starts at the persisted
counter).
-/
theorem inv2_counter_not_ahead_of_all_matches :
    ∃ s : State, Reachable defaultConfig s ∧ counterAheadOfAllMatches s = false := by
  refine ⟨finalInv2, ?_, ?_⟩
  · exact ⟨traceInv2, by decide⟩
  · decide

/--
**INV-3 violation** (RULES.md INV-3; G2 `inv3-default`).

The as-is HOTP path has a reachable transition that lowers the persisted
counter: `s` is reachable, `step` takes `s` to `t`, and `t.counter < s.counter`.
This is the monotonicity violation; it does not rely on a ghost `maxCounter`
field.

Source: `OTPCredentialProvider.java:109-115`, `JpaUserCredentialStore.java:63-76`
(the write overwrites `credentialData` with the request's stale value).
-/
theorem inv3_counter_can_decrease :
    ∃ s t : State,
      Reachable defaultConfig s ∧ StepRel defaultConfig s t ∧ t.counter < s.counter := by
  refine ⟨beforeInv3, finalInv2, ?_, ?_, ?_⟩
  · exact ⟨traceInv3Before, by decide⟩
  · exact ⟨Action.commit 1, by decide⟩
  · decide

end Hotp
