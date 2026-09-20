package me.sat7.dynamicshop.transactions;

import me.sat7.dynamicshop.files.CustomConfig;
import me.sat7.dynamicshop.utilities.ConfigUtil;
import me.sat7.dynamicshop.utilities.MathUtil;
import me.sat7.dynamicshop.utilities.RandomPriceUtil;
import me.sat7.dynamicshop.utilities.ShopUtil;
import org.bukkit.configuration.file.FileConfiguration;

public final class Calc
{
    private Calc()
    {

    }

    // 특정 아이탬의 현재 가치를 계산 (다이나믹 or 고정가) (세금 반영)
    public static double getCurrentPrice(String shopName, String idx, boolean buy)
    {
        return getCurrentPrice(shopName, idx, buy, false);
    }

    public static double getCurrentPrice(String shopName, String idx, boolean buy, boolean raw)
    {
        return getCurrentPrice(shopName, idx, buy, raw, true);
    }

    private static double getCurrentPrice(String shopName, String idx, boolean buy, boolean raw, boolean applyRandom)
    {
        FileConfiguration data = ShopUtil.shopConfigFiles.get(shopName).get();

        double value;
        if (!buy && data.contains(idx + ".value2"))
        {
            value = data.getDouble(idx + ".value2");
        } else
        {
            value = data.getDouble(idx + ".value");
        }

        double min = data.getDouble(idx + ".valueMin", 0.0001);
        double max = data.getDouble(idx + ".valueMax");
        int median = data.getInt(idx + ".median");
        int stock = data.getInt(idx + ".stock");

        double price;
        if (median <= 0 || stock <= 0)
        {
            price = value;
        } else
        {
            if(!buy && stock < Integer.MAX_VALUE)
                stock = stock + 1;

            price = (median * value) / stock;
        }

        if (price < min)
        {
            price = min;
        }
        if (max != 0 && price > max)
        {
            price = max;
        }

        // 할인
        if (data.contains(idx + ".discount"))
        {
            int discount = data.getInt(idx + ".discount");
            price = price * (100 - discount) / 100;
        }

        // 랜덤 가격 (퍼센트). 기능이 꺼져있거나 대상이 아니면 0%
        double randomPercent = applyRandom ? RandomPriceUtil.GetPercent(data, idx, buy) : 0;
        if (randomPercent != 0)
        {
            price = RandomPriceUtil.Apply(price, randomPercent);
            if (price < min)
            {
                price = min;
            }
        }

        // 판매세 계산 (임의 지정된 판매가치가 없는 경우에만)
        if (!buy && !data.contains(idx + ".value2"))
        {
            double tax = ((price / 100) * getTaxRate(shopName));
            price -= tax;
        }

        // 랜덤 가격 안전장치: 판매가가 구매가보다 높아지면 무한 돈복사가 되므로 구매가로 제한함.
        // 상점에서 살 수 없는 아이탬은 되팔기 순환 자체가 불가능하므로 제한하지 않음.
        if (!buy && RandomPriceUtil.IsEnabled(data) && !IsSellOnly(data, idx))
        {
            double buyPrice = getCurrentPrice(shopName, idx, true, true);
            if (price > buyPrice)
            {
                price = buyPrice;
            }
        }

        if (!raw && data.contains("Options.flag.integeronly"))
        {
            double rounded = buy ? Math.ceil(price) : Math.floor(price);

            // 정수로 반올림하면 설정한 퍼센트 범위를 벗어날 수 있음. 범위 안의 정수로 되돌림.
            if (randomPercent != 0)
            {
                double base = getCurrentPrice(shopName, idx, buy, true, false);
                rounded = KeepInsideRandomRange(data, rounded, base, buy);
            }

            return rounded;
        }
        else
        {
            return price;
        }
    }

    // 반올림된 값을 랜덤 범위 안의 정수로 맞춤. 범위 안에 정수가 없으면 기준값을 반올림한 값을 씀.
    private static double KeepInsideRandomRange(FileConfiguration data, double rounded, double base, boolean buy)
    {
        if (base <= 0)
            return rounded;

        int min = RandomPriceUtil.GetMinPercent(data);
        int max = RandomPriceUtil.GetMaxPercent(data);
        if (min > max)
        {
            int temp = min;
            min = max;
            max = temp;
        }

        double lowest = Math.ceil(base * (100 + min) / 100 - 0.000001);
        double highest = Math.floor(base * (100 + max) / 100 + 0.000001);

        if (lowest > highest)
            return buy ? Math.ceil(base) : Math.floor(base);

        return MathUtil.Clamp(rounded, lowest, highest);
    }

    // 특정 아이탬의 앞으로 n개의 가치합을 계산 (다이나믹 or 고정가) ([0] 세금 반영된 값, [1] 세금)
    public static double[] calcTotalCost(String shopName, String idx, int amount)
    {
        return calcTotalCost(shopName, idx, amount, true);
    }

    private static double[] calcTotalCost(String shopName, String idx, int amount, boolean applyRandom)
    {
        FileConfiguration data = ShopUtil.shopConfigFiles.get(shopName).get();

        double total = 0;
        int median = data.getInt(idx + ".median");
        int stock = data.getInt(idx + ".stock");

        double value;
        if (amount < 0 && data.contains(idx + ".value2"))
        {
            value = data.getDouble(idx + ".value2");
        } else
        {
            value = data.getDouble(idx + ".value");
        }

        if (median <= 0 || stock <= 0)
        {
            total = value * Math.abs(amount);
        } else
        {
            for (int i = 0; i < Math.abs(amount); i++)
            {
                if (amount < 0 && stock < Integer.MAX_VALUE)
                {
                    stock++;
                }
                double temp = median * value / stock;
                double min = data.getDouble(idx + ".valueMin", 0.0001);
                double max = data.getDouble(idx + ".valueMax");

                if (temp < min)
                {
                    temp = min;
                }
                if (max != 0 && temp > max)
                {
                    temp = max;
                }

                total += temp;

                if (amount > 0)
                {
                    stock--;
                    if (stock < 2)
                    {
                        break;
                    }
                }
            }
        }

        // 할인
        if (data.contains(idx + ".discount"))
        {
            int discount = data.getInt(idx + ".discount");
            total = total * (100 - discount) / 100;
        }

        // 랜덤 가격 (퍼센트)
        double randomPercent = applyRandom ? RandomPriceUtil.GetPercent(data, idx, amount > 0) : 0;
        if (randomPercent != 0)
        {
            total = RandomPriceUtil.Apply(total, randomPercent);
            if (total < 0)
            {
                total = 0;
            }
        }

        // 세금 적용 (판매가 별도지정시 세금계산 안함)
        double tax = 0;
        if (amount < 0 && !data.contains(idx + ".value2"))
        {
            tax = ((total / 100) * getTaxRate(shopName));
            total -= tax;
        }

        // 랜덤 가격 안전장치 (getCurrentPrice와 동일한 이유)
        if (amount < 0 && RandomPriceUtil.IsEnabled(data) && !IsSellOnly(data, idx))
        {
            double buyTotal = calcTotalCost(shopName, idx, -amount)[0];
            if (total > buyTotal)
            {
                total = buyTotal;
            }
        }

        if (data.contains("Options.flag.integeronly") && applyRandom)
        {
            total = amount > 0 ? Math.ceil(total) : Math.floor(total);

            // 단가와 같은 이유로, 반올림된 합계도 설정한 퍼센트 범위 안에 있어야 함.
            if (randomPercent != 0)
            {
                double baseTotal = calcTotalCost(shopName, idx, amount, false)[0];
                total = KeepInsideRandomRange(data, total, baseTotal, amount > 0);
            }
        }
        else
        {
            // 범위 계산용 기준값(applyRandom == false)은 반올림하지 않음. 여기서 반올림하면 범위가 한 칸씩 밀림.
            total = (Math.round(total * 10000) / 10000.0);
        }

        return new double[]{total, tax};
    }

    // 돈복사 안전장치가 실제로 판매가를 깎았는지 확인함. 툴팁에서 이유를 알려주기 위함.
    public static boolean IsSellCappedByBuyPrice(String shopName, String idx)
    {
        FileConfiguration data = ShopUtil.shopConfigFiles.get(shopName).get();

        if (!RandomPriceUtil.IsEnabled(data) || IsSellOnly(data, idx))
            return false;

        return getCurrentPrice(shopName, idx, false, true) >= getCurrentPrice(shopName, idx, true, true) - 0.0001
                && getUncappedSellPrice(shopName, idx) > getCurrentPrice(shopName, idx, true, true) + 0.0001;
    }

    // 안전장치를 적용하기 전의 판매가.
    private static double getUncappedSellPrice(String shopName, String idx)
    {
        FileConfiguration data = ShopUtil.shopConfigFiles.get(shopName).get();

        double value = data.contains(idx + ".value2") ? data.getDouble(idx + ".value2") : data.getDouble(idx + ".value");
        double min = data.getDouble(idx + ".valueMin", 0.0001);
        double max = data.getDouble(idx + ".valueMax");
        int median = data.getInt(idx + ".median");
        int stock = data.getInt(idx + ".stock");

        double price;
        if (median <= 0 || stock <= 0)
        {
            price = value;
        } else
        {
            if (stock < Integer.MAX_VALUE)
                stock = stock + 1;

            price = (median * value) / stock;
        }

        if (price < min)
            price = min;
        if (max != 0 && price > max)
            price = max;

        if (data.contains(idx + ".discount"))
            price = price * (100 - data.getInt(idx + ".discount")) / 100;

        double randomPercent = RandomPriceUtil.GetPercent(data, idx, false);
        if (randomPercent != 0)
        {
            price = RandomPriceUtil.Apply(price, randomPercent);
            if (price < min)
                price = min;
        }

        if (!data.contains(idx + ".value2"))
            price -= (price / 100) * getTaxRate(shopName);

        return price;
    }

    // 상점에서 구매할 수 없는 아이탬인지 확인함.
    public static boolean IsSellOnly(FileConfiguration data, String idx)
    {
        String tradeType = data.getString(idx + ".tradeType");
        return tradeType != null && tradeType.equalsIgnoreCase("SellOnly");
    }

    // 상점의 세율 반환
    public static int getTaxRate(String shopName)
    {
        CustomConfig data = ShopUtil.shopConfigFiles.get(shopName);

        if (data.get().contains("Options.SalesTax"))
        {
            return data.get().getInt("Options.SalesTax");
        } else
        {
            return ConfigUtil.getCurrentTax();
        }
    }
}
