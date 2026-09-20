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
import static org.junit.Assert.assertFalse;

public class CalcRandomPriceTest
{
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final String SHOP = "RandomPriceTestShop";

    private CustomConfig shop(boolean randomEnabled, double value, Double value2, int buyPercent, int sellPercent) throws Exception
    {
        File f = new File(tmp.getRoot(), SHOP + ".yml");
        f.delete();
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
            cc.get().set(RandomPriceUtil.ROOT + ".min", -50);
            cc.get().set(RandomPriceUtil.ROOT + ".max", 50);
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

    @Test
    public void sellOnlyItemsKeepTheirOwnSellValue() throws Exception
    {
        // 상점에서 살 수 없는 아이탬은 되팔기 순환이 불가능하므로 구매가로 제한하지 않음.
        CustomConfig cc = shop(true, 10, 37.0, 32, -3);
        cc.get().set("0.tradeType", "SellOnly");

        // 37 * 0.97 = 35.89. The clamp would have cut this down to 10 * 1.32 = 13.2.
        assertEquals(35.89, Calc.getCurrentPrice(SHOP, "0", false), 0.0001);
    }

    @Test
    public void sellOnlyTotalsKeepTheirOwnSellValue() throws Exception
    {
        CustomConfig cc = shop(true, 10, 37.0, 32, -3);
        cc.get().set("0.tradeType", "SellOnly");

        assertEquals(35.89 * 4, Calc.calcTotalCost(SHOP, "0", -4)[0], 0.0001);
    }

    @Test
    public void buyableItemsStillGetClampedWhenTheSellValueIsHigher() throws Exception
    {
        // 살 수 있는 아이탬은 그대로 제한함.
        shop(true, 10, 37.0, 32, -3);

        double buy = Calc.getCurrentPrice(SHOP, "0", true);
        double sell = Calc.getCurrentPrice(SHOP, "0", false);

        assertEquals(13.2, buy, 0.0001);
        assertEquals(13.2, sell, 0.0001);
    }

    @Test
    public void theCapReasonIsReportedOnlyWhenTheGuardActuallyCuts() throws Exception
    {
        // 살 수 있는 상품 + 판매 기준값이 구매가보다 높음 -> 안전장치가 깎음.
        shop(true, 10, 37.0, 32, -3);
        assertTrue(Calc.IsSellCappedByBuyPrice(SHOP, "0"));

        // 판매가가 구매가보다 낮으면 깎을 일이 없음.
        shop(true, 100, 10.0, 0, 0);
        assertFalse(Calc.IsSellCappedByBuyPrice(SHOP, "0"));

        // SellOnly는 안전장치 대상이 아님.
        CustomConfig cc = shop(true, 10, 37.0, 32, -3);
        cc.get().set("0.tradeType", "SellOnly");
        assertFalse(Calc.IsSellCappedByBuyPrice(SHOP, "0"));
    }

    @Test
    public void numericStringsAreRepairedInsteadOfReadingAsZero() throws Exception
    {
        CustomConfig cc = shop(false, 50, null, 0, 0);
        cc.get().set("0.value2", "1.28E7");

        assertEquals(0, Calc.getCurrentPrice(SHOP, "0", false), 0.0001);

        ShopUtil.RepairNumericStrings();

        assertEquals(1.28E7, Calc.getCurrentPrice(SHOP, "0", false), 0.0001);
    }

    @Test
    public void integerRoundingNeverLeavesTheRandomRange() throws Exception
    {
        // 판매가 1, 범위 -50%..+50% -> 0.5 로 내려가지만 정수로는 1 밖에 없음. 0원이 되면 안됨.
        CustomConfig cc = shop(true, 10, 1.0, 0, -50);
        cc.get().set("Options.flag.integeronly", "");
        cc.get().set("0.tradeType", "SellOnly");

        assertEquals(1, Calc.getCurrentPrice(SHOP, "0", false), 0.0001);
    }

    @Test
    public void integerRoundingStaysInsideTheRangeForNormalPrices() throws Exception
    {
        // 기준 100, -3% -> 97. 정수 범위는 50..150 이므로 그대로 둬야 함.
        CustomConfig cc = shop(true, 1000, 100.0, 0, -3);
        cc.get().set("Options.flag.integeronly", "");
        cc.get().set("0.tradeType", "SellOnly");

        assertEquals(97, Calc.getCurrentPrice(SHOP, "0", false), 0.0001);
    }

    @Test
    public void integerRoundingKeepsTotalsInsideTheRange() throws Exception
    {
        CustomConfig cc = shop(true, 10, 1.0, 0, -50);
        cc.get().set("Options.flag.integeronly", "");
        cc.get().set("0.tradeType", "SellOnly");

        // 기준 합계 4, 범위 2..6. 반올림으로 0이 되면 안됨.
        double total = Calc.calcTotalCost(SHOP, "0", -4)[0];
        assertTrue("total " + total, total >= 2 && total <= 6);
    }

    @Test
    public void buyRoundingDoesNotPushThePriceAboveTheRange() throws Exception
    {
        // 기준 2, +50% -> 3. 올림이 범위 위로 넘지 않아야 함.
        CustomConfig cc = shop(true, 2, null, 50, 0);
        cc.get().set("Options.flag.integeronly", "");

        assertEquals(3, Calc.getCurrentPrice(SHOP, "0", true), 0.0001);
    }

    @Test
    public void unsellableItemsAreReportedWithoutChangingPrices() throws Exception
    {
        CustomConfig cc = shop(false, 10, 0.5, 0, 0);
        cc.get().set("Options.flag.integeronly", "");

        ShopUtil.WarnAboutUnsellableItems();

        // 경고만 하고 값은 그대로여야 함.
        assertEquals(0.5, cc.get().getDouble("0.value2"), 0.0001);
        assertEquals(0, Calc.getCurrentPrice(SHOP, "0", false), 0.0001);
    }
}
