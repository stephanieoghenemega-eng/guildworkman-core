package com.guildworkman.api.payment.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;

/**
 * Conversion between the integer minor units money is stored and transmitted
 * in, and the decimal major-unit figure people read.
 *
 * <p>The exponent is taken from {@link Currency#getDefaultFractionDigits()}
 * rather than hardcoded to 2: NGN and USD have two, but JPY has none, and a
 * hardcoded 100 would silently inflate every yen amount by a hundredfold the
 * day Paystack adds a zero-decimal currency. An unknown or non-decimal
 * currency code (fraction digits of {@code -1}) is treated as having no minor
 * unit, which keeps the amount intact rather than guessing.
 */
public final class MinorUnits {

    private MinorUnits() {
    }

    private static int fractionDigits(String currencyCode) {
        try {
            int digits = Currency.getInstance(currencyCode).getDefaultFractionDigits();
            return Math.max(digits, 0);
        } catch (IllegalArgumentException | NullPointerException ex) {
            // Not an ISO-4217 code the JVM knows. Rendering it 1:1 is wrong in
            // a different way than rendering it /100 would be, but it is the
            // one that doesn't move a decimal point it can't justify moving.
            return 0;
        }
    }

    /** e.g. {@code (150000, "NGN")} to {@code 1500.00}. */
    public static BigDecimal toMajor(long amountMinor, String currencyCode) {
        int digits = fractionDigits(currencyCode);
        return BigDecimal.valueOf(amountMinor, digits);
    }

    /**
     * e.g. {@code (1500.00, "NGN")} to {@code 150000}.
     *
     * @throws IllegalArgumentException if the amount has more precision than
     *         the currency can express — silently rounding a client's stated
     *         price is not this method's call to make.
     */
    public static long toMinor(BigDecimal amountMajor, String currencyCode) {
        int digits = fractionDigits(currencyCode);
        try {
            return amountMajor.setScale(digits, RoundingMode.UNNECESSARY).movePointRight(digits).longValueExact();
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException(
                    "Amount " + amountMajor.toPlainString() + " cannot be expressed in " + currencyCode
                            + " (" + digits + " decimal place(s))", ex);
        }
    }
}
