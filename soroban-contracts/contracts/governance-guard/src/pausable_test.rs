#![cfg(test)]

//! Unit tests for the circuit-breaker primitive itself. Integration-level
//! proof that the guards are wired into the right entrypoints — and, more
//! importantly, that they are *absent* from the recovery entrypoints — lives
//! in each host contract's own test module.
//!
//! Same harness shape as `test.rs`: this crate has no `#[contract]`, so every
//! call runs inside `env.as_contract(...)` against a bare stand-in host, which
//! is what gives `env.storage()` a current contract to work on.

use soroban_sdk::{
    contract, testutils::Address as _, testutils::Events as _, testutils::Ledger, Address, Env,
    String, Vec,
};

use crate::{
    get_pause_state, init_governance, is_paused, pause, paused_scopes, require_not_paused, unpause,
    GovernanceError, GovernanceInit, PauseState, ALL_SCOPES, MAX_PAUSE_DURATION,
    MAX_PAUSE_REASON_LEN, SCOPE_ATTESTATION, SCOPE_INTAKE, SCOPE_SETTLEMENT,
};

#[contract]
struct TestHost;

struct Ctx {
    env: Env,
    host: Address,
    signers: Vec<Address>,
}

fn setup() -> Ctx {
    let env = Env::default();
    env.mock_all_auths();
    let host = env.register(TestHost, ());

    let mut signers = Vec::new(&env);
    signers.push_back(Address::generate(&env));
    signers.push_back(Address::generate(&env));
    signers.push_back(Address::generate(&env));

    env.as_contract(&host, || {
        init_governance(
            &env,
            GovernanceInit {
                signers: signers.clone(),
                threshold: 2,
            },
        )
    })
    .unwrap();

    // Start from a non-zero clock so "before the pause" is expressible.
    set_time(&env, 1_000);

    Ctx { env, host, signers }
}

fn set_time(env: &Env, timestamp: u64) {
    env.ledger().with_mut(|l| l.timestamp = timestamp);
}

impl Ctx {
    fn signer(&self, i: u32) -> Address {
        self.signers.get_unchecked(i)
    }

    /// Most tests don't care about the reason, so this helper supplies an
    /// empty one; `pause_with_reason` is for the tests that do.
    fn pause(
        &self,
        caller: Address,
        scopes: u32,
        duration: u64,
    ) -> Result<PauseState, GovernanceError> {
        self.pause_with_reason(caller, scopes, duration, "")
    }

    fn pause_with_reason(
        &self,
        caller: Address,
        scopes: u32,
        duration: u64,
        reason: &str,
    ) -> Result<PauseState, GovernanceError> {
        let reason = String::from_str(&self.env, reason);
        self.env.as_contract(&self.host, || {
            pause(&self.env, caller, scopes, duration, reason)
        })
    }

    fn unpause(&self, caller: Address, scopes: u32) -> Result<u32, GovernanceError> {
        self.env
            .as_contract(&self.host, || unpause(&self.env, caller, scopes))
    }

    fn scopes(&self) -> u32 {
        self.env
            .as_contract(&self.host, || paused_scopes(&self.env))
    }

    fn state(&self) -> Option<PauseState> {
        self.env
            .as_contract(&self.host, || get_pause_state(&self.env))
    }

    fn guard(&self, scope: u32) -> Result<(), GovernanceError> {
        self.env
            .as_contract(&self.host, || require_not_paused(&self.env, scope))
    }
}

// ---------------------------------------------------------------------------
// Baseline
// ---------------------------------------------------------------------------

#[test]
fn nothing_is_paused_before_any_pause() {
    let ctx = setup();
    assert_eq!(ctx.scopes(), 0);
    assert!(ctx.state().is_none());
    assert_eq!(ctx.guard(ALL_SCOPES), Ok(()));
}

#[test]
fn scope_bits_are_distinct_and_all_scopes_covers_exactly_them() {
    // A duplicated or overlapping bit would silently make one scope pause
    // another — cheap to assert, catastrophic to get wrong.
    assert_eq!(SCOPE_INTAKE & SCOPE_SETTLEMENT, 0);
    assert_eq!(SCOPE_INTAKE & SCOPE_ATTESTATION, 0);
    assert_eq!(SCOPE_SETTLEMENT & SCOPE_ATTESTATION, 0);
    assert_eq!(
        ALL_SCOPES,
        SCOPE_INTAKE | SCOPE_SETTLEMENT | SCOPE_ATTESTATION
    );
}

// ---------------------------------------------------------------------------
// Scope isolation
// ---------------------------------------------------------------------------

#[test]
fn pausing_one_scope_leaves_the_others_open() {
    let ctx = setup();
    ctx.pause(ctx.signer(0), SCOPE_INTAKE, 3_600).unwrap();

    assert_eq!(
        ctx.guard(SCOPE_INTAKE),
        Err(GovernanceError::OperationPaused)
    );
    assert_eq!(ctx.guard(SCOPE_SETTLEMENT), Ok(()));
    assert_eq!(ctx.guard(SCOPE_ATTESTATION), Ok(()));
    assert_eq!(ctx.scopes(), SCOPE_INTAKE);
}

#[test]
fn a_multi_scope_pause_halts_every_named_scope() {
    let ctx = setup();
    ctx.pause(ctx.signer(0), SCOPE_INTAKE | SCOPE_ATTESTATION, 3_600)
        .unwrap();

    assert_eq!(
        ctx.guard(SCOPE_INTAKE),
        Err(GovernanceError::OperationPaused)
    );
    assert_eq!(
        ctx.guard(SCOPE_ATTESTATION),
        Err(GovernanceError::OperationPaused)
    );
    assert_eq!(ctx.guard(SCOPE_SETTLEMENT), Ok(()));
}

#[test]
fn a_guard_over_a_mask_trips_when_any_one_of_its_bits_is_paused() {
    let ctx = setup();
    ctx.pause(ctx.signer(0), SCOPE_SETTLEMENT, 3_600).unwrap();

    assert_eq!(
        ctx.guard(SCOPE_INTAKE | SCOPE_SETTLEMENT),
        Err(GovernanceError::OperationPaused)
    );
}

#[test]
fn is_paused_agrees_with_the_guard() {
    let ctx = setup();
    ctx.pause(ctx.signer(0), SCOPE_SETTLEMENT, 3_600).unwrap();

    let paused = ctx
        .env
        .as_contract(&ctx.host, || is_paused(&ctx.env, SCOPE_SETTLEMENT));
    assert!(paused);
    let open = ctx
        .env
        .as_contract(&ctx.host, || is_paused(&ctx.env, SCOPE_INTAKE));
    assert!(!open);
}

// ---------------------------------------------------------------------------
// Auto-expiry
// ---------------------------------------------------------------------------

#[test]
fn a_pause_lapses_on_its_own_with_no_unpause_transaction() {
    let ctx = setup();
    ctx.pause(ctx.signer(0), ALL_SCOPES, 3_600).unwrap();
    assert_eq!(
        ctx.guard(SCOPE_INTAKE),
        Err(GovernanceError::OperationPaused)
    );

    // Nobody calls anything. Only the clock moves.
    set_time(&ctx.env, 1_000 + 3_600);

    assert_eq!(ctx.guard(ALL_SCOPES), Ok(()));
    assert_eq!(ctx.scopes(), 0);
    assert!(ctx.state().is_none());
}

#[test]
fn a_pause_is_still_in_effect_one_second_before_its_deadline() {
    let ctx = setup();
    ctx.pause(ctx.signer(0), SCOPE_INTAKE, 3_600).unwrap();

    set_time(&ctx.env, 1_000 + 3_599);
    assert_eq!(
        ctx.guard(SCOPE_INTAKE),
        Err(GovernanceError::OperationPaused)
    );

    // `expires_at` is the first instant it is *not* in effect.
    set_time(&ctx.env, 1_000 + 3_600);
    assert_eq!(ctx.guard(SCOPE_INTAKE), Ok(()));
}

#[test]
fn expiry_holds_for_a_maximum_length_pause_too() {
    let ctx = setup();
    let state = ctx
        .pause(ctx.signer(0), ALL_SCOPES, MAX_PAUSE_DURATION)
        .unwrap();
    assert_eq!(state.expires_at, 1_000 + MAX_PAUSE_DURATION);

    set_time(&ctx.env, state.expires_at - 1);
    assert_eq!(
        ctx.guard(SCOPE_INTAKE),
        Err(GovernanceError::OperationPaused)
    );

    set_time(&ctx.env, state.expires_at);
    assert_eq!(ctx.guard(SCOPE_INTAKE), Ok(()));
}

#[test]
fn a_duration_past_the_cap_is_rejected() {
    let ctx = setup();
    let result = ctx.pause(ctx.signer(0), ALL_SCOPES, MAX_PAUSE_DURATION + 1);
    assert_eq!(result, Err(GovernanceError::InvalidPauseDuration));
    assert_eq!(ctx.scopes(), 0);
}

#[test]
fn a_zero_duration_is_rejected() {
    let ctx = setup();
    let result = ctx.pause(ctx.signer(0), ALL_SCOPES, 0);
    assert_eq!(result, Err(GovernanceError::InvalidPauseDuration));
    assert_eq!(ctx.scopes(), 0);
}

// ---------------------------------------------------------------------------
// Re-pausing and replacement
// ---------------------------------------------------------------------------

#[test]
fn re_pausing_replaces_scopes_and_restarts_the_clock() {
    let ctx = setup();
    ctx.pause(ctx.signer(0), SCOPE_INTAKE, 3_600).unwrap();

    set_time(&ctx.env, 1_000 + 1_800);
    let state = ctx.pause(ctx.signer(1), SCOPE_SETTLEMENT, 3_600).unwrap();

    // The narrower mask lifted INTAKE; the deadline moved to the new call.
    assert_eq!(state.scopes, SCOPE_SETTLEMENT);
    assert_eq!(state.expires_at, 1_000 + 1_800 + 3_600);
    assert_eq!(ctx.guard(SCOPE_INTAKE), Ok(()));
    assert_eq!(
        ctx.guard(SCOPE_SETTLEMENT),
        Err(GovernanceError::OperationPaused)
    );
}

#[test]
fn a_lapsed_pause_can_be_placed_again_by_a_present_admin() {
    // Auto-expiry bounds an *unattended* pause. It does not claim to stop a
    // signer who is present from re-arming, and this pins that reading down
    // so nobody later mistakes the guarantee for something stronger — the
    // custody guarantee is that recovery paths are never guarded at all.
    let ctx = setup();
    ctx.pause(ctx.signer(0), ALL_SCOPES, MAX_PAUSE_DURATION)
        .unwrap();

    set_time(&ctx.env, 1_000 + MAX_PAUSE_DURATION);
    assert_eq!(ctx.scopes(), 0);

    ctx.pause(ctx.signer(0), ALL_SCOPES, MAX_PAUSE_DURATION)
        .unwrap();
    assert_eq!(ctx.scopes(), ALL_SCOPES);
}

// ---------------------------------------------------------------------------
// Unpause
// ---------------------------------------------------------------------------

#[test]
fn unpause_clears_only_the_named_scopes_and_keeps_the_deadline() {
    let ctx = setup();
    let state = ctx.pause(ctx.signer(0), ALL_SCOPES, 3_600).unwrap();

    set_time(&ctx.env, 1_500);
    let remaining = ctx.unpause(ctx.signer(1), SCOPE_INTAKE).unwrap();

    assert_eq!(remaining, SCOPE_SETTLEMENT | SCOPE_ATTESTATION);
    assert_eq!(ctx.guard(SCOPE_INTAKE), Ok(()));
    assert_eq!(
        ctx.guard(SCOPE_SETTLEMENT),
        Err(GovernanceError::OperationPaused)
    );
    // Partial lift must not extend the freeze on what stays halted.
    assert_eq!(ctx.state().unwrap().expires_at, state.expires_at);
}

#[test]
fn unpausing_the_last_scope_removes_the_record() {
    let ctx = setup();
    ctx.pause(ctx.signer(0), SCOPE_INTAKE | SCOPE_SETTLEMENT, 3_600)
        .unwrap();

    assert_eq!(
        ctx.unpause(ctx.signer(0), SCOPE_INTAKE).unwrap(),
        SCOPE_SETTLEMENT
    );
    assert_eq!(ctx.unpause(ctx.signer(0), SCOPE_SETTLEMENT).unwrap(), 0);

    assert!(ctx.state().is_none());
    assert_eq!(ctx.guard(ALL_SCOPES), Ok(()));
}

#[test]
fn unpause_with_all_scopes_lifts_everything_at_once() {
    let ctx = setup();
    ctx.pause(ctx.signer(0), ALL_SCOPES, 3_600).unwrap();

    assert_eq!(ctx.unpause(ctx.signer(2), ALL_SCOPES).unwrap(), 0);
    assert_eq!(ctx.guard(ALL_SCOPES), Ok(()));
}

#[test]
fn unpause_of_a_scope_that_was_never_paused_is_a_no_op_on_the_rest() {
    let ctx = setup();
    ctx.pause(ctx.signer(0), SCOPE_INTAKE, 3_600).unwrap();

    assert_eq!(
        ctx.unpause(ctx.signer(0), SCOPE_ATTESTATION).unwrap(),
        SCOPE_INTAKE
    );
    assert_eq!(
        ctx.guard(SCOPE_INTAKE),
        Err(GovernanceError::OperationPaused)
    );
}

#[test]
fn unpause_with_nothing_paused_fails() {
    let ctx = setup();
    assert_eq!(
        ctx.unpause(ctx.signer(0), ALL_SCOPES),
        Err(GovernanceError::NotPaused)
    );
}

#[test]
fn unpause_after_auto_expiry_reports_not_paused_and_drops_the_stale_record() {
    let ctx = setup();
    ctx.pause(ctx.signer(0), ALL_SCOPES, 3_600).unwrap();
    set_time(&ctx.env, 1_000 + 3_600);

    assert_eq!(
        ctx.unpause(ctx.signer(0), ALL_SCOPES),
        Err(GovernanceError::NotPaused)
    );
    // Even the raw entry is gone, so nothing reading storage directly can
    // mistake the lapsed record for live state.
    let raw: Option<PauseState> = ctx.env.as_contract(&ctx.host, || {
        ctx.env
            .storage()
            .instance()
            .get(&crate::GovernanceDataKey::PauseState)
    });
    assert!(raw.is_none());
}

// ---------------------------------------------------------------------------
// Authorization (adversarial)
// ---------------------------------------------------------------------------

#[test]
fn a_non_signer_cannot_pause() {
    let ctx = setup();
    let outsider = Address::generate(&ctx.env);

    assert_eq!(
        ctx.pause(outsider, ALL_SCOPES, 3_600),
        Err(GovernanceError::NotASigner)
    );
    assert_eq!(ctx.scopes(), 0);
}

#[test]
fn a_non_signer_cannot_unpause() {
    let ctx = setup();
    ctx.pause(ctx.signer(0), ALL_SCOPES, 3_600).unwrap();
    let outsider = Address::generate(&ctx.env);

    assert_eq!(
        ctx.unpause(outsider, ALL_SCOPES),
        Err(GovernanceError::NotASigner)
    );
    // The pause survived the failed attempt.
    assert_eq!(ctx.scopes(), ALL_SCOPES);
}

#[test]
fn any_single_signer_may_pause_and_any_other_may_lift_it() {
    // Threshold is 2 for upgrades, but the breaker is deliberately
    // unilateral in both directions — an incident does not wait for a
    // quorum, and no responder can wedge a pause in place by going dark.
    let ctx = setup();
    let threshold = ctx
        .env
        .as_contract(&ctx.host, || crate::get_threshold(&ctx.env));
    assert_eq!(threshold, 2);

    ctx.pause(ctx.signer(2), ALL_SCOPES, 3_600).unwrap();
    assert_eq!(ctx.unpause(ctx.signer(0), ALL_SCOPES).unwrap(), 0);
}

#[test]
fn pause_before_governance_is_initialized_fails() {
    let env = Env::default();
    env.mock_all_auths();
    let host = env.register(TestHost, ());
    let caller = Address::generate(&env);

    let result = env.as_contract(&host, || {
        pause(&env, caller, ALL_SCOPES, 3_600, String::from_str(&env, ""))
    });
    assert_eq!(result, Err(GovernanceError::NotInitialized));
}

// ---------------------------------------------------------------------------
// Scope validation
// ---------------------------------------------------------------------------

#[test]
fn an_empty_scope_mask_is_rejected_rather_than_silently_pausing_nothing() {
    let ctx = setup();
    assert_eq!(
        ctx.pause(ctx.signer(0), 0, 3_600),
        Err(GovernanceError::InvalidPauseScope)
    );
    assert_eq!(
        ctx.unpause(ctx.signer(0), 0),
        Err(GovernanceError::InvalidPauseScope)
    );
}

#[test]
fn an_unknown_scope_bit_is_rejected() {
    let ctx = setup();
    let bogus = 1 << 31;
    assert_eq!(
        ctx.pause(ctx.signer(0), bogus, 3_600),
        Err(GovernanceError::InvalidPauseScope)
    );
    // Even mixed in with a valid bit — a partially-typo'd mask is still a
    // mask the operator did not mean.
    assert_eq!(
        ctx.pause(ctx.signer(0), SCOPE_INTAKE | bogus, 3_600),
        Err(GovernanceError::InvalidPauseScope)
    );
}

// ---------------------------------------------------------------------------
// State record & events
// ---------------------------------------------------------------------------

#[test]
fn the_pause_record_carries_who_paused_and_the_window() {
    let ctx = setup();
    let signer = ctx.signer(1);
    let state = ctx.pause(signer.clone(), SCOPE_INTAKE, 7_200).unwrap();

    assert_eq!(state.scopes, SCOPE_INTAKE);
    assert_eq!(state.paused_by, signer);
    assert_eq!(state.paused_at, 1_000);
    assert_eq!(state.expires_at, 8_200);
    assert_eq!(ctx.state(), Some(state));
}

#[test]
fn pause_and_unpause_each_emit_an_event() {
    // `env.events().all()` reports the events of the most recent top-level
    // invocation, so each call is checked right after it happens rather than
    // against a running total.
    let ctx = setup();

    ctx.pause(ctx.signer(0), ALL_SCOPES, 3_600).unwrap();
    assert_eq!(ctx.env.events().all().events().len(), 1);

    ctx.unpause(ctx.signer(0), ALL_SCOPES).unwrap();
    assert_eq!(ctx.env.events().all().events().len(), 1);
}

#[test]
fn a_rejected_pause_emits_nothing() {
    let ctx = setup();

    let _ = ctx.pause(ctx.signer(0), ALL_SCOPES, MAX_PAUSE_DURATION + 1);
    assert_eq!(ctx.env.events().all().events().len(), 0);

    let _ = ctx.pause(Address::generate(&ctx.env), ALL_SCOPES, 3_600);
    assert_eq!(ctx.env.events().all().events().len(), 0);

    let _ = ctx.pause(ctx.signer(0), 0, 3_600);
    assert_eq!(ctx.env.events().all().events().len(), 0);
}

#[test]
fn a_rejected_unpause_emits_nothing_and_leaves_the_pause_standing() {
    let ctx = setup();
    ctx.pause(ctx.signer(0), ALL_SCOPES, 3_600).unwrap();

    let _ = ctx.unpause(Address::generate(&ctx.env), ALL_SCOPES);
    assert_eq!(ctx.env.events().all().events().len(), 0);
    assert_eq!(ctx.scopes(), ALL_SCOPES);
}

// ---------------------------------------------------------------------------
// Operator reason
// ---------------------------------------------------------------------------

#[test]
fn the_reason_round_trips_through_storage_and_the_event() {
    let ctx = setup();
    let state = ctx
        .pause_with_reason(ctx.signer(0), SCOPE_INTAKE, 3_600, "INC-412 accounting")
        .unwrap();

    // Checked before any further invocation: `events().all()` reports only
    // the most recent top-level call.
    assert_eq!(ctx.env.events().all().events().len(), 1);

    assert_eq!(
        state.reason,
        String::from_str(&ctx.env, "INC-412 accounting")
    );
    // Read back through the view, not just the return value — the round trip
    // through instance storage is the part that could silently drop it.
    assert_eq!(ctx.state().unwrap().reason, state.reason);
}

#[test]
fn an_empty_reason_is_allowed() {
    // The field must never stand between a responder and a halt.
    let ctx = setup();
    let state = ctx
        .pause_with_reason(ctx.signer(0), ALL_SCOPES, 3_600, "")
        .unwrap();
    assert_eq!(state.reason.len(), 0);
    assert_eq!(ctx.scopes(), ALL_SCOPES);
}

#[test]
fn a_reason_at_exactly_the_cap_is_accepted() {
    let ctx = setup();
    let at_cap = "0123456789012345678901234567890123456789012345678901234567890123";
    assert_eq!(at_cap.len() as u32, MAX_PAUSE_REASON_LEN);

    let state = ctx
        .pause_with_reason(ctx.signer(0), SCOPE_INTAKE, 3_600, at_cap)
        .unwrap();
    assert_eq!(state.reason.len(), MAX_PAUSE_REASON_LEN);
}

#[test]
fn a_reason_over_the_cap_is_rejected_and_places_no_pause() {
    let ctx = setup();
    let over_cap = "01234567890123456789012345678901234567890123456789012345678901234";
    assert_eq!(over_cap.len() as u32, MAX_PAUSE_REASON_LEN + 1);

    assert_eq!(
        ctx.pause_with_reason(ctx.signer(0), SCOPE_INTAKE, 3_600, over_cap),
        Err(GovernanceError::InvalidPauseReason)
    );
    assert_eq!(ctx.scopes(), 0);
    assert_eq!(ctx.env.events().all().events().len(), 0);
}

#[test]
fn replacing_a_pause_replaces_its_reason_too() {
    let ctx = setup();
    ctx.pause_with_reason(ctx.signer(0), SCOPE_INTAKE, 3_600, "first")
        .unwrap();
    ctx.pause_with_reason(ctx.signer(1), SCOPE_SETTLEMENT, 3_600, "second")
        .unwrap();

    assert_eq!(
        ctx.state().unwrap().reason,
        String::from_str(&ctx.env, "second")
    );
}

// ---------------------------------------------------------------------------
// Storage round-trip
// ---------------------------------------------------------------------------

#[test]
fn the_whole_pause_record_survives_a_storage_round_trip() {
    // `PauseState` is a `#[contracttype]` struct written to instance storage;
    // this pins every field against a serialization regression, not just the
    // ones the guards happen to read.
    let ctx = setup();
    let signer = ctx.signer(1);
    let returned = ctx
        .pause_with_reason(
            signer.clone(),
            SCOPE_INTAKE | SCOPE_ATTESTATION,
            7_200,
            "INC-9",
        )
        .unwrap();

    let raw: PauseState = ctx.env.as_contract(&ctx.host, || {
        ctx.env
            .storage()
            .instance()
            .get(&crate::GovernanceDataKey::PauseState)
            .unwrap()
    });

    assert_eq!(raw, returned);
    assert_eq!(raw.scopes, SCOPE_INTAKE | SCOPE_ATTESTATION);
    assert_eq!(raw.paused_by, signer);
    assert_eq!(raw.paused_at, 1_000);
    assert_eq!(raw.expires_at, 8_200);
    assert_eq!(raw.reason, String::from_str(&ctx.env, "INC-9"));
}

// ---------------------------------------------------------------------------
// Ordering & the "concurrent" case
// ---------------------------------------------------------------------------
//
// Soroban has no concurrency to race: transactions in a ledger are strictly
// ordered, and the host forbids re-entering a contract already on the call
// stack. "Two signers acting at once" is therefore always one after the
// other, and what matters is that the second observes the first's record
// rather than a stale copy. These pin that.

#[test]
fn a_second_signers_pause_observes_and_replaces_the_first() {
    let ctx = setup();
    ctx.pause_with_reason(ctx.signer(0), SCOPE_INTAKE, 3_600, "a")
        .unwrap();
    let second = ctx
        .pause_with_reason(ctx.signer(1), SCOPE_SETTLEMENT, 1_800, "b")
        .unwrap();

    assert_eq!(ctx.state().unwrap(), second);
    assert_eq!(second.paused_by, ctx.signer(1));
    assert_eq!(ctx.scopes(), SCOPE_SETTLEMENT);
}

#[test]
fn unpause_immediately_after_pause_in_the_same_ledger_leaves_nothing_halted() {
    let ctx = setup();
    ctx.pause(ctx.signer(0), ALL_SCOPES, 3_600).unwrap();
    assert_eq!(ctx.unpause(ctx.signer(1), ALL_SCOPES).unwrap(), 0);
    assert_eq!(ctx.scopes(), 0);

    // And a re-pause after that starts clean rather than resurrecting scopes.
    let state = ctx.pause(ctx.signer(2), SCOPE_INTAKE, 3_600).unwrap();
    assert_eq!(state.scopes, SCOPE_INTAKE);
    assert_eq!(ctx.scopes(), SCOPE_INTAKE);
}

#[test]
fn repeated_guard_consultations_within_one_ledger_all_agree() {
    // Expiry is evaluated per consultation, so the worry would be a call
    // observing "paused" once and "open" a moment later. The ledger timestamp
    // is fixed for the whole transaction, so it cannot.
    let ctx = setup();
    ctx.pause(ctx.signer(0), SCOPE_INTAKE, 3_600).unwrap();

    ctx.env.as_contract(&ctx.host, || {
        let first = require_not_paused(&ctx.env, SCOPE_INTAKE);
        let second = require_not_paused(&ctx.env, SCOPE_INTAKE);
        let third = paused_scopes(&ctx.env);
        assert_eq!(first, Err(GovernanceError::OperationPaused));
        assert_eq!(second, Err(GovernanceError::OperationPaused));
        assert_eq!(third, SCOPE_INTAKE);
    });

    // Cross the deadline and the same batch flips together, not piecemeal.
    set_time(&ctx.env, 1_000 + 3_600);
    ctx.env.as_contract(&ctx.host, || {
        assert_eq!(require_not_paused(&ctx.env, SCOPE_INTAKE), Ok(()));
        assert_eq!(require_not_paused(&ctx.env, ALL_SCOPES), Ok(()));
        assert_eq!(paused_scopes(&ctx.env), 0);
    });
}

// ---------------------------------------------------------------------------
// The reason cap is bytes, not characters
// ---------------------------------------------------------------------------
//
// `MAX_PAUSE_REASON_LEN` bounds the UTF-8 *byte* length, because that is what
// the storage entry actually costs and what `String::len()` reports. For
// ASCII the distinction is invisible, which is exactly why it needs pinning:
// a client that validates character count would happily submit a reason the
// contract rejects. These two tests are the executable statement of that.

/// 32 × U+00E9, two bytes each: 32 characters, exactly 64 bytes.
const REASON_32_CHARS_64_BYTES: &str = "éééééééééééééééééééééééééééééééé";

/// 33 × U+00E9: 33 characters — comfortably under a 64-*character* limit —
/// but 66 bytes, which is over the cap.
const REASON_33_CHARS_66_BYTES: &str = "ééééééééééééééééééééééééééééééééé";

#[test]
fn a_multibyte_reason_is_measured_in_bytes_and_accepted_at_exactly_the_cap() {
    let ctx = setup();
    let state = ctx
        .pause_with_reason(ctx.signer(0), SCOPE_INTAKE, 3_600, REASON_32_CHARS_64_BYTES)
        .unwrap();

    // The contract counts bytes: 32 characters weighing 64 bytes is exactly
    // at the cap, not half of it.
    assert_eq!(state.reason.len(), MAX_PAUSE_REASON_LEN);
    assert_eq!(REASON_32_CHARS_64_BYTES.chars().count(), 32);
    assert_eq!(REASON_32_CHARS_64_BYTES.len() as u32, MAX_PAUSE_REASON_LEN);
}

#[test]
fn a_multibyte_reason_over_the_byte_cap_is_rejected_despite_a_short_char_count() {
    let ctx = setup();

    assert_eq!(
        ctx.pause_with_reason(ctx.signer(0), SCOPE_INTAKE, 3_600, REASON_33_CHARS_66_BYTES),
        Err(GovernanceError::InvalidPauseReason)
    );
    // 33 characters would pass any character-count check; 66 bytes does not.
    assert_eq!(REASON_33_CHARS_66_BYTES.chars().count(), 33);
    assert_eq!(
        REASON_33_CHARS_66_BYTES.len() as u32,
        MAX_PAUSE_REASON_LEN + 2
    );
    assert_eq!(ctx.scopes(), 0);
}
