#![cfg(test)]

use super::*;
use soroban_sdk::testutils::Address as _;
use soroban_sdk::Env;

fn setup() -> (Env, LoyaltyTokenClient<'static>, Address, Address, Address) {
    let env = Env::default();
    env.mock_all_auths();

    let admin = Address::generate(&env);
    let minter = Address::generate(&env);
    let user = Address::generate(&env);

    let contract_id = env.register(LoyaltyToken, ());
    let contract = LoyaltyTokenClient::new(&env, &contract_id);
    contract.initialize(
        &admin,
        &minter,
        &2,
        &String::from_str(&env, "Sabi Points"),
        &String::from_str(&env, "SABI"),
    );

    (env, contract, admin, minter, user)
}

#[test]
fn mint_and_balance() {
    let (_env, contract, _admin, _minter, user) = setup();
    contract.mint(&user, &1_000);
    assert_eq!(contract.balance(&user), 1_000);
}

#[test]
fn transfer_moves_balance() {
    let (env, contract, _admin, _minter, user) = setup();
    let other = Address::generate(&env);

    contract.mint(&user, &500);
    contract.transfer(&user, &other, &200);

    assert_eq!(contract.balance(&user), 300);
    assert_eq!(contract.balance(&other), 200);
}

#[test]
fn transfer_more_than_balance_fails() {
    let (env, contract, _admin, _minter, user) = setup();
    let other = Address::generate(&env);
    contract.mint(&user, &100);

    let result = contract.try_transfer(&user, &other, &200);
    assert_eq!(result, Err(Ok(Error::InsufficientBalance)));
}

#[test]
fn approve_and_transfer_from() {
    let (env, contract, _admin, _minter, user) = setup();
    let spender = Address::generate(&env);
    let recipient = Address::generate(&env);

    contract.mint(&user, &1_000);
    contract.approve(&user, &spender, &300, &(env.ledger().sequence() + 1_000));
    assert_eq!(contract.allowance(&user, &spender), 300);

    contract.transfer_from(&spender, &user, &recipient, &300);
    assert_eq!(contract.balance(&recipient), 300);
    assert_eq!(contract.balance(&user), 700);
    assert_eq!(contract.allowance(&user, &spender), 0);
}

#[test]
fn burn_reduces_balance() {
    let (_env, contract, _admin, _minter, user) = setup();
    contract.mint(&user, &400);
    contract.burn(&user, &150);
    assert_eq!(contract.balance(&user), 250);
}

#[test]
fn admin_can_rotate_minter() {
    let (env, contract, _admin, _minter, user) = setup();
    let new_minter = Address::generate(&env);

    contract.set_minter(&new_minter);
    contract.mint(&user, &50);
    assert_eq!(contract.balance(&user), 50);
}
