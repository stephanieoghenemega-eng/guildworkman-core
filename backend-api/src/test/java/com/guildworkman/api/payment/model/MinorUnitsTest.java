package com.guildworkman.api.payment.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Money crosses the API boundary as a decimal and lives everywhere else as an
 * integer. These tests cover the conversion, including the currencies where
 * "divide by 100" would be wrong.
 */
class MinorUnitsTest {

    @Test
    void convertsNairaBothWays() {
        assertThat(MinorUnits.toMinor(new BigDecimal("1500.00"), "NGN")).isEqualTo(150_000L);
        assertThat(MinorUnits.toMajor(150_000L, "NGN")).isEqualByComparingTo("1500.00");
    }

    @Test
    void roundTripsWithoutDrift() {
        long minor = 7_531L;
        assertThat(MinorUnits.toMinor(MinorUnits.toMajor(minor, "NGN"), "NGN")).isEqualTo(minor);
    }

    @Test
    void respectsAZeroDecimalCurrency() {
        // The reason the exponent comes from the currency and is not hardcoded
        // to 100: a hardcoded divisor inflates every yen amount a hundredfold.
        assertThat(MinorUnits.toMinor(new BigDecimal("1500"), "JPY")).isEqualTo(1_500L);
        assertThat(MinorUnits.toMajor(1_500L, "JPY")).isEqualByComparingTo("1500");
    }

    @Test
    void refusesAnAmountTheCurrencyCannotExpress() {
        // Silently rounding a stated price is not this method's decision to make.
        assertThatThrownBy(() -> MinorUnits.toMinor(new BigDecimal("1500.50"), "JPY"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JPY");
        assertThatThrownBy(() -> MinorUnits.toMinor(new BigDecimal("10.005"), "NGN"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void treatsAnUnknownCurrencyCodeAsHavingNoMinorUnit() {
        // Keeping the amount intact beats moving a decimal point we can't justify.
        assertThat(MinorUnits.toMajor(1_234L, "XYZ")).isEqualByComparingTo("1234");
        assertThat(MinorUnits.toMinor(new BigDecimal("1234"), "XYZ")).isEqualTo(1_234L);
    }

    @Test
    void handlesZeroAndLargeAmounts() {
        assertThat(MinorUnits.toMajor(0L, "NGN")).isEqualByComparingTo("0.00");
        assertThat(MinorUnits.toMinor(new BigDecimal("99999999.99"), "NGN")).isEqualTo(9_999_999_999L);
    }
}
