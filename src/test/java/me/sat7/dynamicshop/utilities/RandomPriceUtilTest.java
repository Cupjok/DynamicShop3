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
        data.set(RandomPriceUtil.ROOT + ".min", -50);
        data.set(RandomPriceUtil.ROOT + ".max", 50);
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

    @Test
    public void storedPercentsAreClampedToTheCurrentRange()
    {
        // 범위를 좁힌 뒤 아직 다시 뽑지 않은 값이 그대로 쓰이면 안됨.
        YamlConfiguration data = new YamlConfiguration();
        data.set(RandomPriceUtil.ROOT + ".enable", true);
        data.set(RandomPriceUtil.ROOT + ".min", -50);
        data.set(RandomPriceUtil.ROOT + ".max", 50);
        data.set("0.randomPrice.buy", 320);
        data.set("0.randomPrice.sell", -90);

        assertEquals(50, RandomPriceUtil.GetPercent(data, "0", true), 0.0001);
        assertEquals(-50, RandomPriceUtil.GetPercent(data, "0", false), 0.0001);
    }

    @Test
    public void rollForItemStaysInsideTheRangeAndOnlyRunsWhenEnabled()
    {
        YamlConfiguration off = new YamlConfiguration();
        RandomPriceUtil.RollForItem(off, "0");
        assertFalse(off.contains("0.randomPrice"));

        YamlConfiguration on = new YamlConfiguration();
        on.set(RandomPriceUtil.ROOT + ".enable", true);
        on.set(RandomPriceUtil.ROOT + ".min", -50);
        on.set(RandomPriceUtil.ROOT + ".max", 50);

        for (int i = 0; i < 200; i++)
        {
            RandomPriceUtil.RollForItem(on, "0");
            double buy = on.getDouble("0.randomPrice.buy");
            double sell = on.getDouble("0.randomPrice.sell");
            assertTrue("buy " + buy, buy >= -50 && buy <= 50);
            assertTrue("sell " + sell, sell >= -50 && sell <= 50);
        }
    }
}
