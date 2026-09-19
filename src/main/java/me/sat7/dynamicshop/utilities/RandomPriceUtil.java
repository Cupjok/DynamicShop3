package me.sat7.dynamicshop.utilities;

import java.text.SimpleDateFormat;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.Map;
import java.util.Random;

import me.sat7.dynamicshop.files.CustomConfig;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

// 랜덤 가격(퍼센트) 기능. Random price offsets, rolled per item and reset on a wall-clock schedule.
public final class RandomPriceUtil
{
    private RandomPriceUtil()
    {

    }

    public static final String ROOT = "Options.randomPrice";

    public static final String TARGET_BUY = "BUY";
    public static final String TARGET_SELL = "SELL";
    public static final String TARGET_BOTH = "BOTH";

    public static final String PERIOD_DAILY = "DAILY";
    public static final String PERIOD_WEEKLY = "WEEKLY";
    public static final String PERIOD_MONTHLY = "MONTHLY";

    public static final int MIN_PERCENT = -100;
    public static final int MAX_PERCENT = 1000;

    private static final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm");
    private static final Random random = new Random();

    public static boolean IsEnabled(FileConfiguration data)
    {
        return data != null && data.getBoolean(ROOT + ".enable", false);
    }

    public static String GetTarget(FileConfiguration data)
    {
        String target = data.getString(ROOT + ".target", TARGET_BOTH);
        if (target == null)
            return TARGET_BOTH;

        target = target.toUpperCase();
        if (!target.equals(TARGET_BUY) && !target.equals(TARGET_SELL) && !target.equals(TARGET_BOTH))
            return TARGET_BOTH;

        return target;
    }

    public static boolean AppliesTo(FileConfiguration data, boolean buy)
    {
        if (!IsEnabled(data))
            return false;

        String target = GetTarget(data);
        return target.equals(TARGET_BOTH) || target.equals(buy ? TARGET_BUY : TARGET_SELL);
    }

    public static int GetMinPercent(FileConfiguration data)
    {
        return MathUtil.Clamp(data.getInt(ROOT + ".min", -10), MIN_PERCENT, MAX_PERCENT);
    }

    public static int GetMaxPercent(FileConfiguration data)
    {
        return MathUtil.Clamp(data.getInt(ROOT + ".max", 10), MIN_PERCENT, MAX_PERCENT);
    }

    public static boolean IsTimerEnabled(FileConfiguration data)
    {
        return data.getBoolean(ROOT + ".timer.enable", false);
    }

    public static String GetPeriod(FileConfiguration data)
    {
        String period = data.getString(ROOT + ".timer.period", PERIOD_DAILY);
        if (period == null)
            return PERIOD_DAILY;

        period = period.toUpperCase();
        if (!period.equals(PERIOD_DAILY) && !period.equals(PERIOD_WEEKLY) && !period.equals(PERIOD_MONTHLY))
            return PERIOD_DAILY;

        return period;
    }

    public static int GetHour(FileConfiguration data)
    {
        return MathUtil.Clamp(data.getInt(ROOT + ".timer.hour", 0), 0, 23);
    }

    public static int GetMinute(FileConfiguration data)
    {
        return MathUtil.Clamp(data.getInt(ROOT + ".timer.minute", 0), 0, 59);
    }

    public static int GetDayOfWeek(FileConfiguration data)
    {
        return MathUtil.Clamp(data.getInt(ROOT + ".timer.dayOfWeek", 1), 1, 7);
    }

    public static int GetDayOfMonth(FileConfiguration data)
    {
        return MathUtil.Clamp(data.getInt(ROOT + ".timer.dayOfMonth", 1), 1, 31);
    }

    // 아이탬 하나의 랜덤 퍼센트. 기능이 꺼져있거나 대상이 아니면 0.
    public static double GetPercent(FileConfiguration data, String idx, boolean buy)
    {
        if (!AppliesTo(data, buy))
            return 0;

        return data.getDouble(idx + ".randomPrice." + (buy ? "buy" : "sell"), 0);
    }

    public static double Apply(double price, double percent)
    {
        if (percent == 0)
            return price;

        return price * (100 + percent) / 100;
    }

    // 다음 초기화 시각 계산. 기계(서버) 시간 기준.
    public static long ComputeNextReset(long fromMillis, String period, int hour, int minute, int dayOfWeek, int dayOfMonth, ZoneId zone)
    {
        LocalDateTime from = LocalDateTime.ofInstant(Instant.ofEpochMilli(fromMillis), zone);
        LocalTime time = LocalTime.of(MathUtil.Clamp(hour, 0, 23), MathUtil.Clamp(minute, 0, 59));

        LocalDateTime next;
        if (PERIOD_WEEKLY.equalsIgnoreCase(period))
        {
            DayOfWeek target = DayOfWeek.of(MathUtil.Clamp(dayOfWeek, 1, 7));
            next = LocalDateTime.of(from.toLocalDate().with(TemporalAdjusters.nextOrSame(target)), time);
            if (!next.isAfter(from))
                next = LocalDateTime.of(from.toLocalDate().plusDays(1).with(TemporalAdjusters.nextOrSame(target)), time);
        }
        else if (PERIOD_MONTHLY.equalsIgnoreCase(period))
        {
            int day = MathUtil.Clamp(dayOfMonth, 1, 31);
            LocalDate base = from.toLocalDate().withDayOfMonth(1);
            next = LocalDateTime.of(ClampDayOfMonth(base, day), time);
            while (!next.isAfter(from))
            {
                base = base.plusMonths(1);
                next = LocalDateTime.of(ClampDayOfMonth(base, day), time);
            }
        }
        else
        {
            next = LocalDateTime.of(from.toLocalDate(), time);
            if (!next.isAfter(from))
                next = next.plusDays(1);
        }

        return next.atZone(zone).toInstant().toEpochMilli();
    }

    // 31일처럼 그 달에 없는 날짜는 그 달의 마지막 날로 처리함.
    private static LocalDate ClampDayOfMonth(LocalDate monthStart, int day)
    {
        return monthStart.withDayOfMonth(Math.min(day, monthStart.lengthOfMonth()));
    }

    public static long GetNextResetTime(FileConfiguration data)
    {
        return data.getLong(ROOT + ".timer.next", 0);
    }

    public static String FormatTime(long millis)
    {
        return sdf.format(millis);
    }

    // 상점의 모든 상품에 대해 퍼센트를 다시 뽑음.
    public static void Reroll(String shopName)
    {
        CustomConfig config = ShopUtil.shopConfigFiles.get(shopName);
        if (config == null)
            return;

        FileConfiguration data = config.get();

        int min = GetMinPercent(data);
        int max = GetMaxPercent(data);
        if (min > max)
        {
            int temp = min;
            min = max;
            max = temp;
        }

        for (String key : data.getKeys(false))
        {
            try
            {
                Integer.parseInt(key); // Options 등에는 적용하지 않음.
            } catch (Exception e)
            {
                continue;
            }

            if (!data.contains(key + ".value"))
                continue; // 장식용은 스킵

            data.set(key + ".randomPrice.buy", RollPercent(min, max));
            data.set(key + ".randomPrice.sell", RollPercent(min, max));
        }

        config.save();
    }

    private static int RollPercent(int min, int max)
    {
        if (min == max)
            return min;

        return min + random.nextInt(max - min + 1);
    }

    // 예약 초기화 시각을 다시 계산해서 저장함.
    public static void ScheduleNextReset(String shopName)
    {
        CustomConfig config = ShopUtil.shopConfigFiles.get(shopName);
        if (config == null)
            return;

        FileConfiguration data = config.get();
        long next = ComputeNextReset(System.currentTimeMillis(), GetPeriod(data), GetHour(data), GetMinute(data),
                GetDayOfWeek(data), GetDayOfMonth(data), ZoneId.systemDefault());

        data.set(ROOT + ".timer.next", next);
        config.save();
    }

    // 1초마다 호출됨. 예약된 시각이 지난 상점만 다시 뽑음.
    public static void Tick()
    {
        long now = System.currentTimeMillis();

        for (Map.Entry<String, CustomConfig> entry : ShopUtil.shopConfigFiles.entrySet())
        {
            FileConfiguration data = entry.getValue().get();

            if (!IsEnabled(data) || !IsTimerEnabled(data))
                continue;

            long next = GetNextResetTime(data);
            if (next == 0)
            {
                ScheduleNextReset(entry.getKey());
                continue;
            }

            if (now < next)
                continue;

            Reroll(entry.getKey());
            ScheduleNextReset(entry.getKey());
        }
    }

    // 기능을 처음 켤 때 기본값을 만들고 값을 한 번 뽑아줌.
    public static void SetDefaults(String shopName)
    {
        CustomConfig config = ShopUtil.shopConfigFiles.get(shopName);
        if (config == null)
            return;

        FileConfiguration data = config.get();
        ConfigurationSection section = data.getConfigurationSection(ROOT);
        if (section == null)
            section = data.createSection(ROOT);

        if (!section.contains("target")) section.set("target", TARGET_BOTH);
        if (!section.contains("min")) section.set("min", -10);
        if (!section.contains("max")) section.set("max", 10);
        if (!section.contains("timer.enable")) section.set("timer.enable", false);
        if (!section.contains("timer.period")) section.set("timer.period", PERIOD_DAILY);
        if (!section.contains("timer.hour")) section.set("timer.hour", 0);
        if (!section.contains("timer.minute")) section.set("timer.minute", 0);
        if (!section.contains("timer.dayOfWeek")) section.set("timer.dayOfWeek", 1);
        if (!section.contains("timer.dayOfMonth")) section.set("timer.dayOfMonth", 1);

        config.save();
    }
}
