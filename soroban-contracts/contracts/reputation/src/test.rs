#![cfg(test)]

use super::*;
use soroban_sdk::testutils::Address as _;
use soroban_sdk::Env;

fn setup() -> (Env, ReputationContractClient<'static>, Address, Address) {
    let env = Env::default();
    env.mock_all_auths();

    let client = Address::generate(&env);
    let worker = Address::generate(&env);

    let contract_id = env.register(ReputationContract, ());
    let contract = ReputationContractClient::new(&env, &contract_id);

    (env, contract, client, worker)
}

#[test]
fn submit_review_updates_aggregate() {
    let (env, contract, client, worker) = setup();

    contract.submit_review(&1, &client, &worker, &5, &String::from_str(&env, "Great job"));
    contract.submit_review(&2, &client, &worker, &3, &String::from_str(&env, "Just ok"));

    let rating = contract.get_rating(&worker);
    assert_eq!(rating.count, 2);
    assert_eq!(rating.sum, 8);
    assert_eq!(contract.get_average_rating_x100(&worker), 400);
    assert_eq!(contract.get_review_count(&worker), 2);
    assert_eq!(contract.get_review(&worker, &0).unwrap().rating, 5);
}

#[test]
fn cannot_review_same_appointment_twice() {
    let (env, contract, client, worker) = setup();

    contract.submit_review(&1, &client, &worker, &5, &String::from_str(&env, "Great"));
    let result = contract.try_submit_review(
        &1,
        &client,
        &worker,
        &4,
        &String::from_str(&env, "Again"),
    );
    assert_eq!(result, Err(Ok(Error::AlreadyReviewed)));
}

#[test]
fn rejects_out_of_range_rating() {
    let (env, contract, client, worker) = setup();
    let result = contract.try_submit_review(&1, &client, &worker, &6, &String::from_str(&env, "x"));
    assert_eq!(result, Err(Ok(Error::InvalidRating)));
}
