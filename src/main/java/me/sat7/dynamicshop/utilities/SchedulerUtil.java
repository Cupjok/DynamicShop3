package me.sat7.dynamicshop.utilities;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import me.sat7.dynamicshop.DynamicShop;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;

import java.util.concurrent.TimeUnit;

/**
 * Wraps Paper's region/entity/global/async schedulers (available on Paper, Purpur and Folia
 * since these APIs were introduced in 1.20.1) instead of the legacy Bukkit scheduler.
 * On Paper/Purpur these behave exactly like the legacy scheduler; on Folia they correctly
 * dispatch to the owning region thread. Using them uniformly keeps a single code path
 * that is correct on all three platforms.
 */
public final class SchedulerUtil
{
    private SchedulerUtil()
    {

    }

    private static final long MS_PER_TICK = 50L;

    // ---------------- Global (no specific location/entity) ----------------

    public static ScheduledTask runGlobal(Runnable task)
    {
        return Bukkit.getGlobalRegionScheduler().run(DynamicShop.plugin, t -> task.run());
    }

    public static ScheduledTask runGlobalDelayed(Runnable task, long delayTicks)
    {
        return Bukkit.getGlobalRegionScheduler().runDelayed(DynamicShop.plugin, t -> task.run(), Math.max(1, delayTicks));
    }

    public static ScheduledTask runGlobalTimer(Runnable task, long delayTicks, long periodTicks)
    {
        return Bukkit.getGlobalRegionScheduler().runAtFixedRate(DynamicShop.plugin, t -> task.run(), Math.max(1, delayTicks), Math.max(1, periodTicks));
    }

    // ---------------- Async (off-thread, no world/entity access) ----------------

    public static ScheduledTask runAsync(Runnable task)
    {
        return Bukkit.getAsyncScheduler().runNow(DynamicShop.plugin, t -> task.run());
    }

    public static ScheduledTask runAsyncDelayed(Runnable task, long delayTicks)
    {
        return Bukkit.getAsyncScheduler().runDelayed(DynamicShop.plugin, t -> task.run(), delayTicks * MS_PER_TICK, TimeUnit.MILLISECONDS);
    }

    public static ScheduledTask runAsyncTimer(Runnable task, long delayTicks, long periodTicks)
    {
        long delayMs = Math.max(1L, delayTicks * MS_PER_TICK);
        long periodMs = Math.max(1L, periodTicks * MS_PER_TICK);
        return Bukkit.getAsyncScheduler().runAtFixedRate(DynamicShop.plugin, t -> task.run(), delayMs, periodMs, TimeUnit.MILLISECONDS);
    }

    // ---------------- Region (tied to a specific location, e.g. a shop's block/sign) ----------------

    public static ScheduledTask runAtLocation(Location location, Runnable task)
    {
        return Bukkit.getRegionScheduler().run(DynamicShop.plugin, location, t -> task.run());
    }

    public static ScheduledTask runAtLocationDelayed(Location location, Runnable task, long delayTicks)
    {
        return Bukkit.getRegionScheduler().runDelayed(DynamicShop.plugin, location, t -> task.run(), Math.max(1, delayTicks));
    }

    public static ScheduledTask runAtLocationTimer(Location location, Runnable task, long delayTicks, long periodTicks)
    {
        return Bukkit.getRegionScheduler().runAtFixedRate(DynamicShop.plugin, location, t -> task.run(), Math.max(1, delayTicks), Math.max(1, periodTicks));
    }

    // ---------------- Entity (tied to a specific player/entity, e.g. their open GUI) ----------------

    public static ScheduledTask runForEntity(Entity entity, Runnable task, Runnable retired)
    {
        return entity.getScheduler().run(DynamicShop.plugin, t -> task.run(), retired);
    }

    public static ScheduledTask runForEntityDelayed(Entity entity, Runnable task, Runnable retired, long delayTicks)
    {
        return entity.getScheduler().runDelayed(DynamicShop.plugin, t -> task.run(), retired, Math.max(1, delayTicks));
    }
}
