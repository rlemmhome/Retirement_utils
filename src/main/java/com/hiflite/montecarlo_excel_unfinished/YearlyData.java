package com.hiflite.montecarlo_excel_unfinished;

import org.apache.commons.lang3.builder.ToStringStyle;

public class YearlyData {

    private long yearNumber                            = 0L;
    private long year                                  = 0;
    private long bobAgeInSeptember                     = 65;

    private double startingPortfolioBalance            = 1_500_000.0;
    private double withdrawalAmtBeforeGuardrail        = 75_000.0;
    private double withdrawalPctBeforeGuardrail        = 0.0;
    private double withdrawalAmtAfterGuardrail         = 0.0;
    private double withdrawalPctAfterGuardrail         = 0.0;
    private double portfolioGrowth                     = 0.0;
    private double endPortfolioBalBeforeInflation      = 1_500_000.0;
    private long upper_withdrawalRateGuardrailUsed_bad = 0;

    private double inflationRateAvg                    = 0.0379;
    private double investReturnAvg                     = 0.0670;
    private double withdrawalEqualsThisAmtIn2026       = 75_000.0;

    private double inflationFactor                     = 1.00;
    private double endPortfolioBalInclInflation        = 1_500_000.0;
    private long   portfolioDroppedBelowWarning        = 0;
    private double endPortfolioBalIn2025Dollars        = 1_500_000.0;

    public long getYearNumber() {
        return yearNumber;
    }

    public void setYearNumber(long yearNumber) {
        this.yearNumber = yearNumber;
    }

    public long getYear() {
        return year;
    }

    public void setYear(long year) {
        this.year = year;
    }

    public long getBobAgeInSeptember() {
        return bobAgeInSeptember;
    }

    public void setBobAgeInSeptember(long bobAgeInSeptember) {
        this.bobAgeInSeptember = bobAgeInSeptember;
    }

    public double getStartingPortfolioBalance() {
        return startingPortfolioBalance;
    }

    public void setStartingPortfolioBalance(double startingPortfolioBalance) {
        this.startingPortfolioBalance = startingPortfolioBalance;
    }

    public double getWithdrawalAmtBeforeGuardrail() {
        return withdrawalAmtBeforeGuardrail;
    }

    public void setWithdrawalAmtBeforeGuardrail(double withdrawalAmtBeforeGuardrail) {
        this.withdrawalAmtBeforeGuardrail = withdrawalAmtBeforeGuardrail;
    }

    public double getWithdrawalPctBeforeGuardrail() {
        return withdrawalPctBeforeGuardrail;
    }

    public void setWithdrawalPctBeforeGuardrail(double withdrawalPctBeforeGuardrail) {
        this.withdrawalPctBeforeGuardrail = withdrawalPctBeforeGuardrail;
    }

    public double getWithdrawalAmtAfterGuardrail() {
        return withdrawalAmtAfterGuardrail;
    }

    public void setWithdrawalAmtAfterGuardrail(double withdrawalAmtAfterGuardrail) {
        this.withdrawalAmtAfterGuardrail = withdrawalAmtAfterGuardrail;
    }

    public double getWithdrawalPctAfterGuardrail() {
        return withdrawalPctAfterGuardrail;
    }

    public void setWithdrawalPctAfterGuardrail(double withdrawalPctAfterGuardrail) {
        this.withdrawalPctAfterGuardrail = withdrawalPctAfterGuardrail;
    }

    public double getPortfolioGrowth() {
        return portfolioGrowth;
    }

    public void setPortfolioGrowth(double portfolioGrowth) {
        this.portfolioGrowth = portfolioGrowth;
    }

    public double getEndPortfolioBalBeforeInflation() {
        return endPortfolioBalBeforeInflation;
    }

    public void setEndPortfolioBalBeforeInflation(double endPortfolioBalBeforeInflation) {
        this.endPortfolioBalBeforeInflation = endPortfolioBalBeforeInflation;
    }

    public long getUpper_withdrawalRateGuardrailUsed_bad() {
        return upper_withdrawalRateGuardrailUsed_bad;
    }

    public void setUpper_withdrawalRateGuardrailUsed_bad(long upper_withdrawalRateGuardrailUsed_bad) {
        this.upper_withdrawalRateGuardrailUsed_bad = upper_withdrawalRateGuardrailUsed_bad;
    }

    public double getInflationRateAvg() {
        return inflationRateAvg;
    }

    public void setInflationRateAvg(double inflationRateAvg) {
        this.inflationRateAvg = inflationRateAvg;
    }

    public double getInvestReturnAvg() {
        return investReturnAvg;
    }

    public void setInvestReturnAvg(double investReturnAvg) {
        this.investReturnAvg = investReturnAvg;
    }

    public double getWithdrawalEqualsThisAmtIn2026() {
        return withdrawalEqualsThisAmtIn2026;
    }

    public void setWithdrawalEqualsThisAmtIn2026(double withdrawalEqualsThisAmtIn2026) {
        this.withdrawalEqualsThisAmtIn2026 = withdrawalEqualsThisAmtIn2026;
    }

    public double getInflationFactor() {
        return inflationFactor;
    }

    public void setInflationFactor(double inflationFactor) {
        this.inflationFactor = inflationFactor;
    }

    public double getEndPortfolioBalInclInflation() {
        return endPortfolioBalInclInflation;
    }

    public void setEndPortfolioBalInclInflation(double endPortfolioBalInclInflation) {
        this.endPortfolioBalInclInflation = endPortfolioBalInclInflation;
    }

    public long getPortfolioDroppedBelowWarning() {
        return portfolioDroppedBelowWarning;
    }

    public void setPortfolioDroppedBelowWarning(long portfolioDroppedBelowWarning) {
        this.portfolioDroppedBelowWarning = portfolioDroppedBelowWarning;
    }

    public double getEndPortfolioBalIn2025Dollars() {
        return endPortfolioBalIn2025Dollars;
    }

    public void setEndPortfolioBalIn2025Dollars(double endPortfolioBalIn2025Dollars) {
        this.endPortfolioBalIn2025Dollars = endPortfolioBalIn2025Dollars;
    }

    @Override
    public String toString() {
        return new org.apache.commons.lang3.builder.ToStringBuilder(this, ToStringStyle.JSON_STYLE)
                .append("yearNumber", yearNumber)
                .append("year", year)
                .append("bobAgeInSeptember", bobAgeInSeptember)
                .append("startingPortfolioBalance", startingPortfolioBalance)
                .append("withdrawalAmtBeforeGuardrail", withdrawalAmtBeforeGuardrail)
                .append("withdrawalPctBeforeGuardrail", withdrawalPctBeforeGuardrail)
                .append("withdrawalAmtAfterGuardrail", withdrawalAmtAfterGuardrail)
                .append("withdrawalPctAfterGuardrail", withdrawalPctAfterGuardrail)
                .append("portfolioGrowth", portfolioGrowth)
                .append("endingPortfolioBeforeInflation", endPortfolioBalBeforeInflation)
                .append("upper_withdrawalRateGuardrailUsed_bad", upper_withdrawalRateGuardrailUsed_bad)
                .append("inflationRateAvg", inflationRateAvg)
                .append("investReturnAvg", investReturnAvg)
                .append("withdrawalEqualsThisAmtIn2026", withdrawalEqualsThisAmtIn2026)
                .append("inflationFactor", inflationFactor)
                .append("endPortfolioIncludesInflation", endPortfolioBalInclInflation)
                .append("portfolioDroppedBelowWarning", portfolioDroppedBelowWarning)
                .append("endPortfolioIn2025Dollars", endPortfolioBalIn2025Dollars)
                .toString();
    }

    public String toCSVString() {
        StringBuffer sb = new StringBuffer();
        sb.append("year,")
                .append("bobAgeInSeptember,")
                .append("startingPortfolioBalance,")
                .append("withdrawalAmtBeforeGuardrail,")
                .append("withdrawalPctBeforeGuardrail,")
                .append("withdrawalAmtAfterGuardrail,")
                .append("withdrawalPctAfterGuardrail,")
                .append("portfolioGrowth,")
                .append("endingPortfolioBeforeInflation,")
                .append("upper_withdrawalRateGuardrailUsed_bad,")
                .append("inflationRateAvg,")
                .append("investReturnAvg,")
                .append("withdrawalEqualsThisAmtIn2026,")
                .append("inflationFactor,")
                .append("endPortfolioIncludesInflation,")
                .append("portfolioDroppedBelowWarning,")
                .append("endPortfolioIn2025Dollars")
                .append("\n");
        return sb.toString();
    }

    public String toStringHdr() {
            StringBuffer sb = new StringBuffer();
            sb.append("year,")
                    .append("yearNumber,")
                    .append("bobAgeInSeptember,")
                    .append("startingPortfolioBalance,")
                    .append("withdrawalAmtBeforeGuardrail,")
                    .append("withdrawalPctBeforeGuardrail,")
                    .append("withdrawalAmtAfterGuardrail,")
                    .append("withdrawalPctAfterGuardrail,")
                    .append("portfolioGrowth,")
                    .append("endingPortfolioBeforeInflation,")
                    .append("upper_withdrawalRateGuardrailUsed_bad,")
                    .append("inflationRateAvg,")
                    .append("investReturnAvg,")
                    .append("withdrawalEqualsThisAmtIn2026,")
                    .append("inflationFactor,")
                    .append("endPortfolioIncludesInflation,")
                    .append("portfolioDroppedBelowWarning,")
                    .append("endPortfolioIn2025Dollars")
                    .toString();
            return sb.toString();
        }

    public String toStringData() {
        String sb2 = new org.apache.commons.lang3.builder.ToStringBuilder(this, ToStringStyle.SIMPLE_STYLE)
                .append("yearNumber", yearNumber)
                .append("year", year)
                .append("bobAgeInSeptember", bobAgeInSeptember)
                .append("startingPortfolioBalance", startingPortfolioBalance)
                .append("withdrawalAmtBeforeGuardrail", withdrawalAmtBeforeGuardrail)
                .append("withdrawalPctBeforeGuardrail", withdrawalPctBeforeGuardrail)
                .append("withdrawalAmtAfterGuardrail", withdrawalAmtAfterGuardrail)
                .append("withdrawalPctAfterGuardrail", withdrawalPctAfterGuardrail)
                .append("portfolioGrowth", portfolioGrowth)
                .append("endingPortfolioBeforeInflation", endPortfolioBalBeforeInflation)
                .append("upper_withdrawalRateGuardrailUsed_bad", upper_withdrawalRateGuardrailUsed_bad)
                .append("inflationRateAvg", inflationRateAvg)
                .append("investReturnAvg", investReturnAvg)
                .append("withdrawalEqualsThisAmtIn2026", withdrawalEqualsThisAmtIn2026)
                .append("inflationFactor", inflationFactor)
                .append("endPortfolioIncludesInflation", endPortfolioBalInclInflation)
                .append("portfolioDroppedBelowWarning", portfolioDroppedBelowWarning)
                .append("endPortfolioIn2025Dollars", endPortfolioBalIn2025Dollars)
                .toString();

        return sb2;
    }
}
