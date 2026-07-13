#![cfg(test)]

use super::*;
use soroban_sdk::testutils::Address as _;
use soroban_sdk::Env;

fn create_token_contract<'a>(
    env: &Env,
    admin: &Address,
) -> (Address, token::StellarAssetClient<'a>, token::Client<'a>) {
    let sac = env.register_stellar_asset_contract_v2(admin.clone());
    let address = sac.address();
    (
        address.clone(),
        token::StellarAssetClient::new(env, &address),
        token::Client::new(env, &address),
    )
}

#[allow(dead_code)]
struct TestCtx<'a> {
    env: Env,
    contract: EscrowContractClient<'a>,
    admin: Address,
    client: Address,
    worker: Address,
    token: Address,
    token_admin: token::StellarAssetClient<'a>,
    token_client: token::Client<'a>,
}

fn setup() -> TestCtx<'static> {
    let env = Env::default();
    env.mock_all_auths();

    let admin = Address::generate(&env);
    let client = Address::generate(&env);
    let worker = Address::generate(&env);
    let token_issuer = Address::generate(&env);

    let (token, token_admin, token_client) = create_token_contract(&env, &token_issuer);
    token_admin.mint(&client, &1_000_000);

    let contract_id = env.register(EscrowContract, ());
    let contract = EscrowContractClient::new(&env, &contract_id);
    contract.initialize(&admin);

    TestCtx {
        env,
        contract,
        admin,
        client,
        worker,
        token,
        token_admin,
        token_client,
    }
}

#[test]
fn happy_path_completion_pays_worker() {
    let ctx = setup();

    ctx.contract
        .create_appointment(&1, &ctx.client, &ctx.worker, &ctx.token, &10_000);
    assert_eq!(ctx.token_client.balance(&ctx.client), 990_000);
    assert_eq!(
        ctx.token_client.balance(&ctx.contract.address),
        10_000
    );

    ctx.contract.confirm_completion(&1);
    assert_eq!(ctx.token_client.balance(&ctx.worker), 10_000);
    assert_eq!(ctx.token_client.balance(&ctx.contract.address), 0);

    let appointment = ctx.contract.get_appointment(&1);
    assert_eq!(appointment.status, Status::Completed);
}

#[test]
fn cancel_refunds_client() {
    let ctx = setup();

    ctx.contract
        .create_appointment(&2, &ctx.client, &ctx.worker, &ctx.token, &5_000);
    ctx.contract.cancel_appointment(&2);

    assert_eq!(ctx.token_client.balance(&ctx.client), 1_000_000);
    assert_eq!(ctx.token_client.balance(&ctx.contract.address), 0);
    assert_eq!(
        ctx.contract.get_appointment(&2).status,
        Status::Cancelled
    );
}

#[test]
fn dispute_resolved_in_favor_of_worker() {
    let ctx = setup();

    ctx.contract
        .create_appointment(&3, &ctx.client, &ctx.worker, &ctx.token, &7_000);
    ctx.contract.raise_dispute(&3, &ctx.client);
    assert_eq!(ctx.contract.get_appointment(&3).status, Status::Disputed);

    ctx.contract.resolve_dispute(&3, &false);
    assert_eq!(ctx.token_client.balance(&ctx.worker), 7_000);
    assert_eq!(
        ctx.contract.get_appointment(&3).status,
        Status::Resolved
    );
}

#[test]
fn duplicate_appointment_id_rejected() {
    let ctx = setup();
    ctx.contract
        .create_appointment(&4, &ctx.client, &ctx.worker, &ctx.token, &1_000);

    let result = ctx.contract.try_create_appointment(
        &4,
        &ctx.client,
        &ctx.worker,
        &ctx.token,
        &1_000,
    );
    assert_eq!(result, Err(Ok(Error::AppointmentExists)));
}

#[test]
fn cannot_confirm_twice() {
    let ctx = setup();
    ctx.contract
        .create_appointment(&5, &ctx.client, &ctx.worker, &ctx.token, &2_000);
    ctx.contract.confirm_completion(&5);

    let result = ctx.contract.try_confirm_completion(&5);
    assert_eq!(result, Err(Ok(Error::InvalidStatus)));
}
