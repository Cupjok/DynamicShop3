package me.sat7.dynamicshop.files;

import me.sat7.dynamicshop.transactions.Calc;
import me.sat7.dynamicshop.utilities.RandomPriceUtil;
import me.sat7.dynamicshop.utilities.ShopUtil;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CalcRandomPriceTest
{
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final String SHOP = "RandomPriceTestShop";

    private CustomConfig shop(boolean randomEnabled, double value, Double value2, int buyPercent, int sellPercent) throws Exception
    {
        File f = tmp.newFile(SHOP + ".yml");
        CustomConfig cc = new CustomConfig();
        cc.setupFile(f);

        cc.get().set("Options.SalesTax", 0);
        cc.get().set("0.mat", "DIAMOND");
        cc.get().set("0.value", value);
        if (value2 != null)
            cc.get().set("0.value2", value2);
        cc.get().set("0.median", 0);
        cc.get().set("0.stock", 0);

        if (randomEnabled)
        {
            cc.get().set(RandomPriceUtil.ROOT + ".enable", true);
            cc.get().set(RandomPriceUtil.ROOT + ".target", RandomPriceUtil.TARGET_BOTH);
            cc.get().set("0.randomPrice.buy", buyPercent);
            cc.get().set("0.randomPrice.sell", sellPercent);
        }

        ShopUtil.shopConfigFiles.put(SHOP, cc);
        return cc;
    }

    @After
    public void cleanup()
    {
        ShopUtil.shopConfigFiles.remove(SHOP);
    }

    @Test
    public void randomPercentMovesTheUnitPrice() throws Exception
    {
        shop(true, 50, null, 10, -10);

        assertEquals(55, Calc.getCurrentPrice(SHOP, "0", true), 0.0001);
        assertEquals(45, Calc.getCurrentPrice(SHOP, "0", false), 0.0001);
    }

    @Test
    public void sellPriceNeverEndsUpAboveTheBuyPrice() throws Exception
    {
        // sell rolls up, buy rolls down: without the clamp this would let players buy and sell for a profit.
        shop(true, 50, null, -20, 20);

        double buy = Calc.getCurrentPrice(SHOP, "0", true);
        double sell = Calc.getCurrentPrice(SHOP, "0", false);

        assertEquals(40, buy, 0.0001);
        assertTrue("sell " + sell + " must not exceed buy " + buy, sell <= buy + 0.0001);
    }

    @Test
    public void sellTotalNeverEndsUpAboveTheBuyTotal() throws Exception
    {
        shop(true, 50, null, -20, 20);

        double buyTotal = Calc.calcTotalCost(SHOP, "0", 8)[0];
        double sellTotal = Calc.calcTotalCost(SHOP, "0", -8)[0];

        assertTrue("sell total " + sellTotal + " must not exceed buy total " + buyTotal, sellTotal <= buyTotal + 0.0001);
    }

    @Test
    public void aSeparateSellValueAboveTheBuyValueIsKeptWhenRandomPriceIsOff() throws Exception
    {
        // The clamp only guards the random price feature; an existing shop keeps behaving as before.
        shop(false, 50, 80.0, 0, 0);

        assertEquals(50, Calc.getCurrentPrice(SHOP, "0", true), 0.0001);
        assertEquals(80, Calc.getCurrentPrice(SHOP, "0", false), 0.0001);
    }
}
