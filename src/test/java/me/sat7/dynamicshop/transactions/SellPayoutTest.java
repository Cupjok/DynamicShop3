package me.sat7.dynamicshop.transactions;

import me.sat7.dynamicshop.constants.Constants;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SellPayoutTest
{
    @Test
    public void vaultPayoutBelowOneCentIsRefused()
    {
        // 0 sell price -> valueMin 0.0001 per item; a full stack still pays nothing.
        assertTrue(Sell.IsPayoutTooLow("Vault", 64 * 0.0001));
        assertTrue(Sell.IsPayoutTooLow("Vault", 0));
        assertTrue(Sell.IsPayoutTooLow("Vault", -5));
        assertFalse(Sell.IsPayoutTooLow("Vault", 0.01));
        assertFalse(Sell.IsPayoutTooLow("Vault", 12.5));
    }

    @Test
    public void jobsPointsUseCentSteps()
    {
        assertTrue(Sell.IsPayoutTooLow(Constants.S_JOBPOINT, 0.001));
        assertFalse(Sell.IsPayoutTooLow(Constants.S_JOBPOINT, 0.5));
    }

    @Test
    public void wholeNumberCurrenciesNeedAtLeastOne()
    {
        assertTrue(Sell.IsPayoutTooLow(Constants.S_EXP, 0.9));
        assertTrue(Sell.IsPayoutTooLow(Constants.S_PLAYERPOINT, 0.99));
        assertFalse(Sell.IsPayoutTooLow(Constants.S_EXP, 1));
        assertFalse(Sell.IsPayoutTooLow(Constants.S_PLAYERPOINT, 3.7));
    }
}
