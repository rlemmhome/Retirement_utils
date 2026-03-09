package com.hiflite.montecarlo_excel;

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
    private double portfolioGrowth = 0.0;
    private double endingPortfolioBeforeInflation      = 1_500_000.0;
    private long upper_withdrawalRateGuardrailUsed_bad = 0;

    private double inflationRateAvg                    = 0.0379;
    private double investReturnAvg                     = 0.0670;
    private double withdrawalEqualsThisAmtIn2026       = 75_000.0;

    private double inflationFactor                     = 1.00;
    private double endPortfolioIncludesInflation       = 1_500_000.0;
    private long portfolioDroppedBelowWarning          = 0;
    private double endPortfolioIn2025Dollars           = 1_500_000.0;

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

    public double getEndingPortfolioBeforeInflation() {
        return endingPortfolioBeforeInflation;
    }

    public void setEndingPortfolioBeforeInflation(double endingPortfolioBeforeInflation) {
        this.endingPortfolioBeforeInflation = endingPortfolioBeforeInflation;
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

    public double getEndPortfolioIncludesInflation() {
        return endPortfolioIncludesInflation;
    }

    public void setEndPortfolioIncludesInflation(double endPortfolioIncludesInflation) {
        this.endPortfolioIncludesInflation = endPortfolioIncludesInflation;
    }

    public long getPortfolioDroppedBelowWarning() {
        return portfolioDroppedBelowWarning;
    }

    public void setPortfolioDroppedBelowWarning(long portfolioDroppedBelowWarning) {
        this.portfolioDroppedBelowWarning = portfolioDroppedBelowWarning;
    }

    public double getEndPortfolioIn2025Dollars() {
        return endPortfolioIn2025Dollars;
    }

    public void setEndPortfolioIn2025Dollars(double endPortfolioIn2025Dollars) {
        this.endPortfolioIn2025Dollars = endPortfolioIn2025Dollars;
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
                .append("endingPortfolioBeforeInflation", endingPortfolioBeforeInflation)
                .append("upper_withdrawalRateGuardrailUsed_bad", upper_withdrawalRateGuardrailUsed_bad)
                .append("inflationRateAvg", inflationRateAvg)
                .append("investReturnAvg", investReturnAvg)
                .append("withdrawalEqualsThisAmtIn2026", withdrawalEqualsThisAmtIn2026)
                .append("inflationFactor", inflationFactor)
                .append("endPortfolioIncludesInflation", endPortfolioIncludesInflation)
                .append("portfolioDroppedBelowWarning", portfolioDroppedBelowWarning)
                .append("endPortfolioIn2025Dollars", endPortfolioIn2025Dollars)
                .toString();
    }
}
