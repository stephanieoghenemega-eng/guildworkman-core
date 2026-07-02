#![no_std]

//! Loyalty rewards token for Sabi-Connect.
//!
//! A SEP-41-style fungible token. The backend (as `minter`) rewards clients
//! and workers with points on completed appointments; holders can transfer
//! or burn their own balance like any other token.

use soroban_sdk::{contract, contracterror, contractimpl, contracttype, Address, Env, String};

#[contracttype]
pub enum DataKey {
    Admin,
    Minter,
    Metadata,
    Balance(Address),
    Allowance(Address, Address),
}

#[contracttype]
#[derive(Clone)]
pub struct AllowanceValue {
    pub amount: i128,
    pub expiration_ledger: u32,
}

#[contracttype]
#[derive(Clone)]
pub struct TokenMetadata {
    pub decimals: u32,
    pub name: String,
    pub symbol: String,
}

#[contracterror]
#[derive(Copy, Clone, Debug, Eq, PartialEq, PartialOrd, Ord)]
pub enum Error {
    AlreadyInitialized = 1,
    NotInitialized = 2,
    InsufficientBalance = 3,
    InsufficientAllowance = 4,
    InvalidAmount = 5,
    NotAuthorized = 6,
}

const DAY_IN_LEDGERS: u32 = 17_280;
const INSTANCE_BUMP_AMOUNT: u32 = DAY_IN_LEDGERS * 60;
const INSTANCE_LIFETIME_THRESHOLD: u32 = DAY_IN_LEDGERS * 30;
const BALANCE_BUMP_AMOUNT: u32 = DAY_IN_LEDGERS * 30;
const BALANCE_LIFETIME_THRESHOLD: u32 = DAY_IN_LEDGERS * 29;

#[contract]
pub struct LoyaltyToken;

#[contractimpl]
impl LoyaltyToken {
    /// One-time setup. `admin` can rotate the minter; `minter` is the only
    /// address allowed to mint new reward points (typically the Sabi-Connect
    /// backend's service account).
    pub fn initialize(
        env: Env,
        admin: Address,
        minter: Address,
        decimals: u32,
        name: String,
        symbol: String,
    ) -> Result<(), Error> {
        if env.storage().instance().has(&DataKey::Admin) {
            return Err(Error::AlreadyInitialized);
        }
        admin.require_auth();

        env.storage().instance().set(&DataKey::Admin, &admin);
        env.storage().instance().set(&DataKey::Minter, &minter);
        env.storage().instance().set(
            &DataKey::Metadata,
            &TokenMetadata {
                decimals,
                name,
                symbol,
            },
        );
        env.storage()
            .instance()
            .extend_ttl(INSTANCE_LIFETIME_THRESHOLD, INSTANCE_BUMP_AMOUNT);
        Ok(())
    }

    /// Admin-only: rotate which address is allowed to mint reward points.
    pub fn set_minter(env: Env, new_minter: Address) -> Result<(), Error> {
        let admin = Self::require_admin(&env)?;
        admin.require_auth();
        env.storage().instance().set(&DataKey::Minter, &new_minter);
        Ok(())
    }

    /// Minter-only: award `amount` loyalty points to `to`.
    pub fn mint(env: Env, to: Address, amount: i128) -> Result<(), Error> {
        if amount <= 0 {
            return Err(Error::InvalidAmount);
        }
        let minter: Address = env
            .storage()
            .instance()
            .get(&DataKey::Minter)
            .ok_or(Error::NotInitialized)?;
        minter.require_auth();

        Self::receive_balance(&env, to, amount);
        env.storage()
            .instance()
            .extend_ttl(INSTANCE_LIFETIME_THRESHOLD, INSTANCE_BUMP_AMOUNT);
        Ok(())
    }

    pub fn balance(env: Env, id: Address) -> i128 {
        Self::read_balance(&env, id)
    }

    pub fn allowance(env: Env, from: Address, spender: Address) -> i128 {
        Self::read_allowance(&env, from, spender).amount
    }

    pub fn approve(
        env: Env,
        from: Address,
        spender: Address,
        amount: i128,
        expiration_ledger: u32,
    ) -> Result<(), Error> {
        from.require_auth();
        if amount < 0 {
            return Err(Error::InvalidAmount);
        }
        Self::write_allowance(&env, from, spender, amount, expiration_ledger);
        Ok(())
    }

    pub fn transfer(env: Env, from: Address, to: Address, amount: i128) -> Result<(), Error> {
        from.require_auth();
        if amount <= 0 {
            return Err(Error::InvalidAmount);
        }
        Self::spend_balance(&env, from, amount)?;
        Self::receive_balance(&env, to, amount);
        Ok(())
    }

    pub fn transfer_from(
        env: Env,
        spender: Address,
        from: Address,
        to: Address,
        amount: i128,
    ) -> Result<(), Error> {
        spender.require_auth();
        if amount <= 0 {
            return Err(Error::InvalidAmount);
        }
        Self::spend_allowance(&env, from.clone(), spender, amount)?;
        Self::spend_balance(&env, from, amount)?;
        Self::receive_balance(&env, to, amount);
        Ok(())
    }

    pub fn burn(env: Env, from: Address, amount: i128) -> Result<(), Error> {
        from.require_auth();
        if amount <= 0 {
            return Err(Error::InvalidAmount);
        }
        Self::spend_balance(&env, from, amount)?;
        Ok(())
    }

    pub fn decimals(env: Env) -> u32 {
        Self::read_metadata(&env).decimals
    }

    pub fn name(env: Env) -> String {
        Self::read_metadata(&env).name
    }

    pub fn symbol(env: Env) -> String {
        Self::read_metadata(&env).symbol
    }

    // --- internal helpers ---

    fn require_admin(env: &Env) -> Result<Address, Error> {
        env.storage()
            .instance()
            .get(&DataKey::Admin)
            .ok_or(Error::NotInitialized)
    }

    fn read_metadata(env: &Env) -> TokenMetadata {
        env.storage()
            .instance()
            .get(&DataKey::Metadata)
            .expect("not initialized")
    }

    fn read_balance(env: &Env, addr: Address) -> i128 {
        let key = DataKey::Balance(addr);
        if let Some(balance) = env.storage().persistent().get::<_, i128>(&key) {
            env.storage()
                .persistent()
                .extend_ttl(&key, BALANCE_LIFETIME_THRESHOLD, BALANCE_BUMP_AMOUNT);
            balance
        } else {
            0
        }
    }

    fn write_balance(env: &Env, addr: Address, amount: i128) {
        let key = DataKey::Balance(addr);
        env.storage().persistent().set(&key, &amount);
        env.storage()
            .persistent()
            .extend_ttl(&key, BALANCE_LIFETIME_THRESHOLD, BALANCE_BUMP_AMOUNT);
    }

    fn receive_balance(env: &Env, addr: Address, amount: i128) {
        let balance = Self::read_balance(env, addr.clone());
        Self::write_balance(env, addr, balance + amount);
    }

    fn spend_balance(env: &Env, addr: Address, amount: i128) -> Result<(), Error> {
        let balance = Self::read_balance(env, addr.clone());
        if balance < amount {
            return Err(Error::InsufficientBalance);
        }
        Self::write_balance(env, addr, balance - amount);
        Ok(())
    }

    fn read_allowance(env: &Env, from: Address, spender: Address) -> AllowanceValue {
        let key = DataKey::Allowance(from, spender);
        if let Some(allowance) = env.storage().temporary().get::<_, AllowanceValue>(&key) {
            if allowance.expiration_ledger < env.ledger().sequence() {
                AllowanceValue {
                    amount: 0,
                    expiration_ledger: allowance.expiration_ledger,
                }
            } else {
                allowance
            }
        } else {
            AllowanceValue {
                amount: 0,
                expiration_ledger: 0,
            }
        }
    }

    fn write_allowance(
        env: &Env,
        from: Address,
        spender: Address,
        amount: i128,
        expiration_ledger: u32,
    ) {
        let allowance = AllowanceValue {
            amount,
            expiration_ledger,
        };
        let key = DataKey::Allowance(from, spender);
        env.storage().temporary().set(&key, &allowance);
        if amount > 0 && expiration_ledger > env.ledger().sequence() {
            let live_for = expiration_ledger - env.ledger().sequence();
            env.storage().temporary().extend_ttl(&key, live_for, live_for);
        }
    }

    fn spend_allowance(
        env: &Env,
        from: Address,
        spender: Address,
        amount: i128,
    ) -> Result<(), Error> {
        let allowance = Self::read_allowance(env, from.clone(), spender.clone());
        if allowance.amount < amount {
            return Err(Error::InsufficientAllowance);
        }
        Self::write_allowance(
            env,
            from,
            spender,
            allowance.amount - amount,
            allowance.expiration_ledger,
        );
        Ok(())
    }
}

#[cfg(test)]
mod test;
