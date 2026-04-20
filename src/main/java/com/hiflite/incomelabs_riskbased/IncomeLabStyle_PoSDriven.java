package com.hiflite.incomelabs_riskbased;

import javax.swing.*;
import javax.swing.event.ChangeListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableColumn;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * IncomeLabStyle_PoSDriven.java
 *
 * Income Lab-Style Probability-of-Success-Driven Withdrawal Simulator.
 *
 * Compile:  javac IncomeLabStyle_PoSDriven.java
 * Run:      java IncomeLabStyle_PoSDriven
 * Requires Java 11+. No external dependencies.
 */
public class IncomeLabStyle_PoSDriven extends JFrame {

    private static final int    MC_SOLVE_PATHS = 800;
    private static final int    MC_FAN_PATHS   = 400;
    private static final int    BINARY_ITERS   = 22;
    private static final int    BASE_YEAR      = 2026;

    // ── Column indices in table model ────────────────────────────────────────
    // 0=ManAge 1=CalYr 2=PortBal 3=Withdrawal 4=WdPct 5=Guardrail(hidden)
    // 6=ManSS 7=WomanSS 8=Annuity 9=Guaranteed
    // 10=Living 11=Medical 12=Tax 13=TotalSpend 14=TotalIncome 15=SurplusGap
    // 16=InflFactor 17=ReturnUsed 18=InflUsed
    private static final int COL_GUARDRAIL  = 5;
    private static final int COL_SURPLUS    = 15;

    // ── Input spinners ───────────────────────────────────────────────────────
    private JSpinner spPortfolio, spHorizon, spTargetPoS;
    private JSpinner spWithdrawStartYear, spWithdrawStartMonth;
    private JSpinner spManBirthYear, spManBirthMonth;
    private JSpinner spWomanBirthYear, spWomanBirthMonth;
    private JSpinner spManSSAmount, spManSSStartYear, spManSSStartMonth;
    private JSpinner spWomanSSAmount, spWomanSSStartYear, spWomanSSStartMonth;
    private JSpinner spSSCola;
    private JSpinner spAnnuity, spAnnuityStartYear;
    private JSpinner spNomReturn, spStdDev, spInflation, spInflationStdDev;
    private JSpinner spLivingExp, spMedical, spMedInflation;
    private JSpinner spBaseTax, spTaxInflation;
    private JSpinner spUpperGuardrail, spLowerGuardrail;

    // ── Output ───────────────────────────────────────────────────────────────
    private JLabel            lblYear1Answer, lblYear1Sub, lblYear1Detail;
    private JLabel            lblManAge, lblWomanAge;
    private JTable            tblResults;
    private DefaultTableModel tblModel;
    private JLabel            lblActualPoS, lblMedianFinal, lblYr10Wd, lblInitRate;
    private JToggleButton     tglDollars;
    private ChartPanel        chartPanel;
    private JComboBox<String> cmbChartType;
    private JTextArea         txaSummary;
    private JButton           btnRun;
    private JProgressBar      progressBar;

    private SimResults lastResults     = null;
    private boolean    showRealDollars = false;

    private static final NumberFormat CURRENCY = NumberFormat.getCurrencyInstance(Locale.US);
    static { CURRENCY.setMaximumFractionDigits(0); }

    // ════════════════════════════════════════════════════════════════════════
    //  Constructor
    // ════════════════════════════════════════════════════════════════════════
    public IncomeLabStyle_PoSDriven() {
        super("Income Lab-Style Probability-of-Success-Driven Withdrawal Simulator");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout(0, 0));
        getContentPane().setBackground(new Color(245, 245, 242));

        ToolTipManager.sharedInstance().setDismissDelay(10_000);
        ToolTipManager.sharedInstance().setInitialDelay(400);

        add(buildInputPanel(),  BorderLayout.WEST);
        add(buildOutputPanel(), BorderLayout.CENTER);
        add(buildStatusBar(),   BorderLayout.SOUTH);

        pack();
        setMinimumSize(new Dimension(1300, 760));
        setExtendedState(JFrame.MAXIMIZED_BOTH);
        setLocationRelativeTo(null);
        setVisible(true);

        SwingUtilities.invokeLater(this::runSimulation);
    }

    // ════════════════════════════════════════════════════════════════════════
    //  INPUT PANEL
    // ════════════════════════════════════════════════════════════════════════
    private JPanel buildInputPanel() {
        JPanel outer = new JPanel(new BorderLayout());
        outer.setBackground(new Color(240, 240, 237));
        outer.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, new Color(200, 198, 193)));
        outer.setPreferredSize(new Dimension(420, 0));
        outer.setMinimumSize(new Dimension(380, 0));

        JPanel inner = new JPanel();
        inner.setLayout(new BoxLayout(inner, BoxLayout.Y_AXIS));
        inner.setBackground(new Color(240, 240, 237));
        inner.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        // Portfolio & Simulation
        spPortfolio          = spinI(1_500_000, 10_000,    20_000_000, 10_000, "#,###");
        spHorizon            = spinI(30,         10,        50,          1,     "#");
        spTargetPoS          = spinI(80,         60,        99,          1,     "#");
        spWithdrawStartYear  = spinI(2027,       2025,      2040,        1,     "#");
        spWithdrawStartMonth = spinI(1,          1,         12,          1,     "#");
        inner.add(card("Portfolio & Simulation", new Object[]{
                "Starting portfolio ($)",        spPortfolio,
                "Retirement horizon (years)",    spHorizon,
                "Target success rate (%)",       spTargetPoS,
                "Withdrawal start year",         spWithdrawStartYear,
                "Withdrawal start month (1-12)", spWithdrawStartMonth,
        }));

        // People
        spManBirthYear    = spinI(1961, 1920, 2000, 1, "#");
        spManBirthMonth   = spinI(9,    1,    12,   1, "#");
        spWomanBirthYear  = spinI(1962, 1920, 2000, 1, "#");
        spWomanBirthMonth = spinI(12,   1,    12,   1, "#");

        JPanel ageDisplay = new JPanel(new GridLayout(1, 2, 8, 0));
        ageDisplay.setOpaque(false);
        ageDisplay.setAlignmentX(LEFT_ALIGNMENT);
        ageDisplay.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
        lblManAge   = new JLabel("Man age: —");
        lblWomanAge = new JLabel("Woman age: —");
        for (JLabel l : new JLabel[]{lblManAge, lblWomanAge}) {
            l.setFont(new Font("SansSerif", Font.BOLD, 11));
            l.setForeground(new Color(24, 95, 165));
        }
        ageDisplay.add(lblManAge); ageDisplay.add(lblWomanAge);

        inner.add(card("People — Birth Dates", new Object[]{
                "Man — birth year",           spManBirthYear,
                "Man — birth month (1-12)",   spManBirthMonth,
                "Woman — birth year",         spWomanBirthYear,
                "Woman — birth month (1-12)", spWomanBirthMonth,
                null, ageDisplay,
        }));

        ChangeListener ageWatcher = e -> updateAgeLabels();
        for (JSpinner s : new JSpinner[]{
                spManBirthYear, spManBirthMonth, spWomanBirthYear, spWomanBirthMonth})
            s.addChangeListener(ageWatcher);
        updateAgeLabels();

        // Social Security
        spManSSAmount       = spinI(40_400,  0,    200_000, 100,  "#,###");
        spManSSStartYear    = spinI(2027,    2024, 2045,    1,    "#");
        spManSSStartMonth   = spinI(1,       1,    12,      1,    "#");
        spWomanSSAmount     = spinI(40_520,  0,    200_000, 100,  "#,###");
        spWomanSSStartYear  = spinI(2027,    2024, 2045,    1,    "#");
        spWomanSSStartMonth = spinI(12,      1,    12,      1,    "#");
        spSSCola            = spinD(2.3,     0.0,  6.0,     0.1,  "0.0#");
        inner.add(card("Social Security", new Object[]{
                "Man SS amount ($/yr)",        spManSSAmount,
                "Man SS start year",           spManSSStartYear,
                "Man SS start month (1-12)",   spManSSStartMonth,
                "Woman SS amount ($/yr)",      spWomanSSAmount,
                "Woman SS start year",         spWomanSSStartYear,
                "Woman SS start month (1-12)", spWomanSSStartMonth,
                "SS COLA rate (%/yr)",         spSSCola,
        }));

        // Annuity
        spAnnuity          = spinI(22_599, 0,    500_000, 500, "#,###");
        spAnnuityStartYear = spinI(2028,   2024, 2050,    1,   "#");
        inner.add(card("Annuity (Non-COLA)", new Object[]{
                "Annuity amount ($/yr)", spAnnuity,
                "Annuity start year",    spAnnuityStartYear,
        }));

        // Market
        spNomReturn       = spinD(6.70,  1.0, 15.0, 0.1,  "0.00");
        spStdDev          = spinD(10.79, 2.0, 30.0, 0.1,  "0.00");
        spInflation       = spinD(3.79,  0.5, 10.0, 0.01, "0.00");
        spInflationStdDev = spinD(2.73,  0.0, 8.0,  0.01, "0.00");
        inner.add(card("Market Assumptions (1961-2024 historical defaults)", new Object[]{
                "Expected nominal return (%)",  spNomReturn,
                "Return std deviation (%)",     spStdDev,
                "Mean inflation (%/yr)",        spInflation,
                "Inflation std deviation (%)",  spInflationStdDev,
        }));

        // Spending
        spLivingExp    = spinI(105_000, 10_000, 1_000_000, 1_000, "#,###");
        spMedical      = spinI(12_000,  0,      200_000,   500,   "#,###");
        spMedInflation = spinD(4.5,     1.0,    10.0,      0.1,   "0.0#");
        spBaseTax      = spinI(15_000,  0,      100_000,   500,   "#,###");
        spTaxInflation = spinD(3.79,    0.5,    10.0,      0.1,   "0.0#");
        inner.add(card("Annual Spending (2027 base $)", new Object[]{
                "Living expenses ($/yr)",   spLivingExp,
                "Medical ($/yr)",           spMedical,
                "Medical inflation (%/yr)", spMedInflation,
                "Base tax — yr 1 ($/yr)",   spBaseTax,
                "Tax inflation (%/yr)",     spTaxInflation,
        }));

        // Guardrails
        spUpperGuardrail = spinD(20.0, 5.0, 60.0, 1.0, "0.0#");
        spLowerGuardrail = spinD(25.0, 5.0, 60.0, 1.0, "0.0#");
        inner.add(card("Guardrail Alerts (display only)", new Object[]{
                "Upper guardrail — raise alert (%)", spUpperGuardrail,
                "Lower guardrail — cut alert (%)",   spLowerGuardrail,
        }));

        // Run button
        btnRun = new JButton("▶  Run Simulation");
        btnRun.setFont(new Font("SansSerif", Font.BOLD, 13));
        btnRun.setBackground(new Color(55, 138, 221));
        btnRun.setForeground(Color.WHITE);
        btnRun.setFocusPainted(false);
        btnRun.setBorder(BorderFactory.createEmptyBorder(10, 28, 10, 28));
        btnRun.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btnRun.setOpaque(true);
        btnRun.addActionListener(e -> runSimulation());
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 8));
        btnPanel.setBackground(new Color(240, 240, 237));
        btnPanel.add(btnRun);
        inner.add(btnPanel);

        JScrollPane scroll = new JScrollPane(inner,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(new Color(240, 240, 237));
        outer.add(scroll, BorderLayout.CENTER);
        return outer;
    }

    private void updateAgeLabels() {
        try {
            lblManAge.setText("Man age: " + computeAge(
                    iv(spManBirthYear), iv(spManBirthMonth)));
            lblWomanAge.setText("Woman age: " + computeAge(
                    iv(spWomanBirthYear), iv(spWomanBirthMonth)));
        } catch (Exception ignored) {}
    }

    private int computeAge(int birthYear, int birthMonth) {
        LocalDate today = LocalDate.now();
        LocalDate birth = LocalDate.of(
                Math.max(1900, Math.min(2100, birthYear)),
                Math.max(1,    Math.min(12,   birthMonth)), 1);
        return (int) java.time.temporal.ChronoUnit.YEARS.between(birth, today);
    }

    // ════════════════════════════════════════════════════════════════════════
    //  OUTPUT PANEL
    // ════════════════════════════════════════════════════════════════════════
    private JPanel buildOutputPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBackground(new Color(245, 245, 242));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        // Answer box
        JPanel answerBox = new JPanel(new BorderLayout(4, 4));
        answerBox.setBackground(new Color(232, 240, 250));
        answerBox.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(181, 212, 244), 1),
                BorderFactory.createEmptyBorder(10, 16, 10, 16)));

        lblYear1Answer = new JLabel("—");
        lblYear1Answer.setFont(new Font("SansSerif", Font.BOLD, 30));
        lblYear1Answer.setForeground(new Color(24, 95, 165));
        lblYear1Sub    = new JLabel(" ");
        lblYear1Sub.setFont(new Font("SansSerif", Font.PLAIN, 13));
        lblYear1Sub.setForeground(new Color(90, 90, 90));
        lblYear1Detail = new JLabel(" ");
        lblYear1Detail.setFont(new Font("SansSerif", Font.PLAIN, 12));
        lblYear1Detail.setForeground(new Color(110, 110, 110));

        tglDollars = new JToggleButton("Showing: Future $ (nominal)");
        tglDollars.setFont(new Font("SansSerif", Font.PLAIN, 11));
        tglDollars.setFocusPainted(false);
        tglDollars.addActionListener(e -> {
            showRealDollars = tglDollars.isSelected();
            tglDollars.setText(showRealDollars
                    ? "Showing: Today's $ (2026 real)"
                    : "Showing: Future $ (nominal)");
            if (lastResults != null) updateUI(lastResults);
        });

        JPanel aNorth = new JPanel(new BorderLayout()); aNorth.setOpaque(false);
        JLabel aTitle = new JLabel("Year 1 portfolio withdrawal at target PoS");
        aTitle.setFont(new Font("SansSerif", Font.PLAIN, 11));
        aTitle.setForeground(new Color(100, 100, 100));
        aNorth.add(aTitle, BorderLayout.WEST);
        aNorth.add(tglDollars, BorderLayout.EAST);

        JPanel aMid = new JPanel(new BorderLayout(2, 2)); aMid.setOpaque(false);
        aMid.add(lblYear1Answer, BorderLayout.CENTER);
        aMid.add(lblYear1Sub,    BorderLayout.SOUTH);

        answerBox.add(aNorth,         BorderLayout.NORTH);
        answerBox.add(aMid,           BorderLayout.CENTER);
        answerBox.add(lblYear1Detail, BorderLayout.SOUTH);

        // Metrics row
        JPanel metricsRow = new JPanel(new GridLayout(1, 4, 8, 0));
        metricsRow.setBackground(new Color(245, 245, 242));
        lblActualPoS   = mkMetricLabel();
        lblMedianFinal = mkMetricLabel();
        lblYr10Wd      = mkMetricLabel();
        lblInitRate    = mkMetricLabel();
        metricsRow.add(wrapMetric(lblActualPoS,   "Actual PoS",               "from fan simulation"));
        metricsRow.add(wrapMetric(lblMedianFinal, "Median final balance",      "end of horizon"));
        metricsRow.add(wrapMetric(lblYr10Wd,      "Yr 10 withdrawal (median)", "see dollar toggle"));
        metricsRow.add(wrapMetric(lblInitRate,    "Initial withdrawal rate",   "% of portfolio"));

        JPanel topSection = new JPanel(new BorderLayout(0, 8));
        topSection.setBackground(new Color(245, 245, 242));
        topSection.add(answerBox,  BorderLayout.NORTH);
        topSection.add(metricsRow, BorderLayout.CENTER);

        // Tabbed output
        JTabbedPane tabs = new JTabbedPane();
        tabs.setFont(new Font("SansSerif", Font.PLAIN, 12));
        tabs.addTab("Year-by-year table", buildTablePanel());
        tabs.addTab("Simulation chart",   buildChartPanel());
        tabs.addTab("Summary",            buildSummaryPanel());

        panel.add(topSection, BorderLayout.NORTH);
        panel.add(tabs,       BorderLayout.CENTER);
        return panel;
    }

    // ── Table ────────────────────────────────────────────────────────────────
    private JScrollPane buildTablePanel() {
        String[] cols = {
                "Man age", "Cal yr", "Portfolio bal",
                "80% PoS withdrawal", "Wd %", "Guardrail",   // col 5 = hidden
                "Man SS", "Woman SS", "Annuity", "Guaranteed",
                "Living", "Medical", "Tax (est)",
                "Total spend", "Total income", "Surplus/gap",
                "Infl factor", "Return used", "Infl used"
        };
        tblModel = new DefaultTableModel(cols, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };

        tblResults = new JTable(tblModel) {
            @Override
            public String getToolTipText(MouseEvent e) {
                int col = columnAtPoint(e.getPoint());
                int row = rowAtPoint(e.getPoint());
                // Tooltip on guardrail column even though hidden (col 5 in model)
                // More usefully, put guardrail tooltip on the Wd% column (col 4)
                // since guardrail is hidden
                if (col == 4 && row >= 0) {
                    // Retrieve guardrail value from model col 5
                    Object gv = tblModel.getValueAt(row, COL_GUARDRAIL);
                    String g  = gv == null ? "" : gv.toString();
                    if (g.contains("▲"))
                        return "<html><b>▲ Raise alert</b><br>"
                                + "The re-solved withdrawal has risen more than the upper<br>"
                                + "guardrail threshold above the Year-1 amount.<br>"
                                + "Your portfolio has grown — you can sustainably spend more.<br>"
                                + "No action required; the 80% PoS re-solve already reflects this.</html>";
                    if (g.contains("▼"))
                        return "<html><b>▼ Cut alert</b><br>"
                                + "The re-solved withdrawal has fallen more than the lower<br>"
                                + "guardrail threshold below the Year-1 amount.<br>"
                                + "Consider reducing discretionary spending this year.</html>";
                    return "<html><b>Within guardrail range</b><br>"
                            + "Withdrawal is within the guardrail bands. No adjustment indicated.</html>";
                }
                return super.getToolTipText(e);
            }
        };
        tblResults.setFont(new Font("SansSerif", Font.PLAIN, 11));
        tblResults.setRowHeight(20);
        tblResults.getTableHeader().setFont(new Font("SansSerif", Font.BOLD, 10));
        tblResults.setGridColor(new Color(220, 220, 215));
        tblResults.setShowGrid(true);
        tblResults.setSelectionBackground(new Color(210, 230, 250));
        tblResults.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);

        // Column widths — guardrail col (5) set to 0 (hidden)
        int[] w = {55, 55, 105, 115, 68,  0,    // 0-5
                75, 80,  72,  90,  72,  72,  78,  // 6-12
                88, 95,  85,  72,  75,  68};       // 13-18
        for (int i = 0; i < w.length && i < tblResults.getColumnCount(); i++) {
            TableColumn tc = tblResults.getColumnModel().getColumn(i);
            tc.setPreferredWidth(w[i]);
            if (w[i] == 0) { tc.setMinWidth(0); tc.setMaxWidth(0); }
        }

        // Header tooltip for Wd% column explaining guardrail indicator
        JTableHeader header = tblResults.getTableHeader();
        header.addMouseMotionListener(new MouseMotionAdapter() {
            @Override public void mouseMoved(MouseEvent e) {
                int col = header.columnAtPoint(e.getPoint());
                if (col == 4)
                    header.setToolTipText(
                            "<html><b>Wd % — effective portfolio withdrawal rate</b><br>"
                                    + "= withdrawal ÷ portfolio balance for that year.<br>"
                                    + "Hover over individual cells for guardrail status:<br>"
                                    + "▲ raise alert = withdrawal risen above upper guardrail threshold<br>"
                                    + "▼ cut alert = withdrawal fallen below lower guardrail threshold</html>");
                else
                    header.setToolTipText(null);
            }
        });

        // Cell renderer
        tblResults.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(
                    JTable t, Object v, boolean sel, boolean foc, int row, int col) {
                Component c = super.getTableCellRendererComponent(t, v, sel, foc, row, col);
                if (!sel) {
                    c.setBackground(row % 2 == 0 ? Color.WHITE : new Color(248, 248, 245));
                    String s = v == null ? "" : v.toString();
                    // Wd% column: show guardrail indicator color
                    if (col == 4) {
                        Object gv = tblModel.getValueAt(row, COL_GUARDRAIL);
                        String g  = gv == null ? "" : gv.toString();
                        c.setForeground(g.contains("▲") ? new Color(59,109,17)
                                : g.contains("▼") ? new Color(163,45,45)
                                  : Color.BLACK);
                    } else if (col == COL_SURPLUS) {
                        // Red for negative (gap), green for positive (surplus)
                        c.setForeground(s.startsWith("-") ? new Color(180, 30, 30)
                                : new Color(59, 109, 17));
                    } else {
                        c.setForeground(Color.BLACK);
                    }
                }
                ((JLabel) c).setHorizontalAlignment(col <= 1 ? LEFT : RIGHT);
                return c;
            }
        });

        JScrollPane scroll = new JScrollPane(tblResults);
        scroll.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        return scroll;
    }

    // ── Chart ─────────────────────────────────────────────────────────────────
    private JPanel buildChartPanel() {
        JPanel wrapper = new JPanel(new BorderLayout(0, 4));
        wrapper.setBackground(new Color(245, 245, 242));
        wrapper.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        JPanel ctrl = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        ctrl.setBackground(new Color(245, 245, 242));
        ctrl.add(new JLabel("Chart type:"));
        cmbChartType = new JComboBox<>(new String[]{
                "Withdrawal $ — fan + percentiles",
                "Portfolio balance — fan + percentiles",
                "Final balance histogram",
                "Income vs. spending (median)"
        });
        cmbChartType.setFont(new Font("SansSerif", Font.PLAIN, 12));
        cmbChartType.addActionListener(e -> { if (lastResults != null) refreshChart(); });
        ctrl.add(cmbChartType);

        chartPanel = new ChartPanel();
        wrapper.add(ctrl,       BorderLayout.NORTH);
        wrapper.add(chartPanel, BorderLayout.CENTER);
        return wrapper;
    }

    // ── Summary ───────────────────────────────────────────────────────────────
    private JScrollPane buildSummaryPanel() {
        txaSummary = new JTextArea();
        txaSummary.setFont(new Font("Monospaced", Font.PLAIN, 12));
        txaSummary.setLineWrap(true);
        txaSummary.setWrapStyleWord(true);
        txaSummary.setEditable(false);
        txaSummary.setBackground(new Color(250, 250, 248));
        txaSummary.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
        JScrollPane sp = new JScrollPane(txaSummary);
        sp.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        return sp;
    }

    private JPanel buildStatusBar() {
        progressBar = new JProgressBar();
        progressBar.setStringPainted(true);
        progressBar.setString("Ready");
        progressBar.setPreferredSize(new Dimension(0, 22));
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(BorderFactory.createEmptyBorder(0, 8, 4, 8));
        p.setBackground(new Color(245, 245, 242));
        p.add(progressBar);
        return p;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  SIMULATION
    // ════════════════════════════════════════════════════════════════════════
    private void runSimulation() {
        btnRun.setEnabled(false);
        progressBar.setIndeterminate(true);
        progressBar.setString("Running Monte Carlo…");
        SwingWorker<SimResults, Void> worker = new SwingWorker<>() {
            @Override protected SimResults doInBackground() { return simulate(readInputs()); }
            @Override protected void done() {
                try {
                    lastResults = get();
                    updateUI(lastResults);
                    progressBar.setIndeterminate(false);
                    progressBar.setString("Complete — " + MC_FAN_PATHS
                            + " fan paths × " + lastResults.inp.horizon + " years");
                } catch (Exception ex) {
                    progressBar.setString("Error: " + ex.getMessage());
                    ex.printStackTrace();
                }
                btnRun.setEnabled(true);
            }
        };
        worker.execute();
    }

    private SimInputs readInputs() {
        SimInputs i = new SimInputs();
        i.portfolio          = iv(spPortfolio);
        i.horizon            = iv(spHorizon);
        i.targetPoS          = iv(spTargetPoS) / 100.0;
        i.withdrawStartYear  = iv(spWithdrawStartYear);
        i.withdrawStartMonth = iv(spWithdrawStartMonth);
        i.manBirthYear       = iv(spManBirthYear);
        i.manBirthMonth      = iv(spManBirthMonth);
        i.womanBirthYear     = iv(spWomanBirthYear);
        i.womanBirthMonth    = iv(spWomanBirthMonth);
        i.manSSAmount        = iv(spManSSAmount);
        i.manSSStartYear     = iv(spManSSStartYear);
        i.manSSStartMonth    = iv(spManSSStartMonth);
        i.womanSSAmount      = iv(spWomanSSAmount);
        i.womanSSStartYear   = iv(spWomanSSStartYear);
        i.womanSSStartMonth  = iv(spWomanSSStartMonth);
        i.ssCola             = dv(spSSCola)         / 100.0;
        i.annuity            = iv(spAnnuity);
        i.annuityStartYear   = iv(spAnnuityStartYear);
        i.nomReturn          = dv(spNomReturn)      / 100.0;
        i.stdDev             = dv(spStdDev)         / 100.0;
        i.inflation          = dv(spInflation)      / 100.0;
        i.inflationStdDev    = dv(spInflationStdDev) / 100.0;
        i.livingExp          = iv(spLivingExp);
        i.medical            = iv(spMedical);
        i.medInflation       = dv(spMedInflation)   / 100.0;
        i.baseTax            = iv(spBaseTax);
        i.taxInflation       = dv(spTaxInflation)   / 100.0;
        i.upperGuardrail     = dv(spUpperGuardrail) / 100.0;
        i.lowerGuardrail     = dv(spLowerGuardrail) / 100.0;
        i.manAge             = computeAge(i.manBirthYear,   i.manBirthMonth);
        i.womanAge           = computeAge(i.womanBirthYear, i.womanBirthMonth);
        i.currentAge         = i.manAge;
        return i;
    }

    private int    iv(JSpinner s) { return ((Number) s.getValue()).intValue(); }
    private double dv(JSpinner s) { return ((Number) s.getValue()).doubleValue(); }

    private double manSSThisYear(SimInputs inp, int y) {
        int calYear = BASE_YEAR + y;
        if (calYear < inp.manSSStartYear) return 0;
        if (calYear == inp.manSSStartYear)
            return inp.manSSAmount * (13.0 - inp.manSSStartMonth) / 12.0;
        return inp.manSSAmount * Math.pow(1 + inp.ssCola, calYear - inp.manSSStartYear);
    }

    private double womanSSThisYear(SimInputs inp, int y) {
        int calYear = BASE_YEAR + y;
        if (calYear < inp.womanSSStartYear) return 0;
        if (calYear == inp.womanSSStartYear)
            return inp.womanSSAmount * (13.0 - inp.womanSSStartMonth) / 12.0;
        return inp.womanSSAmount * Math.pow(1 + inp.ssCola, calYear - inp.womanSSStartYear);
    }

    private double annuityThisYear(SimInputs inp, int y) {
        return (BASE_YEAR + y) >= inp.annuityStartYear ? inp.annuity : 0;
    }

    private double taxThisYear(SimInputs inp, int y) {
        int calYear = BASE_YEAR + y;
        if (calYear < inp.withdrawStartYear) return 0;
        int yrsFromWithdraw = calYear - inp.withdrawStartYear;
        return inp.baseTax * Math.pow(1 + inp.taxInflation, yrsFromWithdraw);
    }

    private SimResults simulate(SimInputs inp) {
        SimResults res = new SimResults();
        res.inp = inp;
        res.medianRows = new ArrayList<>();

        int yr1Wd = solveWithdrawal(inp.portfolio, inp.horizon, inp, 999);
        res.yr1Withdrawal = yr1Wd;
        double bal = inp.portfolio;

        for (int y = 0; y < inp.horizon; y++) {
            int calYear  = BASE_YEAR + y;
            int manAge   = inp.manAge + y;
            int remaining = inp.horizon - y;
            boolean drawing = calYear >= inp.withdrawStartYear;

            int wd = drawing && bal > 0
                    ? solveWithdrawal((int) Math.max(0, bal), remaining, inp, 999 + y * 37) : 0;

            double vsYr1      = (yr1Wd > 0 && drawing) ? (wd - yr1Wd) / (double) yr1Wd : 0.0;
            double wdPct      = (drawing && bal > 0)   ? wd / (double) bal * 100.0 : 0.0;
            double inflFactor = Math.pow(1 + inp.inflation, y);

            double manSS    = manSSThisYear(inp, y);
            double womanSS  = womanSSThisYear(inp, y);
            double ann      = annuityThisYear(inp, y);
            double guaranteed = manSS + womanSS + ann;
            double totalIncome = guaranteed + wd;
            double tax      = taxThisYear(inp, y);
            double living   = drawing ? inp.livingExp * Math.pow(1 + inp.inflation, y) : 0;
            double medical  = drawing ? inp.medical   * Math.pow(1 + inp.medInflation, y) : 0;
            double totalSpend = drawing ? living + medical + tax : 0;
            double surplus  = totalIncome - totalSpend;

            // For median path, record the mean return and mean inflation (expected values)
            // These are the deterministic values used in the median scenario
            double returnUsed = inp.nomReturn * 100.0;
            double inflUsed   = inp.inflation * 100.0;

            String alert = "—";
            if (drawing) {
                if      (vsYr1 >= inp.upperGuardrail)      alert = "▲ raise alert";
                else if (vsYr1 <= -inp.lowerGuardrail)     alert = "▼ cut alert";
            }

            MedianRow row = new MedianRow();
            row.calYear     = calYear;
            row.manAge      = manAge;
            row.balance     = (int) Math.max(0, bal);
            row.withdrawal  = wd;
            row.wdPct       = wdPct;
            row.vsYr1       = vsYr1;
            row.alert       = alert;
            row.manSS       = (int) manSS;
            row.womanSS     = (int) womanSS;
            row.annuity     = (int) ann;
            row.guaranteed  = (int) guaranteed;
            row.living      = (int) living;
            row.medical     = (int) medical;
            row.tax         = (int) tax;
            row.totalSpend  = (int) totalSpend;
            row.totalIncome = (int) totalIncome;
            row.surplus     = (int) surplus;
            row.inflFactor  = inflFactor;
            row.returnUsed  = returnUsed;
            row.inflUsed    = inflUsed;
            row.drawing     = drawing;
            res.medianRows.add(row);

            bal = bal * (1 + inp.nomReturn) - wd;
            if (bal < 0) bal = 0;
        }

        // Fan paths
        res.fanBalances    = new double[MC_FAN_PATHS][inp.horizon + 1];
        res.fanWithdrawals = new double[MC_FAN_PATHS][inp.horizon];
        // Store per-path, per-year inflation factors for real-dollar chart deflation
        res.fanInflFactors = new double[MC_FAN_PATHS][inp.horizon + 1];
        int survived = 0;

        for (int p = 0; p < MC_FAN_PATHS; p++) {
            SeededRng rng = new SeededRng(p * 13 + 7);
            double b = inp.portfolio;
            res.fanBalances[p][0]    = b;
            res.fanInflFactors[p][0] = 1.0;

            for (int y = 0; y < inp.horizon; y++) {
                int calYear = BASE_YEAR + y;
                boolean drawing = calYear >= inp.withdrawStartYear;

                // Stochastic inflation for this path/year
                double infl = Math.max(0,
                        inp.inflation + inp.inflationStdDev * rng.nextGaussian());
                res.fanInflFactors[p][y + 1] =
                        res.fanInflFactors[p][y] * (1 + infl);

                int wd = 0;
                if (drawing && b > 0)
                    wd = solveWithdrawal((int) b, inp.horizon - y, inp, p * 1000 + y * 37);
                res.fanWithdrawals[p][y] = wd;

                double ret = inp.nomReturn + inp.stdDev * rng.nextGaussian();
                b = b * (1 + ret) - wd;
                if (b < 0) b = 0;
                res.fanBalances[p][y + 1] = b;
            }
            if (res.fanBalances[p][inp.horizon] > 0) survived++;
        }
        res.actualPoS = survived / (double) MC_FAN_PATHS;

        double[] finals = new double[MC_FAN_PATHS];
        for (int p = 0; p < MC_FAN_PATHS; p++) finals[p] = res.fanBalances[p][inp.horizon];
        Arrays.sort(finals);
        res.medianFinalBalance = (int) finals[MC_FAN_PATHS / 2];
        return res;
    }

    private int solveWithdrawal(int balance, int years, SimInputs inp, int seed) {
        if (balance <= 0 || years <= 0) return 0;
        double lo = 0, hi = balance * 0.22;
        for (int i = 0; i < BINARY_ITERS; i++) {
            double mid = (lo + hi) / 2.0;
            if (survivalRate(balance, years, mid, inp, seed) > inp.targetPoS)
                lo = mid;
            else
                hi = mid;
        }
        return (int) ((lo + hi) / 2.0);
    }

    private double survivalRate(int balance, int years, double wd, SimInputs inp, int seed) {
        int ok = 0;
        for (int i = 0; i < MC_SOLVE_PATHS; i++) {
            SeededRng rng = new SeededRng(seed * 1000L + i * 7 + 3);
            double b = balance;
            boolean alive = true;
            for (int y = 0; y < years; y++) {
                double infl = Math.max(0,
                        inp.inflation + inp.inflationStdDev * rng.nextGaussian());
                double ret  = inp.nomReturn + inp.stdDev * rng.nextGaussian();
                b = b * (1 + ret) - wd * Math.pow(1 + infl, y);
                if (b <= 0) { alive = false; break; }
            }
            if (alive) ok++;
        }
        return ok / (double) MC_SOLVE_PATHS;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  UI UPDATE
    // ════════════════════════════════════════════════════════════════════════
    private void updateUI(SimResults res) {
        SimInputs inp = res.inp;
        int yr1  = res.yr1Withdrawal;
        double rate = yr1 / (double) inp.portfolio * 100.0;

        lblYear1Answer.setText(CURRENCY.format(yr1) + " / yr");
        lblYear1Sub.setText(String.format(
                "  %.2f%% of portfolio  ·  %.0f%% PoS target  ·  %d-year horizon",
                rate, inp.targetPoS * 100, inp.horizon));
        lblYear1Detail.setText(String.format(
                "Man (Sep %d, age %d) · Woman (Dec %d, age %d) · Draws begin %02d/%d · "
                        + "%.2f%% nom return / %.2f%% inflation (1961-2024 defaults)",
                inp.manBirthYear, inp.manAge, inp.womanBirthYear, inp.womanAge,
                inp.withdrawStartMonth, inp.withdrawStartYear,
                inp.nomReturn * 100, inp.inflation * 100));

        int yr10wd  = res.medianRows.size() >= 10 ? res.medianRows.get(9).withdrawal : 0;
        double d10  = res.medianRows.size() >= 10 ? res.medianRows.get(9).inflFactor : 1.0;
        double dEnd = !res.medianRows.isEmpty()
                ? res.medianRows.get(res.medianRows.size() - 1).inflFactor : 1.0;

        lblActualPoS.setText(String.format("%.1f%%", res.actualPoS * 100));
        lblMedianFinal.setText(showRealDollars
                ? formatMoney((long)(res.medianFinalBalance / dEnd)) + " (2026$)"
                : formatMoney(res.medianFinalBalance) + " (nom.)");
        lblYr10Wd.setText(showRealDollars
                ? CURRENCY.format((long)(yr10wd / d10)) + " (2026$)"
                : CURRENCY.format(yr10wd) + " (nom.)");
        lblInitRate.setText(String.format("%.2f%%", rate));

        // Table
        tblModel.setRowCount(0);
        for (MedianRow r : res.medianRows) {
            double d = showRealDollars ? r.inflFactor : 1.0;
            tblModel.addRow(new Object[]{
                    r.manAge,                                                     // 0
                    r.calYear,                                                    // 1
                    CURRENCY.format((long)(r.balance / d)),                       // 2
                    r.drawing ? CURRENCY.format((long)(r.withdrawal / d)) : "—", // 3
                    r.drawing ? String.format("%.2f%%", r.wdPct) : "—",          // 4  Wd%
                    r.alert,                                                      // 5  hidden
                    r.manSS   > 0 ? CURRENCY.format((long)(r.manSS   / d)) : "—",// 6
                    r.womanSS > 0 ? CURRENCY.format((long)(r.womanSS / d)) : "—",// 7
                    r.annuity > 0 ? CURRENCY.format((long)(r.annuity / d)) : "—",// 8
                    r.guaranteed > 0 ? CURRENCY.format((long)(r.guaranteed/d)):"—",//9
                    r.drawing ? CURRENCY.format((long)(r.living  / d)) : "—",    // 10
                    r.drawing ? CURRENCY.format((long)(r.medical / d)) : "—",    // 11
                    r.tax > 0 ? CURRENCY.format((long)(r.tax     / d)) : "—",    // 12
                    r.drawing ? CURRENCY.format((long)(r.totalSpend / d)) : "—", // 13
                    CURRENCY.format((long)(r.totalIncome / d)),                   // 14
                    r.drawing                                                     // 15 surplus
                            ? (r.surplus >= 0 ? "+" : "-")
                              + CURRENCY.format((long)(Math.abs(r.surplus) / d))
                            : "—",
                    String.format("%.3f", r.inflFactor),                          // 16
                    String.format("%.2f%%", r.returnUsed),                        // 17
                    String.format("%.2f%%", r.inflUsed),                          // 18
            });
        }

        refreshChart();
        txaSummary.setText(buildSummary(res));
        txaSummary.setCaretPosition(0);
    }

    private void refreshChart() {
        if (lastResults == null) return;
        chartPanel.setData(lastResults, cmbChartType.getSelectedIndex(), showRealDollars);
        chartPanel.repaint();
    }

    private String buildSummary(SimResults res) {
        SimInputs inp = res.inp;
        int yr1 = res.yr1Withdrawal;
        MedianRow r1 = res.medianRows.stream().filter(r -> r.drawing).findFirst().orElse(null);
        int guar1 = r1 != null ? r1.guaranteed : 0;
        int spd1  = r1 != null ? r1.totalSpend : 0;
        int inc1  = yr1 + guar1;
        int sur1  = inc1 - spd1;
        int fullSSYear = Math.max(inp.manSSStartYear, inp.womanSSStartYear);

        return String.format(
                "══ YEAR 2026 ══\n"
                        + "  No portfolio draws. Woman's salary covers living expenses.\n"
                        + "  Man (Sep %d, age %d) · Woman (Dec %d, age %d)\n\n"
                        + "══ FIRST WITHDRAWAL YEAR (%d) ══\n"
                        + "  Portfolio withdrawal:  %s/yr  (%.2f%% of $%,.0f)\n"
                        + "  + Guaranteed income:   %s  (man SS %s + woman SS %s + annuity %s)\n"
                        + "  = Total income:        %s\n"
                        + "  − Total spending:      %s  (living + medical + est. tax %s)\n"
                        + "  → %s of %s\n\n"
                        + "══ SOCIAL SECURITY ══\n"
                        + "  Man:   %s/yr from %02d/%d (age %d at start)\n"
                        + "  Woman: %s/yr from %02d/%d (age %d at start)\n"
                        + "  Both fully active from %d · COLA %.1f%%/yr\n\n"
                        + "══ ANNUITY ══\n"
                        + "  %s/yr from %d (non-COLA) · Loses ~%.0f%% real value by %d at %.1f%% mean inflation\n\n"
                        + "══ TAX ESTIMATE ══\n"
                        + "  Base %s in %d, grown at %.1f%%/yr.\n"
                        + "  Reflects standard deductions + SS exclusion + qualified dividend rates.\n\n"
                        + "══ MARKET ASSUMPTIONS (1961-2024 historical) ══\n"
                        + "  Nominal return: %.2f%% mean / %.2f%% std dev\n"
                        + "  Inflation:      %.2f%% mean / %.2f%% std dev (stochastic per MC path)\n\n"
                        + "══ MEDIAN SCENARIO ══\n"
                        + "  Portfolio %s at year %d · Actual PoS: %.1f%% across %d paths\n"
                        + "  Medical: %s/yr base at %.1f%%/yr → %s nominally by %d",
                inp.manBirthYear, inp.manAge, inp.womanBirthYear, inp.womanAge,
                inp.withdrawStartYear,
                CURRENCY.format(yr1), yr1 / (double) inp.portfolio * 100, (double) inp.portfolio,
                CURRENCY.format(guar1),
                CURRENCY.format(r1 != null ? r1.manSS   : 0),
                CURRENCY.format(r1 != null ? r1.womanSS : 0),
                CURRENCY.format(r1 != null ? r1.annuity : 0),
                CURRENCY.format(inc1), CURRENCY.format(spd1),
                CURRENCY.format(r1 != null ? r1.tax : 0),
                sur1 >= 0 ? "SURPLUS" : "GAP", CURRENCY.format(Math.abs(sur1)),
                CURRENCY.format(inp.manSSAmount), inp.manSSStartMonth, inp.manSSStartYear,
                inp.manAge + (inp.manSSStartYear - BASE_YEAR),
                CURRENCY.format(inp.womanSSAmount), inp.womanSSStartMonth, inp.womanSSStartYear,
                inp.womanAge + (inp.womanSSStartYear - BASE_YEAR),
                fullSSYear, inp.ssCola * 100,
                CURRENCY.format(inp.annuity), inp.annuityStartYear,
                (1 - 1.0 / Math.pow(1 + inp.inflation, inp.horizon)) * 100,
                BASE_YEAR + inp.horizon, inp.inflation * 100,
                CURRENCY.format(inp.baseTax), inp.withdrawStartYear, inp.taxInflation * 100,
                inp.nomReturn * 100, inp.stdDev * 100,
                inp.inflation * 100, inp.inflationStdDev * 100,
                formatMoney(res.medianFinalBalance), inp.horizon,
                res.actualPoS * 100, MC_FAN_PATHS,
                CURRENCY.format(inp.medical), inp.medInflation * 100,
                CURRENCY.format((long)(inp.medical * Math.pow(1 + inp.medInflation, inp.horizon))),
                BASE_YEAR + inp.horizon
        );
    }

    // ════════════════════════════════════════════════════════════════════════
    //  CHART PANEL
    // ════════════════════════════════════════════════════════════════════════
    static class ChartPanel extends JPanel {
        private SimResults data;
        private int     chartType      = 0;
        private boolean realDollars    = false;
        private static final Color[] PCTC = {
                new Color(99,153,34), new Color(55,138,221), new Color(186,117,23)
        };

        ChartPanel() {
            setBackground(Color.WHITE);
            setBorder(BorderFactory.createLineBorder(new Color(210, 210, 205)));
        }

        void setData(SimResults d, int type, boolean real) {
            this.data       = d;
            this.chartType  = type;
            this.realDollars = real;
        }

        @Override
        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            if (data == null) return;
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            switch (chartType) {
                case 0 -> drawFan(g, false);
                case 1 -> drawFan(g, true);
                case 2 -> drawHist(g);
                case 3 -> drawIncome(g);
            }
        }

        /** Deflate a fan array by per-path, per-year inflation factors if realDollars. */
        private double[][] deflatedFan(double[][] raw, double[][] inflFactors) {
            if (!realDollars) return raw;
            int paths = raw.length;
            int pts   = raw[0].length;
            double[][] out = new double[paths][pts];
            for (int p = 0; p < paths; p++)
                for (int y = 0; y < pts; y++) {
                    double f = (inflFactors != null && y < inflFactors[p].length)
                            ? inflFactors[p][y] : 1.0;
                    out[p][y] = f > 0 ? raw[p][y] / f : 0;
                }
            return out;
        }

        /** Deflate median-row values by the deterministic inflation factor. */
        private double[] deflatedMedian(double[] vals, List<MedianRow> rows) {
            if (!realDollars) return vals;
            double[] out = new double[vals.length];
            for (int i = 0; i < vals.length; i++) {
                double f = (i < rows.size()) ? rows.get(i).inflFactor : 1.0;
                out[i] = f > 0 ? vals[i] / f : 0;
            }
            return out;
        }

        private Rectangle2D pa() {
            return new Rectangle2D.Double(74, 22, getWidth() - 84, getHeight() - 60);
        }

        private double toY(Rectangle2D pa, double v, double mn, double mx) {
            return pa.getMaxY() - (v - mn) / (mx - mn) * pa.getHeight();
        }

        private double toX(Rectangle2D pa, int yr, int tot) {
            return pa.getX() + yr / (double) tot * pa.getWidth();
        }

        private void drawPath(Graphics2D g, Rectangle2D pa, double[] vals,
                              int pts, double mn, double mx, int tot) {
            Path2D path = new Path2D.Double();
            boolean started = false;
            for (int i = 0; i < pts; i++) {
                double x = toX(pa, i, tot);
                double y = toY(pa, Math.max(mn, Math.min(mx, vals[i])), mn, mx);
                if (!started) { path.moveTo(x, y); started = true; }
                else path.lineTo(x, y);
            }
            g.draw(path);
        }

        private void grid(Graphics2D g, Rectangle2D pa) {
            g.setColor(new Color(220, 220, 215));
            g.setStroke(new BasicStroke(0.5f));
            for (int i = 0; i <= 6; i++) {
                double y = pa.getY() + i / 6.0 * pa.getHeight();
                g.draw(new Line2D.Double(pa.getX(), y, pa.getMaxX(), y));
            }
            g.setColor(new Color(180, 180, 175));
            g.setStroke(new BasicStroke(1f));
            g.draw(pa);
        }

        private void axes(Graphics2D g, Rectangle2D pa,
                          double mn, double mx, int xs, String yLbl) {
            g.setFont(new Font("SansSerif", Font.PLAIN, 10));
            g.setColor(new Color(90, 90, 90));
            for (int i = 0; i <= 6; i++) {
                double val = mn + (mx - mn) * (1 - i / 6.0);
                double y   = pa.getY() + i / 6.0 * pa.getHeight();
                String l   = formatMoney((long) val);
                FontMetrics fm = g.getFontMetrics();
                g.drawString(l, (float)(pa.getX() - fm.stringWidth(l) - 3), (float)(y + 4));
            }
            for (int y = 0; y <= xs; y += 5) {
                double x = toX(pa, y, xs);
                g.drawString("Yr" + y, (float)(x - 8), (float)(pa.getMaxY() + 14));
            }
            // Dollar mode label suffix
            String suffix = realDollars ? " (2026 $)" : " (nominal)";
            Graphics2D g2 = (Graphics2D) g.create();
            g2.rotate(-Math.PI / 2, 11, pa.getCenterY());
            g2.setFont(new Font("SansSerif", Font.PLAIN, 10));
            g2.setColor(new Color(100, 100, 100));
            String full = yLbl + suffix;
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(full,
                    (float)(11 - fm.stringWidth(full) / 2.0), (float) pa.getCenterY());
            g2.dispose();
        }

        private void drawFan(Graphics2D g, boolean balMode) {
            int yrs = data.inp.horizon;
            double[][] rawSer = balMode ? data.fanBalances : data.fanWithdrawals;
            // For withdrawals, inflation factors are indexed 0..yrs-1; for balances 0..yrs
            double[][] inflF  = data.fanInflFactors;
            double[][] ser    = deflatedFan(rawSer, inflF);
            int pts = balMode ? yrs + 1 : yrs;

            double maxV = 0;
            for (double[] p : ser) for (double v : p) maxV = Math.max(maxV, v);
            maxV = Math.ceil(maxV / 100_000.0) * 100_000;

            Rectangle2D pa = pa();
            grid(g, pa);

            int step = Math.max(1, MC_FAN_PATHS / 60);
            for (int p = 0; p < MC_FAN_PATHS; p += step) {
                boolean sv = data.fanBalances[p][yrs] > 0;
                g.setColor(sv ? new Color(55,138,221,28) : new Color(226,75,74,18));
                g.setStroke(new BasicStroke(0.7f));
                drawPath(g, pa, ser[p], pts, 0, maxV, yrs);
            }

            String[] lbls = {"75th pct", "Median", "25th pct"};
            double[] pcts = {0.75, 0.50, 0.25};
            for (int pi = 0; pi < 3; pi++) {
                double[] pl = new double[pts];
                for (int y = 0; y < pts; y++) {
                    double[] vs = new double[MC_FAN_PATHS];
                    for (int p = 0; p < MC_FAN_PATHS; p++)
                        vs[p] = ser[p][Math.min(y, ser[p].length - 1)];
                    Arrays.sort(vs);
                    pl[y] = vs[(int)(pcts[pi] * (MC_FAN_PATHS - 1))];
                }
                g.setColor(PCTC[pi]);
                g.setStroke(new BasicStroke(2.2f));
                drawPath(g, pa, pl, pts, 0, maxV, yrs);
                g.setFont(new Font("SansSerif", Font.PLAIN, 10));
                g.drawString(lbls[pi],
                        (float)(toX(pa, pts - 1, yrs) + 2),
                        (float) toY(pa, pl[pts - 1], 0, maxV));
            }
            axes(g, pa, 0, maxV, yrs,
                    balMode ? "Portfolio balance" : "Annual withdrawal");
        }

        private void drawHist(Graphics2D g) {
            int yrs = data.inp.horizon;
            // Deflate final balances if in real-dollar mode using end-of-horizon factor
            double[] fn = new double[MC_FAN_PATHS];
            for (int p = 0; p < MC_FAN_PATHS; p++) {
                double raw = data.fanBalances[p][yrs];
                double f   = realDollars ? data.fanInflFactors[p][yrs] : 1.0;
                fn[p] = f > 0 ? raw / f : 0;
            }
            Arrays.sort(fn);
            double maxV = fn[MC_FAN_PATHS - 1];
            int BINS = 16; double bw = Math.max(1, maxV / BINS);
            int[] cnt = new int[BINS]; int fail = 0;
            for (double v : fn) {
                if (v <= 0) { fail++; continue; }
                cnt[Math.min(BINS - 1, (int)(v / bw))]++;
            }
            int maxC = Arrays.stream(cnt).max().orElse(1);
            Rectangle2D pa = pa(); grid(g, pa);
            double bwPx = pa.getWidth() / BINS * 0.85;
            for (int b = 0; b < BINS; b++) {
                double x = pa.getX() + b / (double) BINS * pa.getWidth();
                double h = cnt[b] / (double) maxC * pa.getHeight();
                g.setColor(new Color(55, 138, 221, 160));
                g.fill(new Rectangle2D.Double(x, pa.getMaxY() - h, bwPx, h));
                g.setColor(new Color(24, 95, 165));
                g.setStroke(new BasicStroke(0.8f));
                g.draw(new Rectangle2D.Double(x, pa.getMaxY() - h, bwPx, h));
            }
            g.setColor(new Color(163, 45, 45));
            g.setFont(new Font("SansSerif", Font.BOLD, 11));
            g.drawString(fail + " of " + MC_FAN_PATHS + " failed ("
                            + String.format("%.0f%%", fail * 100.0 / MC_FAN_PATHS) + ")",
                    (float)(pa.getX() + 4), (float)(pa.getY() + 14));
            String suffix = realDollars ? " (2026 $)" : " (nominal)";
            axes(g, pa, 0, maxC, BINS, "# paths by final balance" + suffix);
        }

        private void drawIncome(Graphics2D g) {
            List<MedianRow> rows = data.medianRows;
            if (rows.isEmpty()) return;
            int yrs = rows.size();

            double[] rawInc = rows.stream().mapToDouble(r -> r.totalIncome).toArray();
            double[] rawSpd = rows.stream().mapToDouble(r -> r.totalSpend).toArray();
            double[] rawWd  = rows.stream().mapToDouble(r -> r.withdrawal).toArray();
            double[] rawGu  = rows.stream().mapToDouble(r -> r.guaranteed).toArray();

            double[] inc = deflatedMedian(rawInc, rows);
            double[] spd = deflatedMedian(rawSpd, rows);
            double[] wd  = deflatedMedian(rawWd,  rows);
            double[] gu  = deflatedMedian(rawGu,  rows);

            double maxV = 0;
            for (int i = 0; i < yrs; i++) maxV = Math.max(maxV, Math.max(inc[i], spd[i]));
            maxV = Math.ceil(maxV / 50_000.0) * 50_000;

            Rectangle2D pa = pa(); grid(g, pa);
            Color[]    cols = {
                    new Color(99,153,34), new Color(226,75,74,200),
                    new Color(55,138,221), new Color(29,158,117)
            };
            String[]   lbls  = {"Total income","Total spending",
                    "Portfolio withdrawal","Guaranteed income"};
            double[][] lines = {inc, spd, wd, gu};
            for (int li = 0; li < lines.length; li++) {
                g.setColor(cols[li]);
                g.setStroke(li < 2 ? new BasicStroke(2.5f) :
                        new BasicStroke(1.8f, BasicStroke.CAP_BUTT,
                                BasicStroke.JOIN_ROUND, 1f, new float[]{5,3}, 0));
                drawPath(g, pa, lines[li], yrs, 0, maxV, yrs);
            }
            g.setFont(new Font("SansSerif", Font.PLAIN, 10));
            for (int li = 0; li < lbls.length; li++) {
                g.setColor(cols[li]);
                g.fillRect((int)(pa.getX() + 4 + li * 126), (int)(pa.getY() + 4), 10, 10);
                g.setColor(new Color(50, 50, 50));
                g.drawString(lbls[li],
                        (int)(pa.getX() + 17 + li * 126), (int)(pa.getY() + 13));
            }
            axes(g, pa, 0, maxV, yrs, "Annual dollars");
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  SEEDED RNG
    // ════════════════════════════════════════════════════════════════════════
    static class SeededRng {
        private long state;
        SeededRng(long seed) { this.state = seed ^ 0x6c62272e07bb0142L; }
        private double nextUniform() {
            state = state * 6364136223846793005L + 1442695040888963407L;
            long bits = (state >>> 33) ^ state;
            return (bits >>> 1) / (double) Long.MAX_VALUE;
        }
        double nextGaussian() {
            double u = Math.max(1e-12, nextUniform()), v = nextUniform();
            return Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * v);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  DATA CLASSES
    // ════════════════════════════════════════════════════════════════════════
    static class SimInputs {
        int portfolio, horizon; double targetPoS;
        int withdrawStartYear, withdrawStartMonth;
        int manBirthYear, manBirthMonth, manAge;
        int womanBirthYear, womanBirthMonth, womanAge, currentAge;
        int manSSAmount, manSSStartYear, manSSStartMonth;
        int womanSSAmount, womanSSStartYear, womanSSStartMonth;
        double ssCola;
        int annuity, annuityStartYear;
        double nomReturn, stdDev, inflation, inflationStdDev;
        int livingExp, medical; double medInflation;
        int baseTax; double taxInflation;
        double upperGuardrail, lowerGuardrail;
    }

    static class MedianRow {
        int calYear, manAge, balance, withdrawal;
        int manSS, womanSS, annuity, guaranteed;
        int living, medical, tax, totalSpend, totalIncome, surplus;
        double vsYr1, wdPct, inflFactor, returnUsed, inflUsed;
        String alert;
        boolean drawing;
    }

    static class SimResults {
        SimInputs inp; int yr1Withdrawal;
        List<MedianRow> medianRows;
        double[][] fanBalances, fanWithdrawals, fanInflFactors;
        double actualPoS; int medianFinalBalance;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  UI HELPERS
    // ════════════════════════════════════════════════════════════════════════
    private JPanel card(String title, Object[] items) {
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBackground(Color.WHITE);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(0, 0, 6, 0),
                BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(new Color(208, 206, 200), 1),
                        BorderFactory.createEmptyBorder(8, 10, 8, 10))));

        JLabel titleLbl = new JLabel(title.toUpperCase());
        titleLbl.setFont(new Font("SansSerif", Font.BOLD, 10));
        titleLbl.setForeground(new Color(110, 105, 95));
        titleLbl.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
        titleLbl.setAlignmentX(LEFT_ALIGNMENT);
        card.add(titleLbl);

        for (int i = 0; i < items.length; i += 2) {
            Object labelObj = items[i];
            Object comp     = items[i + 1];
            if (labelObj != null) {
                JLabel lbl = new JLabel((String) labelObj);
                lbl.setFont(new Font("SansSerif", Font.PLAIN, 12));
                lbl.setForeground(new Color(75, 75, 75));
                lbl.setBorder(BorderFactory.createEmptyBorder(5, 0, 1, 0));
                lbl.setAlignmentX(LEFT_ALIGNMENT);
                card.add(lbl);
            }
            JComponent row = (comp instanceof JSpinner sp) ? wrapSpinner(sp) : (JComponent) comp;
            row.setAlignmentX(LEFT_ALIGNMENT);
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
            card.add(row);
        }
        return card;
    }

    private JPanel wrapSpinner(JSpinner sp) {
        sp.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        sp.setPreferredSize(new Dimension(360, 28));
        sp.setMinimumSize(new Dimension(160, 28));
        JPanel p = new JPanel(new BorderLayout()); p.setOpaque(false);
        p.add(sp, BorderLayout.CENTER);
        return p;
    }

    private JSpinner spinI(int val, int min, int max, int step, String fmt) {
        JSpinner s = new JSpinner(new SpinnerNumberModel(val, min, max, step));
        s.setEditor(new JSpinner.NumberEditor(s, fmt));
        s.setFont(new Font("SansSerif", Font.PLAIN, 12));
        return s;
    }

    private JSpinner spinD(double val, double min, double max, double step, String fmt) {
        JSpinner s = new JSpinner(new SpinnerNumberModel(val, min, max, step));
        s.setEditor(new JSpinner.NumberEditor(s, fmt));
        s.setFont(new Font("SansSerif", Font.PLAIN, 12));
        return s;
    }

    private JLabel mkMetricLabel() {
        JLabel l = new JLabel("—");
        l.setFont(new Font("SansSerif", Font.BOLD, 17));
        l.setForeground(new Color(30, 30, 30));
        return l;
    }

    private JPanel wrapMetric(JLabel val, String title, String sub) {
        JPanel p = new JPanel(new BorderLayout(2, 2));
        p.setBackground(new Color(240, 240, 237));
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(210, 208, 203), 1),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)));
        JLabel t = new JLabel(title);
        t.setFont(new Font("SansSerif", Font.PLAIN, 10));
        t.setForeground(new Color(110, 110, 110));
        JLabel s = new JLabel(sub);
        s.setFont(new Font("SansSerif", Font.PLAIN, 10));
        s.setForeground(new Color(140, 140, 140));
        p.add(t,   BorderLayout.NORTH);
        p.add(val, BorderLayout.CENTER);
        p.add(s,   BorderLayout.SOUTH);
        return p;
    }

    private static String formatMoney(long n) {
        if (Math.abs(n) >= 1_000_000) return String.format("$%.2fM", n / 1_000_000.0);
        if (Math.abs(n) >= 1_000)     return String.format("$%,dK",  n / 1_000);
        return "$" + n;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  MAIN
    // ════════════════════════════════════════════════════════════════════════
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
            catch (Exception ignored) {}
            new IncomeLabStyle_PoSDriven();
        });
    }
}
