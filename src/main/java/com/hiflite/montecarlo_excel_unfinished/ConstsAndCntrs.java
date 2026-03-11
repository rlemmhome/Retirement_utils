package com.hiflite.montecarlo_excel_unfinished;

public class ConstsAndCntrs {

    private static final double avg_retirement_asset_returns_1965_2024      = 0.0944;
    private static final double forecast_retirement_asset_returns_2025_2040 = 0.067;
    public static final long YEAR_OF_START = 2026;
    public static final long YEAR_BEGIN_WD = 2027;


    public static final long NUM_YEARS = 30;
    public static final long BOB_AGE_IN_SEPTEMBER = 65;

    public static final double STARTING_PORTFOLIO_AMT = 1500000.0;

    public static final double STARTING_WITHDRAWAL_PCT = 0.05;

    public static final double GUARDRAIL_MOVEMENT_PCT  = 0.20;
    public static final double WITHDRAWAL_ADJUST_PCT   = 0.10;

    public static final double LOWER_GUARDRAIL_PCT     = STARTING_WITHDRAWAL_PCT * (1.0 - GUARDRAIL_MOVEMENT_PCT);
    public static final double UPPER_GUARDRAIL_PCT     = STARTING_WITHDRAWAL_PCT * (1.0 + GUARDRAIL_MOVEMENT_PCT);

    public static final double INFLATION_AVG           = 0.0379;
    public static final double INFLATION_STDDEV        = 0.0273;
    public static final double INVEST_RETURNS_AVG           = forecast_retirement_asset_returns_2025_2040;
    public static final double INVEST_RETURNS_STDDEV        = 0.1089;

    public static final double portfolio_warning_at    = 1.0;

    private long run_counter                           = 0;
    private long no_decrease_in_withdrawal_needed      = 0;
    private long failure_counter                       = 0;
    private long success_counter                       = 0;
    private long portfolio_warning                     = 0;
    private boolean first_run                          = true;

    private double min_ending_portfolio_in_2026_dollars = 999_999_999;


    public long getRun_counter() {
        return run_counter;
    }

    public void increment_run_counter() {
        first_run = false;
        run_counter++;
    }

    public long getNo_decrease_in_withdrawal_needed() {
        return no_decrease_in_withdrawal_needed;
    }

    public void increment_No_decrease_in_withdrawal_needed(long no_decrease_in_withdrawal_needed) {
        this.no_decrease_in_withdrawal_needed = no_decrease_in_withdrawal_needed;
    }

    public long getFailure_counter() {
        return failure_counter;
    }

    public void incrementFailureCounter() {
        this.failure_counter++;
    }

    public long getSuccess_counter() {
        return success_counter;
    }

    public void incrementSuccessCounter(long success_counter) {
        this.success_counter = success_counter;
    }

    public long getPortfolio_warningDollars() {
        return portfolio_warning;
    }

    public void setPortfolio_warningDollars(long portfolio_warning) {
        this.portfolio_warning = portfolio_warning;
    }

    public boolean isFirst_run() {
        return first_run;
    }

    public void setFirst_run(boolean first_run) {
        this.first_run = first_run;
    }

    public double getMin_ending_portfolio_in_2026_dollars() {
        return min_ending_portfolio_in_2026_dollars;
    }

    public void setMin_ending_portfolio_in_2026_dollars(double min_ending_portfolio_in_2026_dollars) {
        this.min_ending_portfolio_in_2026_dollars = min_ending_portfolio_in_2026_dollars;
    }

}
