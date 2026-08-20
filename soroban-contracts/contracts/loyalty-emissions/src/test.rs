use super::*;
use soroban_sdk::testutils::{Address as _, Ledger};
use soroban_sdk::{Address, Env, String, Vec};

// The real deployed token, aliased so it doesn't clash with the local
// `LoyaltyTokenClient` cross-contract stub declared in `lib.rs`.
use guildworkman_loyalty_token::{
    LoyaltyToken as RealLoyaltyToken, LoyaltyTokenClient as RealLoyaltyTokenClient,
};

const WINDOW: u32 = 100;
const ACCOUNT_CAP: i128 = 1_000;
const GLOBAL_CAP: i128 = 10_000;

struct Fixture<'a> {
    env: Env,
    emissions: LoyaltyEmissionsClient<'a>,
    token: RealLoyaltyTokenClient<'a>,
    admin: Address,
    signers: Vec<Address>,
    /// The underlying token's *own* governance signers — distinct from
    /// `signers`, which govern the emission engine. Needed to exercise the
    /// case where the token halts minting underneath a healthy engine.
    token_signers: Vec<Address>,
}

fn make_signers(env: &Env, n: u32) -> Vec<Address> {
    let mut signers = Vec::new(env);
    for _ in 0..n {
        signers.push_back(Address::generate(env));
    }
    signers
}

/// Deploy the loyalty token, deploy the emissions engine, and hand the token's
/// `minter` role to the engine so `claim` can mint.
fn setup<'a>() -> Fixture<'a> {
    let env = Env::default();
    env.mock_all_auths();

    let admin = Address::generate(&env);
    let token_admin = Address::generate(&env);

    // Underlying loyalty token.
    let token_id = env.register(RealLoyaltyToken, ());
    let token = RealLoyaltyTokenClient::new(&env, &token_id);
    let token_signers = make_signers(&env, 1);
    token.initialize(
        &token_admin,
        &token_admin, // temporary minter, rotated to the engine below
        &2,
        &String::from_str(&env, "GuildWorkman Points"),
        &String::from_str(&env, "GWP"),
        &governance::GovernanceInit {
            signers: token_signers.clone(),
            threshold: 1,
        },
    );

    // Emissions engine.
    let emissions_id = env.register(LoyaltyEmissions, ());
    let emissions = LoyaltyEmissionsClient::new(&env, &emissions_id);
    let config = Config {
        window: WINDOW,
        account_cap: ACCOUNT_CAP,
        global_cap: GLOBAL_CAP,
    };
    let signers = make_signers(&env, 3);
    emissions.initialize(
        &admin,
        &token_id,
        &config,
        &governance::GovernanceInit {
            signers: signers.clone(),
            threshold: 2,
        },
    );

    // The engine must be the token's minter.
    token.set_minter(&emissions_id);

    Fixture {
        env,
        emissions,
        token,
        admin,
        signers,
        token_signers,
    }
}

fn set_ledger(env: &Env, seq: u32) {
    env.ledger().with_mut(|l| l.sequence_number = seq);
}

// ---------------------------------------------------------------------------
// Initialization
// ---------------------------------------------------------------------------

#[test]
fn initialize_stores_config() {
    let f = setup();
    let cfg = f.emissions.get_config();
    assert_eq!(cfg.window, WINDOW);
    assert_eq!(cfg.account_cap, ACCOUNT_CAP);
    assert_eq!(cfg.global_cap, GLOBAL_CAP);
    assert_eq!(f.emissions.get_admin(), f.admin);
}

#[test]
fn double_initialize_fails() {
    let f = setup();
    let cfg = Config {
        window: WINDOW,
        account_cap: ACCOUNT_CAP,
        global_cap: GLOBAL_CAP,
    };
    let token = f.emissions.get_token();
    let gov = governance::GovernanceInit {
        signers: f.signers.clone(),
        threshold: 2,
    };
    let res = f.emissions.try_initialize(&f.admin, &token, &cfg, &gov);
    assert_eq!(res, Err(Ok(Error::AlreadyInitialized)));
}

#[test]
fn initialize_rejects_bad_config() {
    let env = Env::default();
    env.mock_all_auths();
    let admin = Address::generate(&env);
    let token = Address::generate(&env);
    let emissions_id = env.register(LoyaltyEmissions, ());
    let emissions = LoyaltyEmissionsClient::new(&env, &emissions_id);

    let bad = Config {
        window: 0,
        account_cap: ACCOUNT_CAP,
        global_cap: GLOBAL_CAP,
    };
    let gov = governance::GovernanceInit {
        signers: make_signers(&env, 1),
        threshold: 1,
    };
    assert_eq!(
        emissions.try_initialize(&admin, &token, &bad, &gov),
        Err(Ok(Error::InvalidConfig))
    );
}

// ---------------------------------------------------------------------------
// Vesting math
// ---------------------------------------------------------------------------

#[test]
fn linear_vesting_over_time() {
    let f = setup();
    let user = Address::generate(&f.env);
    set_ledger(&f.env, 1_000);
    // total 1000, start now, no cliff, duration 1000 ledgers, reclaim after end.
    f.emissions
        .create_schedule(&user, &1_000, &0, &0, &1_000, &2_000);

    // Nothing vested at start.
    assert_eq!(f.emissions.vested(&user), 0);

    // Halfway through.
    set_ledger(&f.env, 1_500);
    assert_eq!(f.emissions.vested(&user), 500);

    // Fully vested at/after end.
    set_ledger(&f.env, 2_000);
    assert_eq!(f.emissions.vested(&user), 1_000);

    // Stays capped after the end.
    set_ledger(&f.env, 5_000);
    assert_eq!(f.emissions.vested(&user), 1_000);
}

#[test]
fn cliff_blocks_vesting_until_reached() {
    let f = setup();
    let user = Address::generate(&f.env);
    set_ledger(&f.env, 1_000);
    // cliff of 400 ledgers into a 1000-ledger stream.
    f.emissions
        .create_schedule(&user, &1_000, &1_000, &400, &1_000, &2_000);

    // Just before the cliff: nothing.
    set_ledger(&f.env, 1_399);
    assert_eq!(f.emissions.vested(&user), 0);

    // At the cliff: linear value at that point (400/1000 = 40%).
    set_ledger(&f.env, 1_400);
    assert_eq!(f.emissions.vested(&user), 400);
}

// ---------------------------------------------------------------------------
// Claiming
// ---------------------------------------------------------------------------

#[test]
fn claim_mints_vested_amount() {
    let f = setup();
    let user = Address::generate(&f.env);
    set_ledger(&f.env, 1_000);
    f.emissions
        .create_schedule(&user, &800, &0, &0, &1_000, &2_000);

    set_ledger(&f.env, 1_500); // 50% vested -> 400
    let minted = f.emissions.claim(&user);
    assert_eq!(minted, 400);
    assert_eq!(f.token.balance(&user), 400);

    let schedule = f.emissions.get_schedule(&user);
    assert_eq!(schedule.claimed, 400);
}

#[test]
fn incremental_claims_only_mint_the_delta() {
    let f = setup();
    let user = Address::generate(&f.env);
    set_ledger(&f.env, 1_000);
    f.emissions
        .create_schedule(&user, &1_000, &0, &0, &1_000, &2_000);

    set_ledger(&f.env, 1_300); // 30%
    assert_eq!(f.emissions.claim(&user), 300);
    set_ledger(&f.env, 1_700); // 70% total, already claimed 300 -> 400 more
    assert_eq!(f.emissions.claim(&user), 400);
    set_ledger(&f.env, 2_000); // 100%, claimed 700 -> 300 more
    assert_eq!(f.emissions.claim(&user), 300);
    assert_eq!(f.token.balance(&user), 1_000);
}

#[test]
fn claim_with_nothing_vested_fails() {
    let f = setup();
    let user = Address::generate(&f.env);
    set_ledger(&f.env, 1_000);
    f.emissions
        .create_schedule(&user, &1_000, &2_000, &0, &1_000, &4_000);
    // Before start.
    assert_eq!(f.emissions.try_claim(&user), Err(Ok(Error::NothingToClaim)));
}

#[test]
fn double_claim_same_ledger_fails_second_time() {
    let f = setup();
    let user = Address::generate(&f.env);
    set_ledger(&f.env, 1_000);
    f.emissions
        .create_schedule(&user, &1_000, &0, &0, &1_000, &2_000);
    set_ledger(&f.env, 2_000);
    // Fully vested but rate-limited to 1000/window; account_cap == 1000 here.
    assert_eq!(f.emissions.claim(&user), 1_000);
    // Second claim in same window: nothing left to claim.
    assert_eq!(f.emissions.try_claim(&user), Err(Ok(Error::NothingToClaim)));
}

// ---------------------------------------------------------------------------
// Rate limiting (anti-abuse)
// ---------------------------------------------------------------------------

#[test]
fn account_rate_limit_clamps_claim() {
    let f = setup();
    let user = Address::generate(&f.env);
    set_ledger(&f.env, 1_000);
    // 5000 total, fully vests quickly, but account_cap is 1000/window.
    f.emissions
        .create_schedule(&user, &5_000, &0, &0, &10, &1_100);

    set_ledger(&f.env, 1_050); // fully vested (5000)
                               // Window index = 1050/100 = 10. Clamped to account_cap 1000.
    assert_eq!(f.emissions.claim(&user), 1_000);
    // Same window, budget exhausted.
    assert_eq!(
        f.emissions.try_claim(&user),
        Err(Ok(Error::AccountRateLimited))
    );

    // Next window opens a fresh 1000 budget.
    set_ledger(&f.env, 1_100); // window index 11
    assert_eq!(f.emissions.claim(&user), 1_000);
    assert_eq!(f.token.balance(&user), 2_000);
}

#[test]
fn claimable_view_reflects_rate_limit() {
    let f = setup();
    let user = Address::generate(&f.env);
    set_ledger(&f.env, 1_000);
    f.emissions
        .create_schedule(&user, &5_000, &0, &0, &10, &1_100);
    set_ledger(&f.env, 1_050);
    // Fully vested 5000, but claimable clamps to account_cap 1000.
    assert_eq!(f.emissions.claimable(&user), 1_000);
    f.emissions.claim(&user);
    // Budget spent -> claimable now 0 for this window.
    assert_eq!(f.emissions.claimable(&user), 0);
}

#[test]
fn global_rate_limit_is_a_circuit_breaker() {
    // Reconfigure with a small global cap so a handful of accounts hit it.
    let env = Env::default();
    env.mock_all_auths();
    let admin = Address::generate(&env);
    let token_admin = Address::generate(&env);

    let token_id = env.register(RealLoyaltyToken, ());
    let token = RealLoyaltyTokenClient::new(&env, &token_id);
    token.initialize(
        &token_admin,
        &token_admin,
        &2,
        &String::from_str(&env, "GuildWorkman Points"),
        &String::from_str(&env, "GWP"),
        &governance::GovernanceInit {
            signers: make_signers(&env, 1),
            threshold: 1,
        },
    );

    let emissions_id = env.register(LoyaltyEmissions, ());
    let emissions = LoyaltyEmissionsClient::new(&env, &emissions_id);
    emissions.initialize(
        &admin,
        &token_id,
        &Config {
            window: WINDOW,
            account_cap: 1_000,
            global_cap: 1_500, // only 1.5 accounts' worth per window
        },
        &governance::GovernanceInit {
            signers: make_signers(&env, 1),
            threshold: 1,
        },
    );
    token.set_minter(&emissions_id);

    let a = Address::generate(&env);
    let b = Address::generate(&env);
    env.ledger().with_mut(|l| l.sequence_number = 1_000);
    emissions.create_schedule(&a, &5_000, &0, &0, &10, &1_100);
    emissions.create_schedule(&b, &5_000, &0, &0, &10, &1_100);
    env.ledger().with_mut(|l| l.sequence_number = 1_050);

    // First account takes its full 1000 (global now 1000/1500).
    assert_eq!(emissions.claim(&a), 1_000);
    // Second account is clamped by the *global* remainder of 500.
    assert_eq!(emissions.claim(&b), 500);
    // Global exhausted this window.
    assert_eq!(emissions.try_claim(&b), Err(Ok(Error::GlobalRateLimited)));
}

// ---------------------------------------------------------------------------
// Reclaim
// ---------------------------------------------------------------------------

#[test]
fn reclaim_returns_unclaimed_remainder() {
    let f = setup();
    let user = Address::generate(&f.env);
    set_ledger(&f.env, 1_000);
    f.emissions
        .create_schedule(&user, &1_000, &0, &0, &1_000, &2_000);

    set_ledger(&f.env, 1_300); // 30%
    assert_eq!(f.emissions.claim(&user), 300);

    // Reclaim at/after reclaimable_after.
    set_ledger(&f.env, 2_000);
    let reclaimed = f.emissions.reclaim(&user);
    assert_eq!(reclaimed, 700); // total 1000 - claimed 300
                                // Reclaimed units were never minted.
    assert_eq!(f.token.balance(&user), 300);
}

#[test]
fn reclaim_before_deadline_fails() {
    let f = setup();
    let user = Address::generate(&f.env);
    set_ledger(&f.env, 1_000);
    f.emissions
        .create_schedule(&user, &1_000, &0, &0, &1_000, &2_000);
    set_ledger(&f.env, 1_999);
    assert_eq!(
        f.emissions.try_reclaim(&user),
        Err(Ok(Error::NotYetReclaimable))
    );
}

#[test]
fn claim_after_reclaim_fails() {
    let f = setup();
    let user = Address::generate(&f.env);
    set_ledger(&f.env, 1_000);
    f.emissions
        .create_schedule(&user, &1_000, &0, &0, &1_000, &2_000);
    set_ledger(&f.env, 2_000);
    f.emissions.reclaim(&user);
    assert_eq!(
        f.emissions.try_claim(&user),
        Err(Ok(Error::AlreadyReclaimed))
    );
    assert_eq!(f.emissions.claimable(&user), 0);
}

#[test]
fn double_reclaim_fails() {
    let f = setup();
    let user = Address::generate(&f.env);
    set_ledger(&f.env, 1_000);
    f.emissions
        .create_schedule(&user, &1_000, &0, &0, &1_000, &2_000);
    set_ledger(&f.env, 2_000);
    f.emissions.reclaim(&user);
    assert_eq!(
        f.emissions.try_reclaim(&user),
        Err(Ok(Error::AlreadyReclaimed))
    );
}

#[test]
fn reclaim_fully_claimed_schedule_fails() {
    // Fully vest and drain within the caps, then reclaim finds nothing.
    let f = setup();
    let user = Address::generate(&f.env);
    set_ledger(&f.env, 1_000);
    // total == account_cap so one claim drains it.
    f.emissions
        .create_schedule(&user, &1_000, &0, &0, &10, &1_100);
    set_ledger(&f.env, 1_050);
    assert_eq!(f.emissions.claim(&user), 1_000);
    set_ledger(&f.env, 1_100);
    assert_eq!(
        f.emissions.try_reclaim(&user),
        Err(Ok(Error::NothingToReclaim))
    );
}

// ---------------------------------------------------------------------------
// Schedule validation & adversarial cases
// ---------------------------------------------------------------------------

#[test]
fn create_duplicate_schedule_fails() {
    let f = setup();
    let user = Address::generate(&f.env);
    set_ledger(&f.env, 1_000);
    f.emissions
        .create_schedule(&user, &1_000, &0, &0, &1_000, &2_000);
    assert_eq!(
        f.emissions
            .try_create_schedule(&user, &1_000, &0, &0, &1_000, &2_000),
        Err(Ok(Error::ScheduleExists))
    );
}

#[test]
fn create_schedule_rejects_zero_duration() {
    let f = setup();
    let user = Address::generate(&f.env);
    set_ledger(&f.env, 1_000);
    assert_eq!(
        f.emissions
            .try_create_schedule(&user, &1_000, &0, &0, &0, &2_000),
        Err(Ok(Error::InvalidSchedule))
    );
}

#[test]
fn create_schedule_rejects_cliff_past_duration() {
    let f = setup();
    let user = Address::generate(&f.env);
    set_ledger(&f.env, 1_000);
    assert_eq!(
        f.emissions
            .try_create_schedule(&user, &1_000, &0, &1_001, &1_000, &2_000),
        Err(Ok(Error::InvalidSchedule))
    );
}

#[test]
fn create_schedule_rejects_nonpositive_total() {
    let f = setup();
    let user = Address::generate(&f.env);
    set_ledger(&f.env, 1_000);
    assert_eq!(
        f.emissions
            .try_create_schedule(&user, &0, &0, &0, &1_000, &2_000),
        Err(Ok(Error::InvalidSchedule))
    );
}

#[test]
fn create_schedule_rejects_reclaim_before_vesting_ends() {
    // reclaimable_after must be >= start + duration so the admin cannot rug a
    // still-vesting beneficiary.
    let f = setup();
    let user = Address::generate(&f.env);
    set_ledger(&f.env, 1_000);
    // start 1000, duration 1000 -> ends at 2000, but reclaimable_after 1500.
    assert_eq!(
        f.emissions
            .try_create_schedule(&user, &1_000, &1_000, &0, &1_000, &1_500),
        Err(Ok(Error::InvalidSchedule))
    );
}

#[test]
fn claim_on_missing_schedule_fails() {
    let f = setup();
    let user = Address::generate(&f.env);
    assert_eq!(
        f.emissions.try_claim(&user),
        Err(Ok(Error::ScheduleNotFound))
    );
}

#[test]
fn a_user_cannot_out_claim_their_total_via_many_windows() {
    // Adversarial: repeatedly claim across many windows must never exceed total.
    let f = setup();
    let user = Address::generate(&f.env);
    set_ledger(&f.env, 1_000);
    f.emissions
        .create_schedule(&user, &2_500, &0, &0, &10, &1_100);

    let mut ledger = 1_050u32;
    let mut total_minted = 0i128;
    for _ in 0..10 {
        set_ledger(&f.env, ledger);
        if let Ok(amount) = f.emissions.try_claim(&user) {
            total_minted += amount.unwrap();
        }
        ledger += WINDOW; // move to a fresh rate-limit window each iteration
    }
    assert_eq!(total_minted, 2_500);
    assert_eq!(f.token.balance(&user), 2_500);
}

// ===========================================================================
// Upgrade governance — see the equivalent block in reputation/src/test.rs
// for why these all stop one approval short of the configured threshold.
// ===========================================================================

fn dummy_hash(env: &Env) -> soroban_sdk::BytesN<32> {
    soroban_sdk::BytesN::from_array(env, &[0u8; 32])
}

#[test]
fn initialize_stores_governance_config() {
    let f = setup();
    assert_eq!(f.emissions.get_signers(), f.signers);
    assert_eq!(f.emissions.get_upgrade_threshold(), 2);
    assert_eq!(f.emissions.get_storage_version(), 1);
}

#[test]
fn propose_upgrade_by_non_signer_fails() {
    let f = setup();
    let outsider = Address::generate(&f.env);
    let result = f
        .emissions
        .try_propose_upgrade(&outsider, &dummy_hash(&f.env));
    assert_eq!(result, Err(Ok(Error::NotASigner)));
}

#[test]
fn approve_upgrade_below_threshold_is_not_ready() {
    let f = setup();
    let target = dummy_hash(&f.env);

    let ready = f
        .emissions
        .propose_upgrade(&f.signers.get_unchecked(0), &target);
    assert!(!ready);

    let pending = f.emissions.get_pending_upgrade().unwrap();
    assert_eq!(pending.approvals.len(), 1);
    assert_eq!(pending.wasm_hash, target);
}

#[test]
fn cancel_upgrade_by_a_non_approving_signer_still_works() {
    let f = setup();
    f.emissions
        .propose_upgrade(&f.signers.get_unchecked(0), &dummy_hash(&f.env));

    f.emissions.cancel_upgrade(&f.signers.get_unchecked(2));
    assert!(f.emissions.get_pending_upgrade().is_none());
}

#[test]
fn migrate_with_nothing_to_migrate_fails() {
    let f = setup();
    let result = f.emissions.try_migrate(&f.signers.get_unchecked(0));
    assert_eq!(result, Err(Ok(Error::NothingToMigrate)));
}

#[test]
fn migrate_by_non_signer_fails() {
    let f = setup();
    let outsider = Address::generate(&f.env);
    let result = f.emissions.try_migrate(&outsider);
    assert_eq!(result, Err(Ok(Error::NotASigner)));
}

// ---------------------------------------------------------------------------
// Emergency circuit breaker
// ---------------------------------------------------------------------------
//
// The breaker's own semantics live in `guildworkman-governance-guard`'s unit
// tests. These cover the engine's wiring, plus the one genuinely cross-contract
// case: the token halting mint underneath a healthy engine.

/// Reason string for tests that don't exercise the field itself. Kept
/// non-empty so the round-trip through storage is actually covered by every
/// pause test rather than only the ones that look at it.
fn reason(env: &Env) -> soroban_sdk::String {
    soroban_sdk::String::from_str(env, "INC-000 test")
}

fn set_time(env: &Env, timestamp: u64) {
    env.ledger().with_mut(|l| l.timestamp = timestamp);
}

#[test]
fn paused_intake_blocks_new_schedules() {
    let f = setup();
    let user = Address::generate(&f.env);
    set_ledger(&f.env, 1_000);
    set_time(&f.env, 1_000);
    f.emissions.pause(
        &f.signers.get_unchecked(0),
        &SCOPE_INTAKE,
        &3_600,
        &reason(&f.emissions.env),
    );

    let res = f
        .emissions
        .try_create_schedule(&user, &1_000, &0, &0, &1_000, &2_000);
    assert_eq!(res, Err(Ok(Error::OperationPaused)));
    assert_eq!(
        f.emissions.try_get_schedule(&user),
        Err(Ok(Error::ScheduleNotFound))
    );
}

#[test]
fn paused_settlement_blocks_claims_and_mints_nothing() {
    let f = setup();
    let user = Address::generate(&f.env);
    set_ledger(&f.env, 1_000);
    f.emissions
        .create_schedule(&user, &1_000, &0, &0, &1_000, &2_000);

    set_ledger(&f.env, 1_500);
    set_time(&f.env, 1_000);
    f.emissions.pause(
        &f.signers.get_unchecked(0),
        &SCOPE_SETTLEMENT,
        &3_600,
        &reason(&f.emissions.env),
    );

    let res = f.emissions.try_claim(&user);
    assert_eq!(res, Err(Ok(Error::OperationPaused)));
    assert_eq!(f.token.balance(&user), 0);
    assert_eq!(f.emissions.get_schedule(&user).claimed, 0);
}

#[test]
fn a_halted_claim_loses_nothing_because_vesting_keeps_accruing() {
    // The reason halting `claim` is a delay rather than a confiscation:
    // `vested` is a pure function of the ledger clock, so the beneficiary
    // gets the same units afterwards, only later.
    let f = setup();
    let user = Address::generate(&f.env);
    set_ledger(&f.env, 1_000);
    f.emissions
        .create_schedule(&user, &1_000, &0, &0, &1_000, &2_000);

    set_ledger(&f.env, 1_500);
    set_time(&f.env, 1_000);
    f.emissions.pause(
        &f.signers.get_unchecked(0),
        &SCOPE_SETTLEMENT,
        &3_600,
        &reason(&f.emissions.env),
    );
    assert_eq!(
        f.emissions.try_claim(&user),
        Err(Ok(Error::OperationPaused))
    );

    // Vesting continued through the halt, and it lifts with no transaction.
    set_ledger(&f.env, 1_900);
    set_time(&f.env, 4_600);
    assert_eq!(f.emissions.paused_scopes(), 0);
    assert_eq!(f.emissions.claim(&user), 900);
    assert_eq!(f.token.balance(&user), 900);
}

#[test]
fn reclaim_stays_open_while_settlement_is_halted() {
    // `reclaim` mints and burns nothing — it only marks an allocation as
    // never-to-be-minted — so it is not a fund-recovery path that must stay
    // open, but it is also not one a pause needs to close. Pinned here so
    // the asymmetry documented on `reclaim` is a tested fact.
    let f = setup();
    let user = Address::generate(&f.env);
    set_ledger(&f.env, 1_000);
    f.emissions
        .create_schedule(&user, &1_000, &0, &0, &1_000, &2_000);

    set_ledger(&f.env, 2_000);
    set_time(&f.env, 1_000);
    f.emissions.pause(
        &f.signers.get_unchecked(0),
        &ALL_SCOPES,
        &3_600,
        &reason(&f.emissions.env),
    );

    assert_eq!(f.emissions.reclaim(&user), 1_000);
    assert_eq!(f.token.balance(&user), 0);
}

#[test]
fn views_stay_readable_while_everything_is_halted() {
    let f = setup();
    let user = Address::generate(&f.env);
    set_ledger(&f.env, 1_000);
    f.emissions
        .create_schedule(&user, &1_000, &0, &0, &1_000, &2_000);

    set_ledger(&f.env, 1_500);
    set_time(&f.env, 1_000);
    f.emissions.pause(
        &f.signers.get_unchecked(0),
        &ALL_SCOPES,
        &3_600,
        &reason(&f.emissions.env),
    );

    assert_eq!(f.emissions.vested(&user), 500);
    assert_eq!(f.emissions.claimable(&user), 500);
    assert_eq!(f.emissions.get_schedule(&user).total, 1_000);
}

#[test]
fn pausing_the_token_alone_stops_claims_at_the_mint_boundary() {
    // The engine holds the token's `minter` role, so halting token intake
    // stops emissions even with the engine itself wide open. The failure
    // surfaces as a trapped cross-contract invocation rather than this
    // contract's own typed error, since the engine calls `mint` through the
    // non-`try` client — what matters is that no supply is created.
    let f = setup();
    let user = Address::generate(&f.env);
    set_ledger(&f.env, 1_000);
    f.emissions
        .create_schedule(&user, &1_000, &0, &0, &1_000, &2_000);

    set_ledger(&f.env, 1_500);
    set_time(&f.env, 1_000);
    f.token.pause(
        &f.token_signers.get_unchecked(0),
        &SCOPE_INTAKE,
        &3_600,
        &reason(&f.token.env),
    );

    assert_eq!(f.emissions.paused_scopes(), 0);
    assert!(f.emissions.try_claim(&user).is_err());
    assert_eq!(f.token.balance(&user), 0);
    assert_eq!(f.emissions.get_schedule(&user).claimed, 0);

    // Lifting the token's halt is enough; the engine needed no change.
    f.token
        .unpause(&f.token_signers.get_unchecked(0), &SCOPE_INTAKE);
    assert_eq!(f.emissions.claim(&user), 500);
    assert_eq!(f.token.balance(&user), 500);
}

#[test]
fn a_non_signer_cannot_pause_the_engine() {
    let f = setup();
    let outsider = Address::generate(&f.env);

    let res = f
        .emissions
        .try_pause(&outsider, &ALL_SCOPES, &3_600, &reason(&f.emissions.env));
    assert_eq!(res, Err(Ok(Error::NotASigner)));
    assert_eq!(f.emissions.paused_scopes(), 0);
}

#[test]
fn the_emissions_admin_is_not_a_pause_authority() {
    let f = setup();
    let res = f
        .emissions
        .try_pause(&f.admin, &ALL_SCOPES, &3_600, &reason(&f.emissions.env));
    assert_eq!(res, Err(Ok(Error::NotASigner)));
}

#[test]
fn a_pause_longer_than_the_cap_is_refused() {
    let f = setup();
    set_time(&f.env, 1_000);

    let res = f.emissions.try_pause(
        &f.signers.get_unchecked(0),
        &ALL_SCOPES,
        &(MAX_PAUSE_DURATION + 1),
        &reason(&f.emissions.env),
    );
    assert_eq!(res, Err(Ok(Error::InvalidPauseDuration)));
    assert_eq!(f.emissions.paused_scopes(), 0);
}

#[test]
fn unpausing_settlement_alone_reopens_claims_while_intake_stays_halted() {
    let f = setup();
    let user = Address::generate(&f.env);
    let other = Address::generate(&f.env);
    set_ledger(&f.env, 1_000);
    f.emissions
        .create_schedule(&user, &1_000, &0, &0, &1_000, &2_000);

    set_ledger(&f.env, 1_500);
    set_time(&f.env, 1_000);
    f.emissions.pause(
        &f.signers.get_unchecked(0),
        &ALL_SCOPES,
        &3_600,
        &reason(&f.emissions.env),
    );

    let remaining = f
        .emissions
        .unpause(&f.signers.get_unchecked(1), &SCOPE_SETTLEMENT);
    assert_eq!(remaining & SCOPE_SETTLEMENT, 0);
    assert_eq!(remaining & SCOPE_INTAKE, SCOPE_INTAKE);

    assert_eq!(f.emissions.claim(&user), 500);
    let res = f
        .emissions
        .try_create_schedule(&other, &1_000, &0, &0, &1_000, &2_000);
    assert_eq!(res, Err(Ok(Error::OperationPaused)));
}

// ---------------------------------------------------------------------------
// Cross-contract broadcast
// ---------------------------------------------------------------------------

#[test]
fn one_scope_mask_broadcast_to_two_contracts_halts_only_the_intended_paths() {
    // This fixture registers two real, separately-deployed contracts with
    // independent storage and independent signer sets — the closest thing in
    // a unit test to the protocol-wide sweep an operator would actually run.
    //
    // The sweep is N transactions, not one, and each contract's guard reads
    // only its own record. What's asserted here is that the same mask lands
    // correctly on both despite them implementing different subsets of it.
    let f = setup();
    let user = Address::generate(&f.env);
    set_ledger(&f.env, 1_000);
    f.emissions
        .create_schedule(&user, &1_000, &0, &0, &1_000, &2_000);
    set_ledger(&f.env, 1_500);
    set_time(&f.env, 1_000);

    // One mask, broadcast to both. ATTESTATION is meaningful to neither and
    // rides along without special-casing.
    let mask = SCOPE_INTAKE | governance::SCOPE_ATTESTATION;
    let why = soroban_sdk::String::from_str(&f.env, "INC-77 protocol sweep");
    f.emissions
        .pause(&f.signers.get_unchecked(0), &mask, &3_600, &why);
    f.token
        .pause(&f.token_signers.get_unchecked(0), &mask, &3_600, &why);

    assert_eq!(f.emissions.paused_scopes(), mask);
    assert_eq!(f.token.paused_scopes(), mask);

    // Intake is halted on both: no new schedules, no new supply.
    let other = Address::generate(&f.env);
    assert_eq!(
        f.emissions
            .try_create_schedule(&other, &1_000, &0, &0, &1_000, &2_000),
        Err(Ok(Error::OperationPaused))
    );

    // Settlement was never named, so claiming still works at the emissions
    // layer — but the token's halted mint stops it at the boundary, and no
    // supply is created. Exactly the intended protocol-wide effect.
    assert!(f.emissions.try_claim(&user).is_err());
    assert_eq!(f.token.balance(&user), 0);
    assert_eq!(f.emissions.get_schedule(&user).claimed, 0);

    // Lifting the sweep on both restores normal service with no other change.
    f.emissions
        .unpause(&f.signers.get_unchecked(1), &ALL_SCOPES);
    f.token
        .unpause(&f.token_signers.get_unchecked(0), &ALL_SCOPES);
    assert_eq!(f.emissions.claim(&user), 500);
    assert_eq!(f.token.balance(&user), 500);
}

#[test]
fn the_two_contracts_pause_records_are_fully_independent() {
    // Separate deployments, separate storage: halting one must not be
    // observable from the other, in either direction.
    let f = setup();
    set_time(&f.env, 1_000);

    f.emissions.pause(
        &f.signers.get_unchecked(0),
        &SCOPE_SETTLEMENT,
        &3_600,
        &reason(&f.emissions.env),
    );
    assert_eq!(f.emissions.paused_scopes(), SCOPE_SETTLEMENT);
    assert_eq!(f.token.paused_scopes(), 0);
    assert!(f.token.get_pause_state().is_none());

    f.token.pause(
        &f.token_signers.get_unchecked(0),
        &SCOPE_INTAKE,
        &7_200,
        &reason(&f.token.env),
    );
    assert_eq!(f.emissions.paused_scopes(), SCOPE_SETTLEMENT);
    assert_eq!(f.token.paused_scopes(), SCOPE_INTAKE);
    // Different deadlines, kept apart.
    assert_eq!(f.emissions.get_pause_state().unwrap().expires_at, 4_600);
    assert_eq!(f.token.get_pause_state().unwrap().expires_at, 8_200);
}

#[test]
fn a_signer_of_one_contract_cannot_pause_the_other() {
    // The two deployments have independent governance sets; authority must
    // not leak across them just because they sit in the same protocol.
    let f = setup();
    set_time(&f.env, 1_000);

    let res = f.token.try_pause(
        &f.signers.get_unchecked(0),
        &SCOPE_INTAKE,
        &3_600,
        &reason(&f.token.env),
    );
    assert_eq!(res, Err(Ok(guildworkman_loyalty_token::Error::NotASigner)));

    let res = f.emissions.try_pause(
        &f.token_signers.get_unchecked(0),
        &SCOPE_INTAKE,
        &3_600,
        &reason(&f.emissions.env),
    );
    assert_eq!(res, Err(Ok(Error::NotASigner)));
}
