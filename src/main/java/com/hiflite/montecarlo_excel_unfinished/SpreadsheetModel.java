package com.hiflite.montecarlo_excel_unfinished;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

public class SpreadsheetModel {

    private static final RandomGenerator RANDOM = RandomGenerator.getDefault();
    ConstsAndCntrs cac = new ConstsAndCntrs();
    YearlyData yd;
    List<YearlyData> yearlyDataList = new ArrayList<>((int) ConstsAndCntrs.NUM_YEARS);

    public static void main(String[] args) {
        SpreadsheetModel theModel = new SpreadsheetModel();
        theModel.driver();
    }

    private void driver() {

        System.out.println(new YearlyData().toStringHdr());

        for (int yearIncr = 0; yearIncr < ConstsAndCntrs.NUM_YEARS; yearIncr++) {
            yd = new YearlyData();
            yearlyDataList.add(yd);

            double currInflationRate = RANDOM.nextGaussian(ConstsAndCntrs.INFLATION_AVG, ConstsAndCntrs.INFLATION_STDDEV);
            double currInvestReturn = RANDOM.nextGaussian(ConstsAndCntrs.INVEST_RETURNS_AVG, ConstsAndCntrs.INVEST_RETURNS_STDDEV);

            YearlyData ydPrev;
            if (yearIncr > 0) {
                ydPrev = yearlyDataList.get(yearIncr - 1);
            } else {
                ydPrev = new YearlyData();
            }

            yd.setYearNumber(yearIncr);
            yd.setYear(ConstsAndCntrs.YEAR_OF_START + yearIncr);

            // inflation for the year, and the average investment return
            yd.setInflationRateAvg(currInflationRate);
            yd.setInvestReturnAvg(currInvestReturn);
            yd.setBobAgeInSeptember(ConstsAndCntrs.BOB_AGE_IN_SEPTEMBER+yearIncr);

            //System.out.printf("year = %4d ; inflation = %7.4f ; investment return = %7.4f\n", yd.getYear(), yd.getInflationRateAvg(), yd.getInvestReturnAvg());

            // initialize balance and withdrawal before guardrail
            if (yearIncr == 0) {
                yd.setStartingPortfolioBalance(ConstsAndCntrs.STARTING_PORTFOLIO_AMT);
                yd.setWithdrawalAmtBeforeGuardrail(ConstsAndCntrs.STARTING_PORTFOLIO_AMT * ConstsAndCntrs.STARTING_WITHDRAWAL_PCT);
                yd.setWithdrawalPctBeforeGuardrail(ConstsAndCntrs.STARTING_WITHDRAWAL_PCT);

                ydPrev.setEndPortfolioBalInclInflation(ConstsAndCntrs.STARTING_PORTFOLIO_AMT);
                ydPrev.setWithdrawalAmtAfterGuardrail(ConstsAndCntrs.STARTING_PORTFOLIO_AMT * ConstsAndCntrs.STARTING_WITHDRAWAL_PCT);
                ydPrev.setWithdrawalPctAfterGuardrail(ConstsAndCntrs.STARTING_WITHDRAWAL_PCT);

                yd.setInflationFactor(1.0);

            }
            if (yearIncr > 0) {
                yd.setInflationFactor(currInflationRate * (1+ydPrev.getInflationRateAvg()));
                yd.setWithdrawalAmtBeforeGuardrail(ConstsAndCntrs.STARTING_PORTFOLIO_AMT * ConstsAndCntrs.STARTING_WITHDRAWAL_PCT);
                yd.setWithdrawalPctBeforeGuardrail(ConstsAndCntrs.STARTING_WITHDRAWAL_PCT);

            } else {
                // starting numbers
                double portfolioAmtBeforeGuardrail = ydPrev.getEndPortfolioBalInclInflation();
                yd.setStartingPortfolioBalance(portfolioAmtBeforeGuardrail);

                double wdAmtBeforeGuardrail = ydPrev.getWithdrawalAmtAfterGuardrail();
                if (ydPrev.getInvestReturnAvg()>0.0) {
                    wdAmtBeforeGuardrail = ydPrev.getWithdrawalAmtAfterGuardrail() * (1+ ydPrev.getInflationRateAvg());
                }
                yd.setWithdrawalAmtBeforeGuardrail(wdAmtBeforeGuardrail);
                yd.setWithdrawalPctBeforeGuardrail(wdAmtBeforeGuardrail / portfolioAmtBeforeGuardrail);

                // test upper and lower guardrail
                double adjusted = yd.getWithdrawalAmtBeforeGuardrail();
                double pctAfterGuardrail = yd.getWithdrawalPctBeforeGuardrail();;
                if (yd.getWithdrawalPctBeforeGuardrail() > ConstsAndCntrs.UPPER_GUARDRAIL_PCT) {
                    adjusted = yd.getWithdrawalAmtBeforeGuardrail()*(1-ConstsAndCntrs.WITHDRAWAL_ADJUST_PCT);
                    long badGR = yd.getUpper_withdrawalRateGuardrailUsed_bad();
                    yd.setUpper_withdrawalRateGuardrailUsed_bad(badGR++);
                } else if (yd.getWithdrawalPctBeforeGuardrail() < ConstsAndCntrs.LOWER_GUARDRAIL_PCT) {
                    adjusted = yd.getWithdrawalAmtBeforeGuardrail()*(1+ConstsAndCntrs.WITHDRAWAL_ADJUST_PCT);
                }
                if (yd.getWithdrawalAmtBeforeGuardrail() != 0.0) {
                    pctAfterGuardrail = adjusted / yd.getWithdrawalAmtBeforeGuardrail();
                }
                yd.setWithdrawalAmtAfterGuardrail(adjusted);
                yd.setWithdrawalPctAfterGuardrail(pctAfterGuardrail);

                double portfolioGrowth = ConstsAndCntrs.STARTING_PORTFOLIO_AMT * yd.getInvestReturnAvg();
                yd.setPortfolioGrowth(portfolioGrowth);
                yd.setEndPortfolioBalBeforeInflation(yd.getStartingPortfolioBalance() - yd.getWithdrawalAmtAfterGuardrail() + portfolioGrowth);

                // inflationFactor is cumulative
                double inflationFactor = ydPrev.getInflationFactor();
                yd.setInflationFactor(inflationFactor * (1+yd.getInvestReturnAvg()));
                yd.setEndPortfolioBalInclInflation(yd.getEndPortfolioBalBeforeInflation() * currInflationRate);
                yd.setEndPortfolioBalIn2025Dollars( yd.getEndPortfolioBalInclInflation() / yd.getInflationFactor());


            }
            String tableData = yd.toStringData();
            System.out.println(tableData);

        }

        System.out.printf("\nAverage inflation = %7.4f ; Average investment return = %7.4f\n", calcAvgInflation(), calcAvgInvestReturn());
    }

    private double calcAvgInflation() {
        double sum = 0;
        for (YearlyData yd : yearlyDataList) {
            sum =  sum + yd.getInflationRateAvg();
        }
        return sum / yearlyDataList.size();
    }

    private double calcAvgInvestReturn() {
        double sum = 0;
        for (YearlyData yd : yearlyDataList) {
            sum =  sum + yd.getInvestReturnAvg();
        }
        return sum / yearlyDataList.size();
    }

}
