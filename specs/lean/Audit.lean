/-
Axiom audit for the headline theorems (skill guardrail, Rule 3).

Proofs phase: the three headline theorems are proved without `sorry` after G3
sign-off, so this audit must print only allowlisted axioms (or "does not depend
on any axioms").  Do not remove a line to hide a theorem.
-/
import Specs

#print axioms Hotp.inv1_same_matched_counter_accepted_twice
#print axioms Hotp.inv2_counter_not_ahead_of_all_matches
#print axioms Hotp.inv3_counter_can_decrease
