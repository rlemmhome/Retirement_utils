package com.hiflite.montecarlo;

public class Inputs_And_Results {

    public static final double STARTING_WITHDRAWAL_PCT = 0.05;

    public static final double GUARDRAIL_MOVEMENT_PCT  = 0.20;
    public static final double WITHDRAWAL_ADJUST_PCT   = 0.10;

    public static final double LOWER_GUARDRAIL_PCT     = STARTING_WITHDRAWAL_PCT * (1.0 - GUARDRAIL_MOVEMENT_PCT);
    public static final double UPPER_GUARDRAIL_PCT     = STARTING_WITHDRAWAL_PCT * (1.0 + GUARDRAIL_MOVEMENT_PCT);

    public static final double INFLATION_AVG           = 0.067;

    public long getRun_counter() {
        return run_counter;
    }

    public void setRun_counter(long run_counter) {
        this.run_counter = run_counter;
    }

    public long getNo_decrease_in_withdrawal_needed() {
        return no_decrease_in_withdrawal_needed;
    }

    public void setNo_decrease_in_withdrawal_needed(long no_decrease_in_withdrawal_needed) {
        this.no_decrease_in_withdrawal_needed = no_decrease_in_withdrawal_needed;
    }

    public long getFailure_counter() {
        return failure_counter;
    }

    public void setFailure_counter(long failure_counter) {
        this.failure_counter = failure_counter;
    }

    public long getSuccess_counter() {
        return success_counter;
    }

    public void setSuccess_counter(long success_counter) {
        this.success_counter = success_counter;
    }

    public long getPortfolio_warning() {
        return portfolio_warning;
    }

    public void setPortfolio_warning(long portfolio_warning) {
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

    public static final double INFLATION_STDDEV        = 0.1089;

    public static final double portfolio_warning_at    = 250_000;

    private long run_counter                      = 0;
    private long no_decrease_in_withdrawal_needed = 0;
    private long failure_counter                  = 0;
    private long success_counter                  = 0;
    private long portfolio_warning                = 0;
    private boolean first_run = true;

    private double min_ending_portfolio_in_2026_dollars = 999_999_999;

    private static final double avg_retirement_asset_returns_1965_2024      = 0.0944;
    private static final double forecast_retirement_asset_returns_2025_2040 = 0.0944;


}
