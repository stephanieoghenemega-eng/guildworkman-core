# sabiconnect-contracts

Soroban (Stellar) smart contracts for Sabi-Connect, the skilled-worker
booking marketplace. This workspace has three independent contracts:

| Contract | Path | Purpose |
|---|---|---|
| `escrow` | `contracts/escrow` | Holds a client's payment for a booked appointment until the client confirms the job is done; releases funds to the skilled worker, refunds on cancellation, and supports admin-arbitrated disputes. |
| `reputation` | `contracts/reputation` | Stores one immutable review per completed appointment and keeps a running rating aggregate per skilled worker. |
| `loyalty-token` | `contracts/loyalty-token` | A SEP-41-style fungible token used to reward clients/workers with points on completed appointments. Only a designated `minter` (the backend's service account) can mint. |

These mirror the domain already implemented server-side in
[`SabiConnect-Backend`](https://github.com/workman-labs/SabiConnect-Backend)
(`AppointmentService`, `ReviewService`, `TransactionService`), moving the
trust-sensitive parts of that flow — holding money, recording reviews,
issuing rewards — on-chain.

## Prerequisites

- Rust with the `wasm32v1-none` target: `rustup target add wasm32v1-none`
  (Soroban does not yet support `wasm32-unknown-unknown` on Rust 1.82+;
  use `wasm32v1-none` with Rust 1.84+, or Rust 1.81 or earlier for the old target.)
- [Stellar CLI](https://developers.stellar.org/docs/tools/cli/install-cli) (`stellar` binary), used for deploying/invoking contracts.

## Build

```sh
stellar contract build
# or, per-contract:
cargo build --target wasm32v1-none --release -p escrow
```

Wasm output lands in `target/wasm32v1-none/release/*.wasm`.

## Test

```sh
cargo test --workspace
```

Each contract has unit tests under `contracts/<name>/src/test.rs` using
`soroban-sdk`'s `testutils`.

## Deploy (testnet example)

```sh
stellar keys generate deployer --network testnet --fund

stellar contract deploy \
  --wasm target/wasm32v1-none/release/escrow.wasm \
  --source deployer --network testnet

stellar contract invoke \
  --id <ESCROW_CONTRACT_ID> --source deployer --network testnet \
  -- initialize --admin <ADMIN_ADDRESS>
```

Repeat `deploy` for `reputation.wasm` and `loyalty_token.wasm`, then call
each contract's `initialize` once.

## Contract interfaces

### escrow

- `initialize(admin: Address)`
- `create_appointment(appointment_id: u64, client: Address, worker: Address, token: Address, amount: i128)`
- `confirm_completion(appointment_id: u64)` — client-only, pays the worker
- `cancel_appointment(appointment_id: u64)` — client-only, refunds the client
- `raise_dispute(appointment_id: u64, caller: Address)` — client or worker
- `resolve_dispute(appointment_id: u64, refund_to_client: bool)` — admin-only
- `get_appointment(appointment_id: u64) -> Appointment`

### reputation

- `submit_review(appointment_id: u64, client: Address, worker: Address, rating: u32, comment: String)` — 1-5 stars, one review per `appointment_id`
- `get_rating(worker: Address) -> Rating { count, sum }`
- `get_average_rating_x100(worker: Address) -> u32` — e.g. `437` = 4.37 stars
- `get_review(worker: Address, index: u32) -> Option<Review>`
- `get_review_count(worker: Address) -> u32`

### loyalty-token

- `initialize(admin: Address, minter: Address, decimals: u32, name: String, symbol: String)`
- `set_minter(new_minter: Address)` — admin-only
- `mint(to: Address, amount: i128)` — minter-only
- `transfer`, `transfer_from`, `approve`, `allowance`, `burn`, `balance`, `decimals`, `name`, `symbol` — standard SEP-41 token surface

## Notes / follow-ups

- `escrow` expects a standard Soroban token contract address (e.g. a Stellar
  Asset Contract wrapping USDC or XLM) for the `token` parameter — it does not
  handle native fiat payments, which stay on Paystack in the existing backend.
- These contracts have not been audited. Get an independent security review
  before moving any real funds through `escrow` or `loyalty-token` on mainnet.
