#![no_std]

//! Reputation contract for GuildWorkman.
//!
//! Stores skilled-worker reviews immutably on-chain, one per completed
//! appointment, and maintains a running rating aggregate per worker.

use soroban_sdk::{contract, contracterror, contractimpl, contracttype, Address, Env, String};

#[contracttype]
#[derive(Clone, Debug)]
pub struct Review {
    pub client: Address,
    pub rating: u32,
    pub comment: String,
}

#[contracttype]
#[derive(Clone, Copy, Debug, Default)]
pub struct Rating {
    pub count: u32,
    pub sum: u64,
}

#[contracttype]
pub enum DataKey {
    Reviewed(u64),
    Rating(Address),
    Review(Address, u32),
    ReviewCount(Address),
}

#[contracterror]
#[derive(Copy, Clone, Debug, Eq, PartialEq, PartialOrd, Ord)]
pub enum Error {
    InvalidRating = 1,
    AlreadyReviewed = 2,
    NoReviews = 3,
}

const LEDGERS_THRESHOLD: u32 = 17_280; // ~1 day
const LEDGERS_EXTEND_TO: u32 = 518_400; // ~30 days

#[contract]
pub struct ReputationContract;

#[contractimpl]
impl ReputationContract {
    /// Client leaves a 1-5 star review for `worker` tied to a specific, unique
    /// `appointment_id`. Each appointment can only be reviewed once.
    pub fn submit_review(
        env: Env,
        appointment_id: u64,
        client: Address,
        worker: Address,
        rating: u32,
        comment: String,
    ) -> Result<(), Error> {
        client.require_auth();

        if rating < 1 || rating > 5 {
            return Err(Error::InvalidRating);
        }

        let reviewed_key = DataKey::Reviewed(appointment_id);
        if env.storage().persistent().has(&reviewed_key) {
            return Err(Error::AlreadyReviewed);
        }
        env.storage().persistent().set(&reviewed_key, &true);
        env.storage()
            .persistent()
            .extend_ttl(&reviewed_key, LEDGERS_THRESHOLD, LEDGERS_EXTEND_TO);

        let count_key = DataKey::ReviewCount(worker.clone());
        let index: u32 = env.storage().persistent().get(&count_key).unwrap_or(0);
        let review = Review {
            client,
            rating,
            comment,
        };
        let review_key = DataKey::Review(worker.clone(), index);
        env.storage().persistent().set(&review_key, &review);
        env.storage()
            .persistent()
            .extend_ttl(&review_key, LEDGERS_THRESHOLD, LEDGERS_EXTEND_TO);
        env.storage().persistent().set(&count_key, &(index + 1));
        env.storage()
            .persistent()
            .extend_ttl(&count_key, LEDGERS_THRESHOLD, LEDGERS_EXTEND_TO);

        let rating_key = DataKey::Rating(worker.clone());
        let mut aggregate: Rating = env
            .storage()
            .persistent()
            .get(&rating_key)
            .unwrap_or_default();
        aggregate.count += 1;
        aggregate.sum += rating as u64;
        env.storage().persistent().set(&rating_key, &aggregate);
        env.storage()
            .persistent()
            .extend_ttl(&rating_key, LEDGERS_THRESHOLD, LEDGERS_EXTEND_TO);

        Ok(())
    }

    pub fn get_rating(env: Env, worker: Address) -> Rating {
        env.storage()
            .persistent()
            .get(&DataKey::Rating(worker))
            .unwrap_or_default()
    }

    /// Average rating scaled by 100 (e.g. `437` means 4.37 stars) to avoid floats.
    pub fn get_average_rating_x100(env: Env, worker: Address) -> Result<u32, Error> {
        let aggregate = Self::get_rating(env, worker);
        if aggregate.count == 0 {
            return Err(Error::NoReviews);
        }
        Ok(((aggregate.sum * 100) / aggregate.count as u64) as u32)
    }

    pub fn get_review(env: Env, worker: Address, index: u32) -> Option<Review> {
        env.storage().persistent().get(&DataKey::Review(worker, index))
    }

    pub fn get_review_count(env: Env, worker: Address) -> u32 {
        env.storage()
            .persistent()
            .get(&DataKey::ReviewCount(worker))
            .unwrap_or(0)
    }
}

#[cfg(test)]
mod test;
