package com.hiflite.montecarlo_excel;

import java.util.LinkedList;
import java.util.List;
import java.util.random.RandomGenerator;

public class SpreadsheetModel {

    private static final RandomGenerator RANDOM = RandomGenerator.getDefault();
    YearlyData yd;
    List<YearlyData> yearlyDataList = new LinkedList<>();

    public static void main(String[] args) {
        SpreadsheetModel theModel = new SpreadsheetModel();
        theModel.driver();
    }

    private void driver() {

        for (int yearNum = 0; yearNum < Inputs_And_Results.NUM_YEARS; yearNum++) {
            yd = new YearlyData();
            yearlyDataList.add(yd);

            YearlyData ydPrev;
            if (yearNum > 0) {
                ydPrev = yearlyDataList.get(yearNum - 1);
            } else {
                ydPrev = new YearlyData();
            }

            yd.setYearNumber(yearNum);
            yd.setYear(Inputs_And_Results.YEAR_OF_START + yearNum);

            // inflation for the year, and the average investment return
            yd.setInflationRateAvg(RANDOM.nextGaussian(Inputs_And_Results.INFLATION_AVG, Inputs_And_Results.INFLATION_STDDEV));
            yd.setInvestReturnAvg(RANDOM.nextGaussian(Inputs_And_Results.INVEST_RETURNS_AVG, Inputs_And_Results.INVEST_RETURNS_STDDEV));
            System.out.printf("year = %4d ; inflation = %7.4f ; investment return = %7.4f\n", yd.getYear(), yd.getInflationRateAvg(), yd.getInvestReturnAvg());

            // balance and withdrawal before guardrail
            if (yearNum == 0) {
                yd.setStartingPortfolioBalance(Inputs_And_Results.STARTING_PORTFOLIO_AMT);
                yd.setWithdrawalAmtBeforeGuardrail(Inputs_And_Results.STARTING_PORTFOLIO_AMT * Inputs_And_Results.STARTING_WITHDRAWAL_PCT);
                yd.setWithdrawalPctBeforeGuardrail(Inputs_And_Results.STARTING_WITHDRAWAL_PCT);
            } else {
                double portfolioAmtBeforeGuardrail = ydPrev.getEndPortfolioIncludesInflation();
                yd.setWithdrawalAmtBeforeGuardrail(ydPrev.getEndPortfolioIncludesInflation());
                yd.setStartingPortfolioBalance(portfolioAmtBeforeGuardrail);
                yd.setWithdrawalPctBeforeGuardrail(yd.getWithdrawalAmtBeforeGuardrail()/portfolioAmtBeforeGuardrail);
            }
            int x=1;

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
