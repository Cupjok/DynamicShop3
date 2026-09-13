package dstesteco;

import net.milkbowl.vault.economy.AbstractEconomy;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Test-only in-memory Vault economy. Every account starts with 1000. */
public class DSTestEco extends JavaPlugin
{
    @Override
    public void onLoad()
    {
        getServer().getServicesManager().register(Economy.class, new Eco(), this, ServicePriority.Highest);
    }

    static final class Eco extends AbstractEconomy
    {
        private final Map<String, Double> bal = new ConcurrentHashMap<>();

        private double get(String n) { return bal.computeIfAbsent(n.toLowerCase(), k -> 1000.0); }

        @Override public boolean isEnabled() { return true; }
        @Override public String getName() { return "DSTestEco"; }
        @Override public boolean hasBankSupport() { return false; }
        @Override public int fractionalDigits() { return 2; }
        @Override public String format(double v) { return String.format("%.2f", v); }
        @Override public String currencyNamePlural() { return "coins"; }
        @Override public String currencyNameSingular() { return "coin"; }
        @Override public boolean hasAccount(String n) { return true; }
        @Override public boolean hasAccount(String n, String w) { return true; }
        @Override public double getBalance(String n) { return get(n); }
        @Override public double getBalance(String n, String w) { return get(n); }
        @Override public boolean has(String n, double a) { return get(n) >= a; }
        @Override public boolean has(String n, String w, double a) { return has(n, a); }

        @Override
        public EconomyResponse withdrawPlayer(String n, double a)
        {
            if (a < 0) return new EconomyResponse(0, get(n), EconomyResponse.ResponseType.FAILURE, "negative");
            if (get(n) < a) return new EconomyResponse(0, get(n), EconomyResponse.ResponseType.FAILURE, "funds");
            bal.put(n.toLowerCase(), get(n) - a);
            return new EconomyResponse(a, get(n), EconomyResponse.ResponseType.SUCCESS, null);
        }

        @Override public EconomyResponse withdrawPlayer(String n, String w, double a) { return withdrawPlayer(n, a); }

        @Override
        public EconomyResponse depositPlayer(String n, double a)
        {
            if (a < 0) return new EconomyResponse(0, get(n), EconomyResponse.ResponseType.FAILURE, "negative");
            bal.put(n.toLowerCase(), get(n) + a);
            return new EconomyResponse(a, get(n), EconomyResponse.ResponseType.SUCCESS, null);
        }

        @Override public EconomyResponse depositPlayer(String n, String w, double a) { return depositPlayer(n, a); }

        private static EconomyResponse noBank() { return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "no banks"); }
        @Override public EconomyResponse createBank(String n, String p) { return noBank(); }
        @Override public EconomyResponse deleteBank(String n) { return noBank(); }
        @Override public EconomyResponse bankBalance(String n) { return noBank(); }
        @Override public EconomyResponse bankHas(String n, double a) { return noBank(); }
        @Override public EconomyResponse bankWithdraw(String n, double a) { return noBank(); }
        @Override public EconomyResponse bankDeposit(String n, double a) { return noBank(); }
        @Override public EconomyResponse isBankOwner(String n, String p) { return noBank(); }
        @Override public EconomyResponse isBankMember(String n, String p) { return noBank(); }
        @Override public List<String> getBanks() { return List.of(); }
        @Override public boolean createPlayerAccount(String n) { return true; }
        @Override public boolean createPlayerAccount(String n, String w) { return true; }
    }
}
