# guildworkman-contracts

![CI](https://github.com/workman-labs/guildworkman-contracts/actions/workflows/ci.yml/badge.svg)

Soroban (Stellar) smart contracts for GuildWorkman, the skilled-worker booking
marketplace. This workspace has five independent contracts:

| Contract | Path | Purpose |
|---|---|---|
| `escrow` | `contracts/escrow` | Holds a client's payment for a booked appointment until the client confirms the job is done; releases funds to the skilled worker, refunds on cancellation, and supports admin-arbitrated disputes. |
| `reputation` | `contracts/reputation` | Stores one immutable review per completed appointment and keeps a running rating aggregate per skilled worker. |
| `loyalty-token` | `contracts/loyalty-token` | A SEP-41-style fungible token used to reward clients/workers with points on completed appointments. Only a designated `minter` (the backend's service account) can mint. |
| `loyalty-emissions` | `contracts/loyalty-emissions` | An emission engine that owns the `loyalty-token`'s `minter` role. Instead of minting rewards in a lump sum, it streams them out of per-account linear vesting schedules, throttled by per-account and global rate limits, and lets the admin reclaim allocations left unclaimed past a deadline. |
| `dispute-resolution` | `contracts/dispute-resolution` | Decentralized alternative to `escrow`'s single-admin arbitration: resolves a dispute via a **staked jury** using **commit-reveal** voting, then pays the majority out of the slashed stakes of the minority and no-shows. |
| `governance-guard` | `contracts/governance-guard` | Not a deployed contract — a shared library that four of the contracts above (all except `dispute-resolution`) depend on, providing the multi-sig upgrade/migration pattern described in [Upgrade governance](#upgrade-governance). |

These mirror the domain already implemented server-side in the backend
([`../backend-api`](../backend-api): `AppointmentService`, `ReviewService`,
`TransactionService`), moving the trust-sensitive parts of that flow —
holding money, recording reviews, issuing rewards — on-chain.

## Table of contents

- [Project ecosystem](#project-ecosystem)
- [Architecture](#architecture)
- [Upgrade governance](#upgrade-governance)
- [Prerequisites](#prerequisites)
- [Build](#build)
- [Test](#test)
- [Deploy (testnet example)](#deploy-testnet-example)
- [Contract interfaces](#contract-interfaces)
- [Security considerations / known limitations](#security-considerations--known-limitations)
- [Notes / follow-ups](#notes--follow-ups)
- [License](#license)

## Project ecosystem

GuildWorkman lives in two repositories:

| Repo | Role |
|---|---|
| **`guildworkman-core`** (this repo) | These Soroban contracts (`soroban-contracts/`, here) **and** the Spring Boot backend (`backend-api/`) — which implements the same domain server-side today (`AppointmentService`, `ReviewService`, `TransactionService`) and is the intended caller of these contracts once integrated (see [Suggested backend integration](#suggested-backend-integration-not-yet-wired-in)). |
| [`guildworkman-web`](https://github.com/workman-labs/guildworkman-web) | Next.js frontend. Already has a real Freighter wallet-connect button and a "Trust, backed by smart contracts" section describing these contracts — but makes no contract calls yet. |

## Architecture

These are three separate contracts rather than one monolith:

- **Independent storage and upgrade paths.** `escrow` handles money,
  `reputation` handles reviews, `loyalty-token` handles a token balance
  ledger. Each has a different risk profile and a different reason to change;
  bugs or upgrades in one shouldn't force redeploying the others.
- **Independent trust boundaries.** `escrow` and `loyalty-token` each have
  their own `admin`/`minter` role — they don't need to trust each other's
  internal state, only the caller's authenticated identity.
- **Composability over coupling.** A future contract (or the backend) can
  call into any of the three via their public functions without depending on
  their private storage layout.

### Suggested backend integration (not yet wired in)

None of this is currently called from the backend (`../backend-api`). The
intended flow, if/when integrated, looks like:

1. Client books a worker in the existing Java backend → backend (or the
   client's wallet, if going non-custodial) calls `escrow.create_appointment`
   with the agreed amount and a token address (e.g. a Stellar Asset Contract
   wrapping USDC), instead of only charging via Paystack.
2. Client marks the job done in the app → backend calls
   `escrow.confirm_completion`, which pays the worker.
3. Backend calls `reputation.submit_review` with the same `appointment_id`
   right after the client submits their in-app review, so on-chain reviews
   stay 1:1 with completed, paid appointments.
4. Backend (as `minter`) calls `loyalty-token.mint` to reward the client
   and/or worker some points for the completed appointment — or, for rewards
   that should vest over time rather than land instantly, registers a
   `loyalty-emissions.create_schedule` and lets the recipient `claim` the
   stream as it vests (see [loyalty-emissions](#loyalty-emissions)).

This requires the backend to hold a Stellar keypair per role (or per user, if
going non-custodial) and a Soroban RPC client — none of that exists in
`backend-api/` today.

## Upgrade governance

All four contracts can have their code swapped in place via Soroban's
`update_current_contract_wasm`, gated behind an M-of-N multi-sig — set once
at `initialize` via a `signers: Vec<Address>` + `threshold: u32` — instead of
being controlled by a single key or left permanently immutable. The pattern
lives in `contracts/governance-guard`, a shared library each contract depends
on and calls from inside its own methods; it is not itself deployed.

This is deliberately narrow in scope: it only ever gates the ability to swap
a contract's code and run its post-upgrade migration. It does not replace or
extend any contract's existing `admin`/`minter` role, and a compromised or
colluding signer set still cannot resolve disputes, mint tokens, or reclaim
schedules directly — it can only ship new code that would need to be
malicious on its own terms to do any of that.

**Flow:**

1. Any signer uploads the new Wasm — `stellar contract upload --wasm ...` —
   and gets back a hash. (This step doesn't go through governance-guard; it
   just puts bytes on the ledger, uploading code executes nothing.)
2. A signer calls `propose_upgrade(proposer, wasm_hash)`. Their approval is
   recorded immediately.
3. Other signers call `approve_upgrade(approver, wasm_hash)` until approvals
   reach `threshold`. **Whichever call — propose or approve — is the one
   that reaches threshold performs the actual swap in that same
   transaction** (with a 1-of-N guard, that's `propose_upgrade` itself).
4. Because a Wasm swap only takes effect after its own invocation finishes,
   the new code can't run a migration in that same call. A signer calls
   `migrate(signer)` afterward, in a separate transaction, to run whatever
   storage transformation the new version needs and advance the recorded
   storage version. No such transformation exists yet in any contract —
   `migrate` currently just proves the version-gated path is real and
   returns `NothingToMigrate` on a fresh deploy, so it's a real place for a
   future version to land field-by-field changes rather than a promise.

Every contract that adopts this exposes the same six entrypoints —
`propose_upgrade`, `approve_upgrade`, `cancel_upgrade`, `migrate`, plus
read-only `get_signers`, `get_upgrade_threshold`, `get_pending_upgrade`,
`get_storage_version` — on top of whatever it already had. `cancel_upgrade`
can be called by any single signer, not the full threshold: a minority
should always be able to block an in-flight upgrade it didn't sign off on,
even though it takes the full threshold to push one through.

**Known limitation, on purpose:** the signer set and threshold are immutable
after `initialize` — there is no signer-rotation flow. Rotating signers
safely (without a majority being able to silently lock out a minority, or
vice versa) is its own governance design problem, and shipping it alongside
the upgrade mechanism itself would roughly double the surface being trusted
in one pass. Left as a deliberate follow-up rather than rushed in here.

Each contract's per-error-code table below lists the governance error
variants it inherited from `governance-guard`, at whatever numeric offset
came next in that contract's existing `Error` enum — the variant names are
identical across all four, only the numbers differ.

## Prerequisites

- Rust with the `wasm32v1-none` target: `rustup target add wasm32v1-none`
  (Soroban does not yet support `wasm32-unknown-unknown` on Rust 1.82+;
  use `wasm32v1-none` with Rust 1.84+, or Rust 1.81 or earlier for the old target.)
- [Stellar CLI](https://developers.stellar.org/docs/tools/cli/install-cli) (`stellar` binary), used for deploying/invoking contracts.

## Build

```sh
stellar contract build
# or, per-contract:
cargo build --target wasm32v1-none --release -p guildworkman-escrow
```

Wasm output lands in `target/wasm32v1-none/release/*.wasm`.

## Test

```sh
cargo test --workspace
```

Each contract has unit tests under `contracts/<name>/src/test.rs` using
`soroban-sdk`'s `testutils`. Current coverage:

- **escrow** (5 tests): happy-path completion pays the worker and drains the
  contract's balance; cancellation refunds the client; a raised dispute
  resolved in the worker's favor pays the worker; creating a duplicate
  `appointment_id` is rejected; confirming an already-completed appointment
  is rejected.
- **reputation** (3 tests): submitting reviews updates the count/sum
  aggregate and average correctly; reviewing the same `appointment_id` twice
  is rejected; a rating outside 1-5 is rejected.
- **loyalty-token** (6 tests): mint increases balance; transfer moves balance
  between accounts; transferring more than the balance fails; approve +
  transfer_from spends down the allowance correctly; burn reduces balance;
  the admin can rotate the minter and the new minter can mint.
- **loyalty-emissions** (24 tests): linear vesting reports the right amount at
  the start, midpoint, and end of a stream and stays capped afterwards; a
  cliff blocks vesting until it's reached; `claim` mints the vested delta and
  incremental claims only mint what's newly vested; per-account and global
  rate limits clamp a claim to the remaining window budget and open a fresh
  budget the next window; the `claimable` view reflects both vesting and rate
  limits; `reclaim` returns the unclaimed remainder only after the deadline and
  blocks further claims; and adversarial paths — double-init, bad config, bad
  schedule params, reclaim-before-vesting-end, double-claim, double-reclaim,
  claiming a missing/reclaimed schedule, and looping claims across many windows
  never mints more than a schedule's `total`.
- **dispute-resolution** (27 tests): the full commit → reveal → resolve →
  withdraw lifecycle pays a plaintiff- or defendant-majority jury out of the
  losers' stakes; a no-show who committed but never revealed is slashed and
  their stake flows to the winners; a tie or a below-quorum turnout refunds
  every staker (including non-revealers) with no slashing; the state machine
  rejects committing/revealing/resolving/withdrawing out of phase; and
  adversarial paths — double-init, bad config, duplicate/same-party disputes,
  double commit/reveal/resolve/withdraw, revealing with the wrong vote or salt,
  a copycat replaying another juror's commitment being unable to reveal it, and
  a slashed loser never draining the pot via repeated withdrawals.

These are unit tests against the in-process Soroban test host
(`Env::default()` + `mock_all_auths()`), not integration tests against a real
network — they don't cover cross-contract calls, real fee/resource limits, or
multi-node consensus behavior.

## Deploy (testnet example)

```sh
stellar keys generate deployer --network testnet --fund

stellar contract deploy \
  --wasm target/wasm32v1-none/release/guildworkman_escrow.wasm \
  --source deployer --network testnet

stellar contract invoke \
  --id <ESCROW_CONTRACT_ID> --source deployer --network testnet \
  -- initialize --admin <ADMIN_ADDRESS>
```

Repeat `deploy` for `guildworkman_reputation.wasm`,
`guildworkman_loyalty_token.wasm`, `guildworkman_loyalty_emissions.wasm`, and
`guildworkman_dispute_resolution.wasm`, then call each contract's `initialize`
once. For the emission engine to be
able to mint, point it at the token in its `initialize` and then hand it the
token's `minter` role:

```sh
stellar contract invoke --id $EMISSIONS --source admin --network testnet \
  -- initialize --admin $ADMIN_ADDR --token $LOYALTY_CONTRACT_ID \
     --config '{ "window": 17280, "account_cap": "1000", "global_cap": "100000" }'

# Hand the token's minter role to the emissions contract.
stellar contract invoke --id $LOYALTY --source admin --network testnet \
  -- set_minter --new_minter $EMISSIONS
```

## Contract interfaces

### escrow

- `initialize(admin: Address, governance_init: GovernanceInit)`
- `create_appointment(appointment_id: u64, client: Address, worker: Address, token: Address, amount: i128)`
- `confirm_completion(appointment_id: u64)` — client-only, pays the worker
- `cancel_appointment(appointment_id: u64)` — client-only, refunds the client
- `raise_dispute(appointment_id: u64, caller: Address)` — client or worker
- `resolve_dispute(appointment_id: u64, refund_to_client: bool)` — admin-only
- `get_appointment(appointment_id: u64) -> Appointment`
- `propose_upgrade`, `approve_upgrade`, `cancel_upgrade`, `migrate`, `get_signers`, `get_upgrade_threshold`, `get_pending_upgrade`, `get_storage_version` — see [Upgrade governance](#upgrade-governance)

#### Storage layout

| `DataKey` variant | Storage | Holds |
|---|---|---|
| `Admin` | instance | The dispute arbiter's `Address`, set once in `initialize`. |
| `Appointment(u64)` | persistent | An `Appointment { client, worker, token, amount, status }` keyed by `appointment_id`. `status` is one of `Funded`, `Completed`, `Cancelled`, `Disputed`, `Resolved`. |

#### Errors

| Variant | Code | Meaning |
|---|---|---|
| `AlreadyInitialized` | 1 | `initialize` called more than once. |
| `NotInitialized` | 2 | `resolve_dispute` called before `initialize`. |
| `AppointmentExists` | 3 | `create_appointment` reused an existing `appointment_id`. |
| `AppointmentNotFound` | 4 | No appointment stored under that `appointment_id`. |
| `InvalidStatus` | 5 | The requested transition doesn't apply to the appointment's current status (e.g. confirming a non-`Funded` appointment). |
| `InvalidAmount` | 6 | `amount <= 0` in `create_appointment`. |
| `NotAParticipant` | 7 | `raise_dispute` called by an address that is neither the client nor the worker. |
| `GovernanceAlreadyInitialized` | 8 | `initialize` called more than once (surfaced via the governance guard). |
| `GovernanceNotInitialized` | 9 | A governance call before `initialize`. |
| `InvalidThreshold` | 10 | `threshold` is `0` or exceeds the number of signers. |
| `DuplicateSigner` | 11 | The same address appears twice in `signers`. |
| `NotASigner` | 12 | `propose_upgrade`/`approve_upgrade`/`cancel_upgrade`/`migrate` called by a non-signer. |
| `NoPendingUpgrade` | 13 | `approve_upgrade`/`cancel_upgrade` with nothing proposed. |
| `AlreadyApproved` | 14 | The same signer approving the same proposal twice. |
| `ProposalExpired` | 15 | `approve_upgrade` more than ~7 days after `propose_upgrade`. |
| `HashMismatch` | 16 | `approve_upgrade` with a hash that doesn't match the pending proposal. |
| `AlreadyMigrated` | 17 | `migrate` targeting a version already applied or behind the current one. |
| `NothingToMigrate` | 18 | `migrate` called when the stored version is already current. |

#### CLI usage

```sh
# One-time setup — a 2-of-3 signer governance guard on top of the admin
stellar contract invoke --id $ESCROW --source admin --network testnet \
  -- initialize --admin $ADMIN_ADDR \
     --governance_init '{"signers":["'$SIGNER_1'","'$SIGNER_2'","'$SIGNER_3'"],"threshold":2}'

# Client books worker WORKER_ADDR, depositing 10000 units of TOKEN_ADDR, appointment id 1
stellar contract invoke --id $ESCROW --source client --network testnet \
  -- create_appointment --appointment_id 1 --client $CLIENT_ADDR \
     --worker $WORKER_ADDR --token $TOKEN_ADDR --amount 10000

# Client confirms the job is done -> pays the worker
stellar contract invoke --id $ESCROW --source client --network testnet \
  -- confirm_completion --appointment_id 1

# Client cancels before completion -> refunds the client
stellar contract invoke --id $ESCROW --source client --network testnet \
  -- cancel_appointment --appointment_id 1

# Either party raises a dispute
stellar contract invoke --id $ESCROW --source client --network testnet \
  -- raise_dispute --appointment_id 1 --caller $CLIENT_ADDR

# Admin resolves the dispute in the worker's favor
stellar contract invoke --id $ESCROW --source admin --network testnet \
  -- resolve_dispute --appointment_id 1 --refund_to_client false

# Read appointment state
stellar contract invoke --id $ESCROW --source admin --network testnet \
  -- get_appointment --appointment_id 1
```

### reputation

> **This section is stale and predates this change** — it describes a
> simpler `submit_review`/`Rating{count,sum}` interface that doesn't match
> `contracts/reputation/src/lib.rs`, which actually has `Config`,
> `submit_attestation` with stake weighting and time decay, admin-managed
> stake, and rate limiting. That drift already existed before this PR and
> rewriting it is unrelated to #16, so it's left alone rather than folded in
> here — flagging it instead of silently leaving it wrong. What *is* true as
> of this PR: `initialize` now also takes a `governance_init: GovernanceInit`,
> and the contract has the same `propose_upgrade`/`approve_upgrade`/
> `cancel_upgrade`/`migrate` surface described in
> [Upgrade governance](#upgrade-governance) — see the source and
> `src/test.rs` for the actual current interface.

- `submit_review(appointment_id: u64, client: Address, worker: Address, rating: u32, comment: String)` — 1-5 stars, one review per `appointment_id`
- `get_rating(worker: Address) -> Rating { count, sum }`
- `get_average_rating_x100(worker: Address) -> u32` — e.g. `437` = 4.37 stars
- `get_review(worker: Address, index: u32) -> Option<Review>`
- `get_review_count(worker: Address) -> u32`

#### Storage layout

| `DataKey` variant | Storage | Holds |
|---|---|---|
| `Reviewed(u64)` | persistent | A `bool` flag per `appointment_id`, used only to enforce one review per appointment. |
| `Rating(Address)` | persistent | A `Rating { count, sum }` aggregate per worker; `sum` is the running total of all star ratings given. |
| `Review(Address, u32)` | persistent | An individual `Review { client, rating, comment }`, keyed by `(worker, index)` where `index` is a per-worker sequence number starting at 0. |
| `ReviewCount(Address)` | persistent | The next free `index` for a given worker — also doubles as the total review count. |

#### Errors

| Variant | Code | Meaning |
|---|---|---|
| `InvalidRating` | 1 | `rating` outside the 1-5 range. |
| `AlreadyReviewed` | 2 | `submit_review` called twice for the same `appointment_id`. |
| `NoReviews` | 3 | `get_average_rating_x100` called for a worker with zero reviews (avoids a divide-by-zero). |

#### CLI usage

```sh
stellar contract invoke --id $REPUTATION --source client --network testnet \
  -- submit_review --appointment_id 1 --client $CLIENT_ADDR \
     --worker $WORKER_ADDR --rating 5 --comment "Great job, on time"

stellar contract invoke --id $REPUTATION --source admin --network testnet \
  -- get_rating --worker $WORKER_ADDR

stellar contract invoke --id $REPUTATION --source admin --network testnet \
  -- get_average_rating_x100 --worker $WORKER_ADDR

stellar contract invoke --id $REPUTATION --source admin --network testnet \
  -- get_review --worker $WORKER_ADDR --index 0

stellar contract invoke --id $REPUTATION --source admin --network testnet \
  -- get_review_count --worker $WORKER_ADDR
```

### loyalty-token

- `initialize(admin: Address, minter: Address, decimals: u32, name: String, symbol: String, governance_init: GovernanceInit)`
- `set_minter(new_minter: Address)` — admin-only
- `mint(to: Address, amount: i128)` — minter-only
- `transfer`, `transfer_from`, `approve`, `allowance`, `burn`, `balance`, `decimals`, `name`, `symbol` — standard SEP-41 token surface
- `propose_upgrade`, `approve_upgrade`, `cancel_upgrade`, `migrate`, `get_signers`, `get_upgrade_threshold`, `get_pending_upgrade`, `get_storage_version` — see [Upgrade governance](#upgrade-governance)

#### Storage layout

| `DataKey` variant | Storage | Holds |
|---|---|---|
| `Admin` | instance | The admin `Address`, allowed to call `set_minter`. |
| `Minter` | instance | The only `Address` allowed to call `mint`. |
| `Metadata` | instance | `TokenMetadata { decimals, name, symbol }`. |
| `Balance(Address)` | persistent | The `i128` token balance for a given holder. |
| `Allowance(Address, Address)` | temporary | An `AllowanceValue { amount, expiration_ledger }` for `(from, spender)`, cleared once `expiration_ledger` passes. |

#### Errors

| Variant | Code | Meaning |
|---|---|---|
| `AlreadyInitialized` | 1 | `initialize` called more than once. |
| `NotInitialized` | 2 | `mint`/`set_minter` called before `initialize`. |
| `InsufficientBalance` | 3 | `transfer`/`transfer_from`/`burn` amount exceeds the sender's balance. |
| `InsufficientAllowance` | 4 | `transfer_from` amount exceeds the spender's remaining allowance. |
| `InvalidAmount` | 5 | A negative/zero amount passed where a positive amount is required. |
| `NotAuthorized` | 6 | Reserved for authorization failures; current checks rely on `require_auth()` panicking directly rather than returning this variant. |
| `GovernanceAlreadyInitialized` | 7 | `initialize` called more than once (surfaced via the governance guard). |
| `GovernanceNotInitialized` | 8 | A governance call before `initialize`. |
| `InvalidThreshold` | 9 | `threshold` is `0` or exceeds the number of signers. |
| `DuplicateSigner` | 10 | The same address appears twice in `signers`. |
| `NotASigner` | 11 | `propose_upgrade`/`approve_upgrade`/`cancel_upgrade`/`migrate` called by a non-signer. |
| `NoPendingUpgrade` | 12 | `approve_upgrade`/`cancel_upgrade` with nothing proposed. |
| `AlreadyApproved` | 13 | The same signer approving the same proposal twice. |
| `ProposalExpired` | 14 | `approve_upgrade` more than ~7 days after `propose_upgrade`. |
| `HashMismatch` | 15 | `approve_upgrade` with a hash that doesn't match the pending proposal. |
| `AlreadyMigrated` | 16 | `migrate` targeting a version already applied or behind the current one. |
| `NothingToMigrate` | 17 | `migrate` called when the stored version is already current. |

#### CLI usage

```sh
stellar contract invoke --id $LOYALTY --source admin --network testnet \
  -- initialize --admin $ADMIN_ADDR --minter $MINTER_ADDR \
     --decimals 2 --name '"GuildWorkman Points"' --symbol '"GWP"' \
     --governance_init '{"signers":["'$SIGNER_1'","'$SIGNER_2'","'$SIGNER_3'"],"threshold":2}'

stellar contract invoke --id $LOYALTY --source admin --network testnet \
  -- set_minter --new_minter $NEW_MINTER_ADDR

stellar contract invoke --id $LOYALTY --source minter --network testnet \
  -- mint --to $CLIENT_ADDR --amount 500

stellar contract invoke --id $LOYALTY --source admin --network testnet \
  -- balance --id $CLIENT_ADDR

stellar contract invoke --id $LOYALTY --source client --network testnet \
  -- transfer --from $CLIENT_ADDR --to $WORKER_ADDR --amount 100

stellar contract invoke --id $LOYALTY --source client --network testnet \
  -- approve --from $CLIENT_ADDR --spender $SPENDER_ADDR \
     --amount 200 --expiration_ledger 5000000

stellar contract invoke --id $LOYALTY --source admin --network testnet \
  -- allowance --from $CLIENT_ADDR --spender $SPENDER_ADDR

stellar contract invoke --id $LOYALTY --source spender --network testnet \
  -- transfer_from --spender $SPENDER_ADDR --from $CLIENT_ADDR \
     --to $WORKER_ADDR --amount 150

stellar contract invoke --id $LOYALTY --source client --network testnet \
  -- burn --from $CLIENT_ADDR --amount 50

stellar contract invoke --id $LOYALTY --source admin --network testnet \
  -- decimals
stellar contract invoke --id $LOYALTY --source admin --network testnet \
  -- name
stellar contract invoke --id $LOYALTY --source admin --network testnet \
  -- symbol
```

### loyalty-emissions

An emission engine layered on top of `loyalty-token`. It holds the token's
`minter` role and releases rewards as **linear vesting streams** rather than
lump-sum mints, gated by anti-abuse rate limits, with an admin reclaim path for
allocations left unclaimed past a deadline.

- `initialize(admin: Address, token: Address, config: Config, governance_init: GovernanceInit)` —
  `config` is `{ window: u32, account_cap: i128, global_cap: i128 }`
- `create_schedule(beneficiary: Address, total: i128, start: u32, cliff: u32, duration: u32, reclaimable_after: u32)` — admin-only; `start: 0` means "start now"
- `claim(beneficiary: Address) -> i128` — beneficiary-authorized; mints the
  vested-and-unclaimed amount clamped to the current rate-limit budget, returns
  the amount minted
- `reclaim(beneficiary: Address) -> i128` — admin-only; after `reclaimable_after`,
  marks the schedule reclaimed and returns the never-minted remainder
- `vested(beneficiary) -> i128`, `claimable(beneficiary) -> i128`,
  `get_schedule(beneficiary) -> Schedule`, `get_config() -> Config`,
  `get_admin() -> Address`, `get_token() -> Address` — read-only views
- `propose_upgrade`, `approve_upgrade`, `cancel_upgrade`, `migrate`, `get_signers`, `get_upgrade_threshold`, `get_pending_upgrade`, `get_storage_version` — see [Upgrade governance](#upgrade-governance)

#### Vesting & rate-limit model

A schedule is `{ total, start, cliff, duration }`. At ledger `t`:

- before `start + cliff` → nothing vested;
- `start + cliff` .. `start + duration` → `vested = total * (t - start) / duration`;
- at/after `start + duration` → fully vested (`total`).

`claimable = vested - claimed`, then clamped to the smaller of the account's and
the global remaining budget for the current window. Windows are fixed tumbling
windows of `window` ledgers (`ledger / window`): each account may claim at most
`account_cap` per window, and all accounts combined at most `global_cap` per
window. `create_schedule` requires `reclaimable_after >= start + duration`, so
the admin can never reclaim allocations that are still vesting.

#### Storage layout

| `DataKey` variant | Storage | Holds |
|---|---|---|
| `Admin` | instance | The admin `Address`; the only caller allowed to `create_schedule`/`reclaim`. |
| `Token` | instance | The `loyalty-token` contract `Address` this engine mints through. |
| `Config` | instance | `Config { window, account_cap, global_cap }`, fixed at `initialize`. |
| `Schedule(Address)` | persistent | A `Schedule { total, claimed, start, cliff, duration, reclaimable_after, reclaimed }` per beneficiary. |
| `AccountWindow(Address, u64)` | temporary | Units a given account has claimed in a given window index; enforces the per-account cap. Auto-expires ~2 windows after use. |
| `GlobalWindow(u64)` | temporary | Units all accounts have claimed in a given window index; enforces the global cap. |

#### Errors

| Variant | Code | Meaning |
|---|---|---|
| `AlreadyInitialized` | 1 | `initialize` called more than once. |
| `NotInitialized` | 2 | A method needing state was called before `initialize`. |
| `InvalidConfig` | 3 | `window == 0` or a non-positive cap passed to `initialize`. |
| `InvalidSchedule` | 4 | `total <= 0`, `duration == 0`, `cliff > duration`, or `reclaimable_after < start + duration`. |
| `ScheduleExists` | 5 | `create_schedule` for a beneficiary that already has one. |
| `ScheduleNotFound` | 6 | No schedule stored for that beneficiary. |
| `NothingToClaim` | 7 | Nothing has vested beyond what's already claimed. |
| `AccountRateLimited` | 8 | The account's per-window budget is already exhausted. |
| `GlobalRateLimited` | 9 | The global per-window budget is already exhausted. |
| `NotYetReclaimable` | 10 | `reclaim` called before `reclaimable_after`. |
| `AlreadyReclaimed` | 11 | `claim`/`reclaim` on an already-reclaimed schedule. |
| `NothingToReclaim` | 12 | `reclaim` when the schedule was already fully claimed. |
| `GovernanceAlreadyInitialized` | 13 | `initialize` called more than once (surfaced via the governance guard). |
| `GovernanceNotInitialized` | 14 | A governance call before `initialize`. |
| `InvalidThreshold` | 15 | `threshold` is `0` or exceeds the number of signers. |
| `DuplicateSigner` | 16 | The same address appears twice in `signers`. |
| `NotASigner` | 17 | `propose_upgrade`/`approve_upgrade`/`cancel_upgrade`/`migrate` called by a non-signer. |
| `NoPendingUpgrade` | 18 | `approve_upgrade`/`cancel_upgrade` with nothing proposed. |
| `AlreadyApproved` | 19 | The same signer approving the same proposal twice. |
| `ProposalExpired` | 20 | `approve_upgrade` more than ~7 days after `propose_upgrade`. |
| `HashMismatch` | 21 | `approve_upgrade` with a hash that doesn't match the pending proposal. |
| `AlreadyMigrated` | 22 | `migrate` targeting a version already applied or behind the current one. |
| `NothingToMigrate` | 23 | `migrate` called when the stored version is already current. |

#### Authorization & safety notes

- **Trust root is the admin.** Only the admin can register or reclaim
  schedules. `claim` is authorized by the beneficiary themselves.
- **The engine, not the backend, is the token's `minter`.** After deployment
  the admin must call `loyalty-token.set_minter` with the emission contract's
  address; the token then only mints through vesting + rate-limit checks.
- **Reclaim cannot rug a vesting stream.** Because `reclaimable_after` is forced
  to be at or after the vesting end, the admin can only reclaim what a
  beneficiary chose not to claim after the stream fully vested — never
  still-vesting funds.
- **Reclaim is un-mint, not burn.** Reclaimed units were never minted, so no
  balance is touched; the schedule is simply closed.

#### CLI usage

```sh
# One-time setup (see the Deploy section for wiring the minter role).
stellar contract invoke --id $EMISSIONS --source admin --network testnet \
  -- initialize --admin $ADMIN_ADDR --token $LOYALTY_CONTRACT_ID \
     --config '{ "window": 17280, "account_cap": "1000", "global_cap": "100000" }' \
     --governance_init '{"signers":["'$SIGNER_1'","'$SIGNER_2'","'$SIGNER_3'"],"threshold":2}'

# Admin registers a stream: 5000 points vesting over 30 days (starting now),
# reclaimable after ~31 days if left unclaimed.
stellar contract invoke --id $EMISSIONS --source admin --network testnet \
  -- create_schedule --beneficiary $WORKER_ADDR --total 5000 \
     --start 0 --cliff 0 --duration 518400 --reclaimable_after 535680

# Worker claims whatever has vested and fits the rate limit.
stellar contract invoke --id $EMISSIONS --source worker --network testnet \
  -- claim --beneficiary $WORKER_ADDR

# Read-only views.
stellar contract invoke --id $EMISSIONS --source admin --network testnet \
  -- vested --beneficiary $WORKER_ADDR
stellar contract invoke --id $EMISSIONS --source admin --network testnet \
  -- claimable --beneficiary $WORKER_ADDR

# Admin reclaims the unclaimed remainder after the deadline.
stellar contract invoke --id $EMISSIONS --source admin --network testnet \
  -- reclaim --beneficiary $WORKER_ADDR
```

### dispute-resolution

> ⚠️ **v1, unaudited, no appeals** — single-round staked-jury voting with no sybil-resistant/weighted jury selection. Read [Security considerations / known limitations](#security-considerations--known-limitations) before integrating.

Decentralized dispute resolution: a staked jury decides the outcome via
commit-reveal voting, and the majority is paid out of the slashed stakes of the
minority and no-shows. This is the trust-minimized counterpart to `escrow`'s
admin-only `resolve_dispute` — an integration could have `escrow` read a
resolved dispute's `Outcome` instead of trusting a single arbiter.

- `initialize(admin: Address, token: Address, config: Config)` — `config` is
  `{ juror_stake: i128, min_jurors: u32, commit_window: u32, reveal_window: u32 }`
- `open_dispute(dispute_id: u64, plaintiff: Address, defendant: Address)` —
  admin-only; starts the commit phase (`commit_deadline = now + commit_window`,
  `reveal_deadline = commit_deadline + reveal_window`)
- `commit_vote(dispute_id: u64, juror: Address, commitment: BytesN<32>)` —
  juror-authorized; stakes `juror_stake` and records a hidden vote. Accepted
  only while `now <= commit_deadline`
- `reveal_vote(dispute_id: u64, juror: Address, vote: bool, salt: BytesN<32>)` —
  juror-authorized; discloses the vote (`true` = plaintiff, `false` = defendant)
  during the reveal phase. `vote`+`salt` must hash to the committed value
- `resolve(dispute_id: u64) -> Outcome` — permissionless; after
  `reveal_deadline`, tallies revealed votes and fixes the per-winner reward
- `withdraw(dispute_id: u64, juror: Address) -> i128` — juror-authorized;
  after resolution, pays a winning juror `juror_stake + reward_per_winner`,
  refunds every staker on a tie/quorum-failure, and slashes losers/no-shows
- `compute_commitment(juror: Address, vote: bool, salt: BytesN<32>) -> BytesN<32>` —
  helper so callers build the commitment with the exact domain separation the
  contract enforces
- `get_dispute(dispute_id) -> Dispute`, `get_juror(dispute_id, juror) -> Juror`,
  `get_config() -> Config`, `get_admin() -> Address`, `get_token() -> Address`
  — read-only views

#### Commit-reveal & incentive model

A juror votes in two steps so no one can copy the current leader or be coerced
for a visible vote:

1. **Commit**: submit `commitment = sha256(salt || vote_byte || juror_xdr)` and
   stake `juror_stake`. Binding the juror's own address into the preimage means
   a copycat who replays someone else's commitment can never produce a matching
   reveal from their own address.
2. **Reveal**: disclose `(vote, salt)`; the contract recomputes the hash and, on
   a match, records the vote and bumps the tally.

At `resolve` the side with more revealed votes wins. The **slashed pot** — the
stakes of the minority voters *and* of everyone who committed but never revealed
— is split evenly among the winners (integer division; any remainder dust stays
in the contract). On a **tie** or a turnout below `min_jurors` (`QuorumFailed`)
nobody is slashed and every staker reclaims their stake. Withdrawals use a pull
pattern, so resolution never loops over an unbounded juror set, and a juror is
marked settled before any transfer (checks-effects-interactions).

#### Storage layout

| `DataKey` variant | Storage | Holds |
|---|---|---|
| `Admin` | instance | The `Address` allowed to `open_dispute`, set once in `initialize`. |
| `Token` | instance | The staking-token contract `Address` jurors post collateral in and are paid from. |
| `Config` | instance | `Config { juror_stake, min_jurors, commit_window, reveal_window }`, fixed at `initialize`. |
| `Dispute(u64)` | persistent | A `Dispute { plaintiff, defendant, commit_deadline, reveal_deadline, juror_count, yes_count, no_count, outcome, reward_per_winner, resolved }` per `dispute_id`. |
| `Juror(u64, Address)` | persistent | A `Juror { commitment, revealed, vote, withdrawn }` per `(dispute_id, juror)`. |

#### Errors

| Variant | Code | Meaning |
|---|---|---|
| `AlreadyInitialized` | 1 | `initialize` called more than once. |
| `NotInitialized` | 2 | A method needing state was called before `initialize`. |
| `InvalidConfig` | 3 | A non-positive stake, or a zero quorum/commit/reveal window, passed to `initialize`. |
| `DisputeExists` | 4 | `open_dispute` reused an existing `dispute_id`. |
| `DisputeNotFound` | 5 | No dispute stored under that `dispute_id`. |
| `NotCommitPhase` | 6 | `commit_vote` after the commit deadline. |
| `NotRevealPhase` | 7 | `reveal_vote` outside the reveal window. |
| `AlreadyCommitted` | 8 | `commit_vote` twice for the same `(dispute, juror)`. |
| `NotCommitted` | 9 | `reveal_vote`/`withdraw`/`get_juror` for a juror who never committed. |
| `AlreadyRevealed` | 10 | `reveal_vote` twice. |
| `InvalidReveal` | 11 | Revealed `(vote, salt)` doesn't hash to the stored commitment. |
| `NotResolvable` | 12 | `resolve` called on or before the reveal deadline. |
| `AlreadyResolved` | 13 | `resolve` called on an already-resolved dispute. |
| `NotResolved` | 14 | `withdraw` before the dispute is resolved. |
| `AlreadyWithdrawn` | 15 | `withdraw` called twice by the same juror. |
| `NothingToWithdraw` | 16 | `withdraw` by a slashed juror (minority voter or no-show) on a decided dispute. |
| `SameParties` | 17 | `open_dispute` with `plaintiff == defendant`. |

#### CLI usage

```sh
# One-time setup: stake 100 units, quorum of 3, ~1h commit + ~1h reveal windows.
stellar contract invoke --id $DISPUTES --source admin --network testnet \
  -- initialize --admin $ADMIN_ADDR --token $TOKEN_ADDR \
     --config '{ "juror_stake": "100", "min_jurors": 3, "commit_window": 720, "reveal_window": 720 }'

# Admin opens a dispute between a client (plaintiff) and worker (defendant).
stellar contract invoke --id $DISPUTES --source admin --network testnet \
  -- open_dispute --dispute_id 1 --plaintiff $CLIENT_ADDR --defendant $WORKER_ADDR

# A juror builds their commitment off-chain (favoring the plaintiff), then stakes + commits.
stellar contract invoke --id $DISPUTES --source juror --network testnet \
  -- compute_commitment --juror $JUROR_ADDR --vote true --salt $SALT_32B_HEX
stellar contract invoke --id $DISPUTES --source juror --network testnet \
  -- commit_vote --dispute_id 1 --juror $JUROR_ADDR --commitment $COMMITMENT_HEX

# During the reveal window, the juror discloses their vote and salt.
stellar contract invoke --id $DISPUTES --source juror --network testnet \
  -- reveal_vote --dispute_id 1 --juror $JUROR_ADDR --vote true --salt $SALT_32B_HEX

# After the reveal window, anyone tallies the result.
stellar contract invoke --id $DISPUTES --source anyone --network testnet \
  -- resolve --dispute_id 1

# Each juror settles: winners collect stake + reward, losers are slashed.
stellar contract invoke --id $DISPUTES --source juror --network testnet \
  -- withdraw --dispute_id 1 --juror $JUROR_ADDR
```

## Security considerations / known limitations

- **Unaudited.** None of these contracts have had an independent security
  review. Do not move real funds through `escrow` or mint real value through
  `loyalty-token` on mainnet before getting one.
- **Trusted token contract.** `escrow` assumes the `token` address passed to
  `create_appointment` behaves like a well-formed SEP-41/Stellar Asset
  Contract. A malicious or buggy token contract (e.g. one that lies about
  transfer success) could break escrow's invariants.
- **No partial completion or partial refunds.** `escrow` is all-or-nothing:
  the full amount goes to either the client or the worker. There's no
  mechanism for a partially-completed job.
- **Single point of trust for admin.** The `escrow` admin unilaterally
  decides disputes, the `loyalty-token` admin unilaterally controls who can
  mint, and the `loyalty-emissions` admin unilaterally registers and reclaims
  vesting schedules. All are single-key roles with no timelock or on-chain
  governance for their day-to-day actions — compromising that key
  compromises the contract's normal operation. Note `loyalty-emissions`
  deliberately blocks reclaiming still-vesting funds
  (`reclaimable_after >= start + duration`), so even a compromised admin cannot
  claw back what has already vested to a beneficiary before they claim it.
  Upgrading a contract's code specifically *is* multi-sig gated (see
  [Upgrade governance](#upgrade-governance)) — a compromised admin key alone
  cannot ship new code, only a signer set reaching threshold can — but that
  guard was scoped narrowly to just the upgrade path, not extended to
  `admin`'s existing day-to-day powers.
- **No spam/rate limiting beyond one-review-per-appointment.** `reputation`
  only prevents double-reviewing the same `appointment_id`; it does not
  prevent a client and worker from colluding to create fake appointments
  (that responsibility sits with whatever system calls `create_appointment`
  and `submit_review` with real appointment IDs — today, nothing does, since
  the Java backend isn't integrated yet).
- **Upgrade path exists; pause does not.** All four contracts have a
  multi-sig-gated code-upgrade and storage-migration path (see
  [Upgrade governance](#upgrade-governance)) — fixing a deployed bug no
  longer requires deploying a new contract and migrating callers manually.
  There's still no emergency pause switch, and the governance signer set is
  fixed at `initialize` with no rotation flow yet.
- **The upgrade path's actual Wasm swap is untested by `cargo test`.**
  `propose_upgrade`/`approve_upgrade` crossing the configured threshold
  calls `update_current_contract_wasm`, which requires the target hash to
  already be uploaded on the ledger — there's no such artifact inside a
  plain unit test run. Every test that exercises the governance flow through
  a real contract stops one approval short of the threshold on purpose; the
  underlying threshold/replay/expiry math is covered directly in
  `contracts/governance-guard`'s own test suite, and the SDK call itself was
  verified against its documented behavior rather than exercised live. A
  testnet deploy-and-upgrade dry run is the natural next verification step
  before this ships anywhere real funds move through.
- **Comments are not authenticated content.** `reputation`'s `comment` field
  is an arbitrary `String` supplied by the reviewer with no length cap or
  content moderation — treat it as untrusted user input wherever it's
  displayed.
- **Jury sybil / stake-weighting.** `dispute-resolution` gives every juror who
  posts `juror_stake` exactly one vote and lets anyone join a dispute during the
  commit phase. It resists *free* sybils (each identity must lock real
  collateral) and hidden-vote manipulation (commit-reveal), but it does **not**
  defend against a well-capitalized actor funding many jurors to swing a verdict
  — there is no random jury selection, reputation weighting, or per-identity
  gating. Set `juror_stake`/`min_jurors` relative to the value at stake, and
  treat this as a coordination mechanism among semi-trusted jurors, not a
  Kleros-grade court. Reputation-weighted / randomized jury selection (building
  on the attestation-based reputation scoring in #20) is a deliberate v1 scope
  boundary tracked in #29, not an oversight.
- **No appeals and majority-takes-all slashing.** A dispute resolves in a single
  round; there is no appeal path, and honest jurors who happen to land in the
  minority are slashed alongside malicious ones. A dishonest majority both wins
  the verdict and confiscates the honest minority's stake. Ties and below-quorum
  turnouts are handled safely (everyone is refunded, no slashing), and integer
  division of the slashed pot can leave at most `winner_count - 1` units of dust
  in the contract. Slashing the whole minority is an intentional Schelling-point
  incentive (commit-reveal is what makes it defensible), but softening it for
  close calls — margin-based partial refunds or an appeal round — is tracked as a
  candidate v2 direction in #29.
- **Reveal-phase liveness assumption.** A juror who commits but never reveals is
  treated as a loser and slashed (on a decided outcome), which is the intended
  anti-griefing incentive — but it also means a juror censored or offline during
  the reveal window loses their stake. Size `reveal_window` accordingly.

## Notes / follow-ups

- `escrow` expects a standard Soroban token contract address (e.g. a Stellar
  Asset Contract wrapping USDC or XLM) for the `token` parameter — it does not
  handle native fiat payments, which stay on Paystack in the existing backend.
- These contracts have not been audited. Get an independent security review
  before moving any real funds through `escrow` or `loyalty-token` on mainnet.
- Governance signer rotation. Right now the signer set and threshold are
  fixed forever at `initialize` — see
  [Upgrade governance](#upgrade-governance) for why that's a deliberate
  scope boundary rather than an oversight, and a real candidate for a
  follow-up issue.
- Real dispute resolution for `escrow` beyond a single admin call, and a
  genuine storage migration exercising `migrate`'s version-transform path
  (nothing has needed one yet — every contract is still on storage version 1).

## License

MIT — see [LICENSE](./LICENSE).
