package me.sat7.dynamicshop.utilities;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.Assert.*;

public class RandomPriceUtilTest
{
    private static final ZoneId ZONE = ZoneId.of("UTC");

    private static long millis(int year, int month, int day, int hour, int minute)
    {
        return LocalDateTime.of(year, month, day, hour, minute).atZone(ZONE).toInstant().toEpochMilli();
    }

    @Test
    public void dailyResetMovesToTheNextDayWhenTheTimeHasPassed()
    {
        long from = millis(2026, 9, 19, 10, 30);
        long next = RandomPriceUtil.ComputeNextReset(from, RandomPriceUtil.PERIOD_DAILY, 4, 0, 1, 1, ZONE);

        assertEquals(millis(2026, 9, 20, 4, 0), next);
    }

    @Test
    public void dailyResetStaysOnTheSameDayWhenTheTimeIsStillAhead()
    {
        long from = millis(2026, 9, 19, 10, 30);
        long next = RandomPriceUtil.ComputeNextReset(from, RandomPriceUtil.PERIOD_DAILY, 23, 15, 1, 1, ZONE);

        assertEquals(millis(2026, 9, 19, 23, 15), next);
    }

    @Test
    public void weeklyResetPicksTheConfiguredWeekday()
    {
        // 2026-09-19 is a Saturday, so the next Monday is 2026-09-21.
        long from = millis(2026, 9, 19, 10, 30);
        long next = RandomPriceUtil.ComputeNextReset(from, RandomPriceUtil.PERIOD_WEEKLY, 0, 0, 1, 1, ZONE);

        assertEquals(millis(2026, 9, 21, 0, 0), next);
    }

    @Test
    public void monthlyResetFallsBackToTheLastDayOfAShortMonth()
    {
        long from = millis(2026, 1, 31, 12, 0);
        long next = RandomPriceUtil.ComputeNextReset(from, RandomPriceUtil.PERIOD_MONTHLY, 0, 0, 1, 31, ZONE);

        assertEquals(millis(2026, 2, 28, 0, 0), next);
    }

    @Test
    public void percentIsZeroWhenTheFeatureIsOff()
    {
        YamlConfiguration data = new YamlConfiguration();
        data.set("3.randomPrice.buy", 25);

        assertEquals(0, RandomPriceUtil.GetPercent(data, "3", true), 0.0001);
    }

    @Test
    public void percentOnlyAppliesToTheConfiguredTarget()
    {
        YamlConfiguration data = new YamlConfiguration();
        data.set(RandomPriceUtil.ROOT + ".enable", true);
        data.set(RandomPriceUtil.ROOT + ".target", RandomPriceUtil.TARGET_BUY);
        data.set("3.randomPrice.buy", 25);
        data.set("3.randomPrice.sell", -10);

        assertEquals(25, RandomPriceUtil.GetPercent(data, "3", true), 0.0001);
        assertEquals(0, RandomPriceUtil.GetPercent(data, "3", false), 0.0001);
    }

    @Test
    public void applyScalesThePriceByThePercent()
    {
        assertEquals(55, RandomPriceUtil.Apply(50, 10), 0.0001);
        assertEquals(45, RandomPriceUtil.Apply(50, -10), 0.0001);
        assertEquals(50, RandomPriceUtil.Apply(50, 0), 0.0001);
    }
}
