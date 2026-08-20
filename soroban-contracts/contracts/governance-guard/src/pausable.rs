//! Emergency circuit breaker: scoped, self-expiring pausability shared by
//! every contract that already depends on this crate.
//!
//! The point of this module is to give an incident responder something
//! between "watch it drain" and "upgrade under fire" — a way to halt the
//! *class* of operation that is being abused, immediately, without an
//! upgrade and without ever taking a user's ability to get their own money
//! back.
//!
//! Three properties are load-bearing, and each one exists to close a
//! failure mode that a plain `bool paused` flag would leave open:
//!
//! 1. **Scoped, not global.** A pause names a set of [scopes](#scopes)
//!    rather than freezing the whole contract. Halting value *entering*
//!    escrow is a safety measure; halting value *leaving* it is a hostage
//!    situation. Only a scoped guard can express the first without the
//!    second.
//!
//! 2. **Fund-recovery paths are never guarded.** This is enforced by
//!    omission at the call sites, not by a runtime check here, so it is
//!    worth stating plainly: `cancel_appointment`, every dispute
//!    entrypoint, and the loyalty token's `transfer` / `transfer_from` /
//!    `burn` of already-held balances carry no [`require_not_paused`] call
//!    at all. There is no scope value — including [`ALL_SCOPES`] — that can
//!    reach them. A pause therefore cannot strand a balance that already
//!    exists; at worst it delays a *new* obligation from being created or
//!    settled.
//!
//! 3. **Time-bound, enforced on read.** A pause carries an `expires_at`
//!    ledger timestamp and is capped at [`MAX_PAUSE_DURATION`]. Expiry is
//!    evaluated every time the guard is consulted, so a pause lapses on its
//!    own — no unpause transaction, no live admin, no working key required.
//!    An abandoned pause is indistinguishable from no pause at all.
//!
//! ## What this deliberately does *not* promise
//!
//! An admin who is present and hostile can call [`pause`] again the moment
//! the previous window lapses, indefinitely. That is not a hole this module
//! can close — the same signer set can already replace the contract's
//! entire code through the upgrade flow, so any "unstoppable freeze"
//! argument that survives auto-expiry survives the upgrade path too. The
//! guarantee on offer is narrower and honest: *an unattended pause always
//! clears*, and *no pause of any duration, attended or not, can stop a user
//! from recovering funds they already own* (property 2 above). Property 2
//! is what makes the residual risk a liveness problem for new business
//! rather than a custody problem for existing balances.
//!
//! ## Authorization
//!
//! Pausing and unpausing are gated by [`require_signer`] — **any single
//! governance signer**, not the full M-of-N threshold. This mirrors
//! [`cancel_upgrade`](crate::cancel_upgrade), which is likewise unilateral,
//! and for the same reason: gathering a threshold takes time an incident
//! does not give you, and the action being authorized is bounded on every
//! axis that matters. A signer acting alone, or maliciously, can delay new
//! business for at most [`MAX_PAUSE_DURATION`] per call and cannot touch a
//! single existing balance. Requiring a threshold would buy no custody
//! safety and would cost the response window.
//!
//! Using the governance signer set rather than each contract's own single
//! `admin` is deliberate too: the signer set is M-of-N, survives the loss
//! of any one key, and is rotatable through the timelocked flow added in
//! #27 — [`propose_signer_rotation`](crate::propose_signer_rotation) →
//! [`approve_signer_rotation`](crate::approve_signer_rotation) →
//! [`execute_signer_rotation`](crate::execute_signer_rotation), documented
//! under "Signer rotation" in the crate root. An incident is exactly when a
//! single non-rotatable admin key is least trustworthy, and pause authority
//! should follow the identity that *can* be revoked. Note the asymmetry
//! this creates on purpose: rotating who may pause is timelocked and needs
//! threshold agreement, while exercising the pause is instant and
//! unilateral. Changing the guest list is a governance decision; pulling
//! the alarm is not.
//!
//! ## Scopes
//!
//! Scopes are a shared vocabulary across contracts so that off-chain
//! tooling can reason about a pause without a per-contract lookup table.
//! The bitmask is small on purpose — a scope only earns a bit if some
//! plausible incident wants it halted *independently* of the others.
//!
//! | Scope | Meaning | Guarded entrypoints |
//! |---|---|---|
//! | [`SCOPE_INTAKE`] | New value or new obligations entering the system | `escrow`: `create_appointment`, `create_milestone_escrow`, `add_milestone`; `loyalty-token`: `mint`; `loyalty-emissions`: `create_schedule` |
//! | [`SCOPE_SETTLEMENT`] | Discretionary happy-path payouts — *not* recovery | `escrow`: `confirm_completion`, `approve_milestone`, `release_milestone_funds`; `loyalty-emissions`: `claim` |
//! | [`SCOPE_ATTESTATION`] | Reputation writes | `reputation`: `submit_attestation` |
//!
//! A contract that has no entrypoint in some scope simply ignores a pause
//! naming it; pausing [`SCOPE_ATTESTATION`] on `escrow` is a well-formed
//! no-op rather than an error, so an operator can broadcast the same scope
//! mask to every contract during an incident without special-casing.
//!
//! ## What "enforced on read" means, precisely
//!
//! A **guard consultation** is one call to [`require_not_paused`],
//! [`is_paused`], [`paused_scopes`] or [`get_pause_state`]. Each one loads
//! the stored record and compares `env.ledger().timestamp()` against
//! `expires_at`; past the deadline it reports "not paused" and the stored
//! record is simply ignored. Nothing is written back, so the guard has no
//! side effects and a lapsed pause costs nothing to step over. The stale
//! record is overwritten by the next [`pause`] and removed by the next
//! [`unpause`].
//!
//! Two consequences worth being explicit about:
//!
//! * **A pause cannot outlive its deadline even if every key is lost.**
//!   Expiry needs no transaction, so there is no "someone must call
//!   `unpause`" step that could fail to happen.
//! * **Expiry cannot land mid-transaction.** `env.ledger().timestamp()` is
//!   the close time of the ledger the transaction executes in and is
//!   constant for the whole invocation, including across cross-contract
//!   calls. A single call can therefore never observe the guard as paused
//!   at one point and open at another — the decision is the same at every
//!   consultation within one transaction.
//!
//! ### Which clock, and what a validator could do with it
//!
//! `expires_at` is compared against `env.ledger().timestamp()` — Stellar's
//! ledger close time in seconds, agreed by SCP consensus and required to be
//! monotonically increasing, not a value any single validator picks. That
//! makes the manipulation surface small and, more importantly, *irrelevant
//! in the direction that would matter*: nudging the clock forward can only
//! end a pause sooner, and nudging it backward is not possible. There is no
//! clock manipulation that extends a halt or reaches a fund-recovery path,
//! because recovery paths consult no clock at all.
//!
//! Ledger sequence would have been the other option, and is what the
//! upgrade timelocks in the crate root use. Wall-clock seconds are the
//! better fit here: a pause duration is negotiated between humans during an
//! incident ("give us six hours"), and seconds say that directly, whereas a
//! ledger count only approximates it at an assumed close rate.
//!
//! ## Example flows
//!
//! Halt new business while an escrow bug is triaged, then bring it back in
//! stages. Every call below is one transaction from any single signer:
//!
//! ```text
//! pause(signer_a, SCOPE_INTAKE, 21_600, "INC-412 milestone accounting")
//!     → new bookings rejected with OperationPaused
//!     → refunds, disputes, existing settlements all still work
//!
//! pause(signer_a, SCOPE_INTAKE | SCOPE_SETTLEMENT, 86_400, "INC-412 widened")
//!     → replaces the record outright: both scopes halted, clock restarted
//!
//! unpause(signer_b, SCOPE_INTAKE)
//!     → bookings resume; settlement stays halted on the *original* deadline
//!
//! (no transaction at all)
//!     → at expires_at, settlement resumes on its own
//! ```
//!
//! **Broadcasting to several contracts.** The scope vocabulary is shared, so
//! an operator halting intake protocol-wide sends the same `pause(caller,
//! SCOPE_INTAKE, secs, reason)` to `escrow`, `loyalty-token` and
//! `loyalty-emissions` — and can include `reputation` in the same sweep even
//! though it has no intake entrypoint, since a scope a contract does not use
//! is a no-op rather than an error. The four are separate deployments with
//! separate storage, so this is N transactions, not one; they do not have to
//! land in the same ledger and nothing breaks if they land out of order or
//! if one fails, because each contract's guard reads only its own record.
//! `soroban-contracts/scripts/broadcast-pause.sh` does exactly this sweep.
//!
//! **What a pause does to accrued rights: nothing.** Vesting in
//! `loyalty-emissions` is a pure function of the ledger clock and keeps
//! accruing through a halt, so a blocked `claim` mints the identical amount
//! once it lifts. Escrowed balances are untouched and stay reachable by
//! their owner throughout via the unguarded recovery paths. Reputation
//! scores already recorded stay readable and keep decaying on schedule. In
//! every case a pause delays an *action*, never a *right*.
//!
//! ## Reentrancy
//!
//! Not a concern here, and worth saying why rather than leaving it to
//! inference. Soroban's host forbids reentrancy outright: a contract
//! already on the call stack cannot be re-entered, so there is no path by
//! which a guarded entrypoint could call out and be called back before its
//! own state writes land. [`pause`] and [`unpause`] additionally make no
//! cross-contract calls at all — they touch one instance-storage key and
//! publish an event — so they cannot yield control mid-update even in
//! principle. Transactions within a ledger are also strictly ordered, so
//! two signers acting "simultaneously" is really one after the other, and
//! the second simply observes the first's record.
//!
//! ### Why `SCOPE_SETTLEMENT` is not a fund trap
//!
//! Halting `confirm_completion` and `release_milestone_funds` does withhold
//! a payout the worker has arguably earned, so it deserves an explicit
//! argument rather than an assumption. Two things make it a delay and not a
//! seizure. First, `release_milestone_funds` is *permissionless* — anyone
//! may call it once the time-lock passes — which makes it precisely the
//! entrypoint an accounting bug would be drained through, so it has to be
//! haltable or the scope is decorative. Second, the pause changes no
//! party's power relative to the others: the client could already cancel a
//! `Funded` appointment for a full refund before any of this existed, and
//! either party could already force the funds into `resolve_dispute`. Both
//! of those paths stay open throughout a pause. The escrowed amount remains
//! reachable by whoever was entitled to it; only the *unilateral* release
//! button is on hold, and only until expiry.

use soroban_sdk::{contractevent, contracttype, Address, Env, String};

use crate::{require_signer, GovernanceDataKey, GovernanceError};

/// New value or new obligations entering the system: escrow deposits,
/// fresh milestones, minted supply, newly registered emission schedules.
///
/// This is the scope to reach for first in almost any incident — stopping
/// the bleeding means stopping new money from walking into a contract you
/// no longer trust, and it is the one scope that provably cannot strand
/// anything, since it only ever blocks value that has not yet arrived.
pub const SCOPE_INTAKE: u32 = 1 << 0;

/// Discretionary happy-path payouts: releasing escrowed funds to a worker,
/// claiming vested emissions.
///
/// Explicitly *not* the same thing as fund recovery — refunds, dispute
/// resolution and token transfers of already-held balances are unguarded
/// entirely and are unaffected by this or any other scope. See the module
/// notes on why halting settlement is a delay rather than a seizure.
pub const SCOPE_SETTLEMENT: u32 = 1 << 1;

/// Reputation writes — attestation submission and the score accumulation
/// that follows from it.
///
/// Separate from the value-bearing scopes because its incidents are
/// separate: a Sybil ring poisoning scores is a reason to stop accepting
/// attestations, and no reason at all to stop paying workers.
pub const SCOPE_ATTESTATION: u32 = 1 << 2;

/// Every scope this crate defines. Also the validation mask — [`pause`]
/// and [`unpause`] reject any bit outside it, so a typo'd or
/// future-versioned scope fails loudly instead of silently pausing nothing.
pub const ALL_SCOPES: u32 = SCOPE_INTAKE | SCOPE_SETTLEMENT | SCOPE_ATTESTATION;

/// Longest `reason` a [`pause`] call may carry, measured in **UTF-8 bytes**
/// — not characters.
///
/// The distinction is invisible for ASCII and load-bearing for everything
/// else: `String::len()` reports bytes, so a 33-character reason made of
/// two-byte code points is 66 bytes and is rejected, even though a
/// character-count check would have passed it. A client validating this
/// before submitting must count bytes (`Buffer.byteLength(s, 'utf8')` in
/// JS, `len(s.encode('utf-8'))` in Python) or restrict itself to ASCII.
/// Both boundaries are pinned by tests.
///
/// Bounded because the reason is written to instance storage, which every
/// subsequent invocation of the contract pays to load — an unbounded
/// operator-supplied string would make an incident note a permanent tax on
/// the hot path. 64 bytes is enough for an incident ticket reference or a
/// short phrase; anything longer belongs in the incident channel the
/// reference points at, not on-chain. An empty reason is allowed, so the
/// field never stands between a responder and a halt.
pub const MAX_PAUSE_REASON_LEN: u32 = 64;

/// Longest window a single [`pause`] call may set, in seconds. 7 days.
///
/// The cap is the whole reason a pause can't become a permanent freeze, so
/// it is chosen as the shortest duration that still comfortably covers a
/// real incident response — triage, patch, review, and an upgrade through
/// the M-of-N flow — over a weekend and across time zones. Anything longer
/// stops being "we are responding" and starts being "we have forgotten",
/// and forgetting is exactly the state auto-expiry exists to recover from.
pub const MAX_PAUSE_DURATION: u64 = 7 * 24 * 60 * 60;

/// The single active pause record.
///
/// Stored under [`GovernanceDataKey::PauseState`] in instance storage. Only
/// one record exists at a time: a second [`pause`] replaces the first
/// outright rather than layering, so the set of halted scopes and the
/// deadline are always readable from one place with no reconciliation.
#[contracttype]
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct PauseState {
    /// Bitmask of halted scopes — some combination of [`SCOPE_INTAKE`],
    /// [`SCOPE_SETTLEMENT`] and [`SCOPE_ATTESTATION`].
    pub scopes: u32,
    /// Ledger timestamp at which this pause stops being in effect. The
    /// guards evaluate this on every read, so no transaction is needed for
    /// it to take hold.
    pub expires_at: u64,
    /// The signer who placed the pause. Recorded for attribution during and
    /// after an incident; it grants no special rights — any signer may
    /// [`unpause`], not just this one, so a responder going offline mid
    /// incident cannot wedge the pause in place.
    pub paused_by: Address,
    /// Ledger timestamp the pause was placed at. Together with
    /// `expires_at` this makes the chosen duration recoverable from state
    /// alone, without correlating against the emitting transaction.
    pub paused_at: u64,
    /// Free-form operator context, at most [`MAX_PAUSE_REASON_LEN`] UTF-8
    /// *bytes* (not characters); may be empty. Carries no meaning to the
    /// contract — it is never parsed or compared — and exists so an on-call
    /// responder can answer "why is this halted?" from chain state alone,
    /// rather than from a chat log nobody can find at 3am.
    pub reason: String,
}

/// Emitted whenever a pause is placed or replaced.
///
/// Wire format, as consumed by an off-chain indexer — pinned by
/// `paused_event_has_the_documented_topics_and_data_shape` in the escrow
/// test suite, so it cannot drift from this comment silently:
///
/// * **Topics** (ordered): `Symbol("gov_pause")`, `Symbol("paused")`,
///   `Address(caller)`. The two prefix symbols come first, then each
///   `#[topic]` field in declaration order.
/// * **Data**: a `Map<Symbol, Val>` keyed by field name — `expires_at`
///   (`u64`), `reason` (`String`), `scopes` (`u32`). Because it is a map,
///   field *names* are part of the contract but their order is not.
///
/// `expires_at` is the absolute ledger timestamp the pause lapses at, not a
/// duration, so a monitor that missed the transaction can still tell how
/// much of the window is left without knowing when it started.
#[contractevent(topics = ["gov_pause", "paused"])]
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct Paused {
    #[topic]
    pub caller: Address,
    /// The full set of scopes halted after this call — the new state, not a
    /// delta against whatever was halted before.
    pub scopes: u32,
    pub expires_at: u64,
    /// Operator-supplied context; may be empty. See [`PauseState::reason`].
    pub reason: String,
}

/// Emitted when scopes are cleared early by a signer.
///
/// Wire format, pinned by
/// `unpaused_event_has_the_documented_topics_and_data_shape`:
///
/// * **Topics** (ordered): `Symbol("gov_pause")`, `Symbol("unpaused")`,
///   `Address(caller)`.
/// * **Data**: a `Map<Symbol, Val>` keyed by field name — `scopes` (`u32`),
///   `remaining_scopes` (`u32`).
///
/// Deliberately *not* emitted on auto-expiry: expiry is a read-time
/// evaluation with no transaction behind it, so there is no execution
/// context to emit from. Monitors get `expires_at` up front in [`Paused`]
/// and should treat it as the authoritative end of the window unless an
/// `Unpaused` arrives sooner.
#[contractevent(topics = ["gov_pause", "unpaused"])]
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct Unpaused {
    #[topic]
    pub caller: Address,
    /// The scopes this call cleared.
    pub scopes: u32,
    /// Scopes still halted afterwards — `0` when the pause is fully lifted.
    pub remaining_scopes: u32,
}

/// Rejects a scope mask that is empty or contains bits outside
/// [`ALL_SCOPES`].
///
/// Empty is rejected rather than treated as "no-op" because every call site
/// that could produce it — a miscomputed mask, a caller that meant
/// `ALL_SCOPES` — is a mistake the operator wants to hear about during an
/// incident, not a silently successful transaction that halted nothing.
fn validate_scopes(scopes: u32) -> Result<(), GovernanceError> {
    if scopes == 0 || scopes & !ALL_SCOPES != 0 {
        return Err(GovernanceError::InvalidPauseScope);
    }
    Ok(())
}

/// Halts `scopes` for `duration_secs` seconds from now, authorized by any
/// single governance signer.
///
/// Replaces any existing pause outright — the new `scopes` mask and the new
/// deadline both take effect, which means calling this with a *narrower*
/// mask lifts the scopes left out of it. To keep an existing deadline while
/// releasing part of it, use [`unpause`] instead; this call always restarts
/// the clock.
///
/// `reason` is free-form operator context of at most
/// [`MAX_PAUSE_REASON_LEN`] **UTF-8 bytes** — not characters, see that
/// constant — and may be empty. It is stored and emitted verbatim, never
/// interpreted. Over-length fails with
/// [`GovernanceError::InvalidPauseReason`] before anything is written.
///
/// Fails with [`GovernanceError::InvalidPauseDuration`] for a zero duration
/// or one exceeding [`MAX_PAUSE_DURATION`] — the cap is enforced here, at
/// the only place a deadline is ever written, so there is no path to a
/// longer window than the constant allows.
pub fn pause(
    env: &Env,
    caller: Address,
    scopes: u32,
    duration_secs: u64,
    reason: String,
) -> Result<PauseState, GovernanceError> {
    require_signer(env, &caller)?;
    validate_scopes(scopes)?;
    if duration_secs == 0 || duration_secs > MAX_PAUSE_DURATION {
        return Err(GovernanceError::InvalidPauseDuration);
    }
    if reason.len() > MAX_PAUSE_REASON_LEN {
        return Err(GovernanceError::InvalidPauseReason);
    }

    let now = env.ledger().timestamp();
    let state = PauseState {
        scopes,
        // `now + duration_secs` cannot overflow: `duration_secs` is capped
        // at MAX_PAUSE_DURATION above, and a u64 ledger timestamp within
        // ~584 billion years of the epoch leaves room for another week.
        expires_at: now + duration_secs,
        paused_by: caller.clone(),
        paused_at: now,
        reason: reason.clone(),
    };
    env.storage()
        .instance()
        .set(&GovernanceDataKey::PauseState, &state);

    Paused {
        caller,
        scopes,
        expires_at: state.expires_at,
        reason,
    }
    .publish(env);
    Ok(state)
}

/// Clears `scopes` from the active pause ahead of its deadline, authorized
/// by any single governance signer. Returns the scopes still halted
/// afterwards.
///
/// Masked rather than all-or-nothing so an operator can bring the system
/// back a piece at a time — resume intake while settlement stays halted —
/// without the alternative of re-calling [`pause`], which would reset the
/// deadline and *extend* the freeze on everything else. Clearing the last
/// scope removes the record entirely. Passing [`ALL_SCOPES`] is the "lift
/// everything now" call.
///
/// Fails with [`GovernanceError::NotPaused`] if nothing is currently
/// halted, including when a record exists but has already auto-expired —
/// an expired pause is not in effect, so there is nothing to lift. The
/// stale record is cleaned up in that case rather than left behind.
pub fn unpause(env: &Env, caller: Address, scopes: u32) -> Result<u32, GovernanceError> {
    require_signer(env, &caller)?;
    validate_scopes(scopes)?;

    let Some(mut state) = read_state(env) else {
        // Either nothing was ever set, or what is set has lapsed. Drop a
        // lapsed record so it can't be mistaken for live state by anything
        // reading storage directly.
        env.storage()
            .instance()
            .remove(&GovernanceDataKey::PauseState);
        return Err(GovernanceError::NotPaused);
    };

    state.scopes &= !scopes;
    let remaining_scopes = state.scopes;
    if remaining_scopes == 0 {
        env.storage()
            .instance()
            .remove(&GovernanceDataKey::PauseState);
    } else {
        env.storage()
            .instance()
            .set(&GovernanceDataKey::PauseState, &state);
    }

    Unpaused {
        caller,
        scopes,
        remaining_scopes,
    }
    .publish(env);
    Ok(remaining_scopes)
}

/// The guard host contracts call at the top of a pausable entrypoint.
///
/// `scope` is a mask; the call is rejected if *any* of its bits are
/// currently halted. Fails with [`GovernanceError::OperationPaused`], which
/// exists precisely so a paused call is distinguishable from a call that
/// was invalid on its own terms — reusing a status error here would make
/// "the protocol is halted" and "you asked for the wrong thing" report
/// identically to a client.
///
/// Read-only: expiry is evaluated, never written back. That keeps the guard
/// free of side effects on the hot path and means a lapsed pause costs
/// nothing to step over. The stale record is overwritten by the next
/// [`pause`] and removed by the next [`unpause`].
pub fn require_not_paused(env: &Env, scope: u32) -> Result<(), GovernanceError> {
    if paused_scopes(env) & scope != 0 {
        return Err(GovernanceError::OperationPaused);
    }
    Ok(())
}

/// The scopes currently halted, or `0` if none are — including when a
/// record exists but its deadline has passed.
///
/// This is the single place expiry is decided; everything else in this
/// module reads through it, so there is no way for one code path to
/// consider a pause live while another considers it lapsed.
pub fn paused_scopes(env: &Env) -> u32 {
    read_state(env).map(|s| s.scopes).unwrap_or(0)
}

/// Whether every bit in `scope` — or any of them, for a multi-bit mask — is
/// currently halted. Convenience mirror of [`require_not_paused`] for
/// read-only callers and off-chain tooling.
pub fn is_paused(env: &Env, scope: u32) -> bool {
    paused_scopes(env) & scope != 0
}

/// The active pause record, or `None` when nothing is in effect.
///
/// Returns `None` for a stored-but-expired record rather than handing back
/// a state the guards no longer honour: a view that disagrees with
/// [`require_not_paused`] is worse than no view at all.
pub fn get_pause_state(env: &Env) -> Option<PauseState> {
    read_state(env)
}

/// Loads the record and applies expiry. Uses `>=` against the deadline so
/// `expires_at` is the first instant the pause is *not* in effect, matching
/// how the value reads to an operator ("halted until T").
fn read_state(env: &Env) -> Option<PauseState> {
    let state: PauseState = env
        .storage()
        .instance()
        .get(&GovernanceDataKey::PauseState)?;
    if env.ledger().timestamp() >= state.expires_at {
        return None;
    }
    Some(state)
}
