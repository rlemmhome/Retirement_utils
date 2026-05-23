package com.hiflite.incomelabs_riskbased;

// =============================================================================
//  Annuity Surrender & Roth Conversion Planner
//  com.hiflite.incomelabs_riskbased.RothConversionPlanner
//  Target: Java 25  (compiled on 21 in sandbox -- 100% compatible)
//  UI:     Swing, single source file, ASCII only
//
//  Personal context:
//    Bob  born Sept 1961 (age 64)  |  Jo born Dec 1962 (age 63)
//    MFJ, Arizona (2.5% flat state income tax)
//    Annuity: $22,599/yr non-COLA, starts April 2028, held in Traditional IRA
//      PV ~$415K  |  surrender fee ~$8K  |  net proceeds to Trad IRA ~$407K
//    Growth: 6.70% nominal  /  3.79% inflation
// =============================================================================

import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.border.MatteBorder;
import javax.swing.event.ChangeListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.text.NumberFormat;
import java.util.Arrays;

public class RothConversionPlanner extends JFrame {

    // =========================================================================
    //  MODEL CONSTANTS
    // =========================================================================

    static final int    PLAN_START      = 2026;
    static final int    PLAN_END        = 2032;
    static final int    YEARS           = PLAN_END - PLAN_START + 1;   // 7

    static final double GROWTH          = 0.0670;   // nominal annual
    static final double INFLATION       = 0.0379;   // for reference / real display

    // Annuity
    static final double ANNUITY_ANNUAL      = 22_599.0;
    static final int    ANNUITY_START_YEAR  = 2028;
    static final double ANNUITY_PV_GROSS    = 415_000.0;
    static final double ANNUITY_SURRENDER_FEE = 8_000.0;
    static final double ANNUITY_PV_NET      = ANNUITY_PV_GROSS - ANNUITY_SURRENDER_FEE; // 407_000

    // Initial balances (2026)
    static final double INIT_BOB_TRAD_IRA   = 880_000;
    static final double INIT_BOB_ROTH_IRA   =  10_000;
    static final double INIT_JO_TRAD_IRA    = 266_000;
    static final double INIT_JO_TRAD_401K   = 314_000;
    static final double INIT_JO_ROTH_401K   =  30_000;
    static final double INIT_MONEY_MARKET   = 150_000;

    // Aggregate starting pools
    //   Trad pool = Bob Trad IRA + Jo Trad IRA + Jo Trad 401K
    //   Roth pool = Bob Roth IRA + Jo Roth 401K
    static final double BASE_TRAD_POOL  = INIT_BOB_TRAD_IRA + INIT_JO_TRAD_IRA + INIT_JO_TRAD_401K;
    static final double BASE_ROTH_POOL  = INIT_BOB_ROTH_IRA + INIT_JO_ROTH_401K;

    // 2026 MFJ Federal Tax Brackets  {lower, upper, rate}
    static final double[][] BRACKETS = {
            {        0,  23_200, 0.10},
            {   23_200,  94_300, 0.12},
            {   94_300, 201_050, 0.22},
            {  201_050, 383_900, 0.24},
            {  383_900, 487_450, 0.32},
            {  487_450, 731_200, 0.35},
            {  731_200, Double.MAX_VALUE, 0.37}
    };

    // 2026 MFJ standard deduction
    static final double STD_DED = 30_000.0;

    // IRMAA (Medicare Part B + D combined surcharge per COUPLE/yr)
    // Tier assessed on MAGI from 2 years prior
    // Tier 0: MAGI <= 218,000  => $0
    // Tier 1: 218,001-273,000  => $1,188  ($594/person x2)
    // Tier 2: 273,001-346,000  => $3,024  ($1,512/person x2)
    // Tier 3: 346,001-750,000  => $4,836
    // Tier 4: >750,000         => $5,508
    static final double[] IRMAA_THRESH     = {218_000, 273_000, 346_000, 750_000};
    static final double[] IRMAA_COUPLE_YR  = {0, 1_188, 3_024, 4_836, 5_508};

    // SS: 85% of gross is taxable (above MFJ threshold -- always assumed here)
    static final double SS_TAX_FRAC = 0.85;

    // =========================================================================
    //  PALETTE
    // =========================================================================

    static final Color C_BG        = new Color(14, 18, 28);
    static final Color C_PANEL     = new Color(20, 26, 42);
    static final Color C_CARD      = new Color(28, 36, 56);
    static final Color C_CARD2     = new Color(24, 32, 50);
    static final Color C_BORDER    = new Color(44, 58, 88);
    static final Color C_GOLD      = new Color(212, 175, 55);
    static final Color C_GOLD_DIM  = new Color(140, 115, 36);
    static final Color C_BLUE      = new Color(80, 160, 255);
    static final Color C_GREEN     = new Color(50, 210, 130);
    static final Color C_RED       = new Color(255, 85, 85);
    static final Color C_AMBER     = new Color(255, 175, 45);
    static final Color C_TEXT      = new Color(225, 232, 245);
    static final Color C_DIM       = new Color(120, 135, 165);
    static final Color C_ROW_ALT   = new Color(22, 30, 48);

    // =========================================================================
    //  MUTABLE STATE  (driven by spinners)
    // =========================================================================

    boolean surrenderAnnuity = true;
    double  annuityPVNet     = ANNUITY_PV_NET;   // user-adjustable

    double  bobSSAnnual      = 0;
    double  joSSAnnual       = 0;
    int     bobSSStart       = 2028;
    int     joSSStart        = 2028;
    double  otherIncome      = 0;

    double[] convAmts = new double[YEARS];

    // =========================================================================
    //  UI REFERENCES
    // =========================================================================

    // Per-year output labels (indexed 0=2026 .. 6=2032)
    final JLabel[] lMagi      = new JLabel[YEARS];
    final JLabel[] lFedTax    = new JLabel[YEARS];
    final JLabel[] lStateTax  = new JLabel[YEARS];
    final JLabel[] lIrmaaTier = new JLabel[YEARS];
    final JLabel[] lIrmaaCost = new JLabel[YEARS];
    final JLabel[] lTradBal   = new JLabel[YEARS];
    final JLabel[] lRothBal   = new JLabel[YEARS];
    final JLabel[] lNetCost   = new JLabel[YEARS];
    final JLabel[] lCumTax    = new JLabel[YEARS];
    final JLabel[] lBracket   = new JLabel[YEARS];

    // Per-year input spinners
    final JSpinner[] sConv = new JSpinner[YEARS];

    // Summary
    final JLabel lSumFedState = new JLabel();
    final JLabel lSumIrmaa    = new JLabel();
    final JLabel lSumGrand    = new JLabel();
    final JLabel lSumTrad     = new JLabel();
    final JLabel lSumRoth     = new JLabel();

    // Comparison table
    DefaultTableModel compModel;
    JTable            compTable;

    // Income spinners
    JSpinner sBobSS, sJoSS, sBobSSYear, sJoSSYear, sOther;
    JCheckBox cbSurrender;
    JSpinner  sPVNet;

    final NumberFormat NF = NumberFormat.getCurrencyInstance();

    // =========================================================================
    //  CONSTRUCTION
    // =========================================================================

    public RothConversionPlanner() {
        super("Roth Conversion Planner  |  IncomeLabsRiskBased  |  Bob & Jo  |  2026-2032");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(1400, 900));
        Arrays.fill(convAmts, 0);
        buildUI();
        recalc();
        pack();
        setLocationRelativeTo(null);
        setVisible(true);
    }

    // =========================================================================
    //  UI ASSEMBLY
    // =========================================================================

    void buildUI() {
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(C_BG);
        root.add(makeHeader(),  BorderLayout.NORTH);
        root.add(makeCenter(), BorderLayout.CENTER);
        root.add(makeFooter(), BorderLayout.SOUTH);
        setContentPane(root);
    }

    // ---- Header -------------------------------------------------------------

    JPanel makeHeader() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(C_PANEL);
        p.setBorder(new MatteBorder(0, 0, 2, 0, C_GOLD));
        p.setPreferredSize(new Dimension(0, 56));

        JLabel title = new JLabel("  ANNUITY SURRENDER & ROTH CONVERSION PLANNER  |  2026 – 2032");
        title.setFont(new Font("Monospaced", Font.BOLD, 17));
        title.setForeground(C_GOLD);

        JLabel sub = new JLabel("Bob (b.1961) & Jo (b.1962)  |  MFJ  |  Arizona 2.5%  |  IL RiskBased  |  6.70% nominal growth  ");
        sub.setFont(new Font("Monospaced", Font.PLAIN, 11));
        sub.setForeground(C_DIM);

        p.add(title, BorderLayout.WEST);
        p.add(sub,   BorderLayout.EAST);
        return p;
    }

    // ---- Center (split pane) ------------------------------------------------

    JComponent makeCenter() {
        JSplitPane sp = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                makeLeftPanel(), makeRightPanel());
        sp.setDividerLocation(430);
        sp.setDividerSize(5);
        sp.setBorder(null);
        sp.setBackground(C_BG);
        return sp;
    }

    // ---- Left panel: inputs -------------------------------------------------

    JScrollPane makeLeftPanel() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(C_PANEL);
        p.setBorder(new EmptyBorder(14, 14, 14, 10));

        p.add(sectionHead("SOCIAL SECURITY & INCOME"));
        p.add(vgap(8));
        p.add(makeIncomeCard());
        p.add(vgap(16));

        p.add(sectionHead("ANNUITY"));
        p.add(vgap(8));
        p.add(makeAnnuityCard());
        p.add(vgap(16));

        p.add(sectionHead("ROTH CONVERSION — PER YEAR"));
        p.add(vgap(8));
        p.add(makeConversionCard());
        p.add(vgap(16));

        p.add(sectionHead("QUICK STRATEGIES"));
        p.add(vgap(8));
        p.add(makeStrategyCard());
        p.add(vgap(8));

        JScrollPane sp = new JScrollPane(p);
        sp.setBorder(null);
        sp.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        sp.getViewport().setBackground(C_PANEL);
        return sp;
    }

    JPanel makeIncomeCard() {
        JPanel p = card();
        p.setLayout(new GridLayout(0, 2, 8, 7));

        p.add(dim("Bob SS gross (annual $):"));
        sBobSS = dollarSpin(0, 0, 80_000, 1_000);
        p.add(sBobSS);

        p.add(dim("Bob SS start year:"));
        sBobSSYear = yearSpin(2028, 2026, 2035);
        p.add(sBobSSYear);

        p.add(dim("Jo SS gross (annual $):"));
        sJoSS = dollarSpin(0, 0, 60_000, 1_000);
        p.add(sJoSS);

        p.add(dim("Jo SS start year:"));
        sJoSSYear = yearSpin(2028, 2026, 2035);
        p.add(sJoSSYear);

        p.add(dim("Other income (annual $):"));
        sOther = dollarSpin(0, 0, 200_000, 1_000);
        p.add(sOther);

        ChangeListener cl = e -> syncAndRecalc();
        sBobSS.addChangeListener(cl);
        sBobSSYear.addChangeListener(cl);
        sJoSS.addChangeListener(cl);
        sJoSSYear.addChangeListener(cl);
        sOther.addChangeListener(cl);

        return p;
    }

    JPanel makeAnnuityCard() {
        JPanel p = card();
        p.setLayout(new GridLayout(0, 2, 8, 7));

        p.add(dim("Surrender annuity:"));
        cbSurrender = new JCheckBox("Yes — net proceeds to Trad IRA", true);
        cbSurrender.setFont(new Font("Monospaced", Font.PLAIN, 12));
        cbSurrender.setForeground(C_TEXT);
        cbSurrender.setBackground(C_CARD);
        cbSurrender.addActionListener(e -> syncAndRecalc());
        p.add(cbSurrender);

        p.add(dim("Net proceeds to IRA ($):"));
        sPVNet = dollarSpin((int)ANNUITY_PV_NET, 50_000, 600_000, 1_000);
        sPVNet.addChangeListener(e -> syncAndRecalc());
        p.add(sPVNet);

        // Info labels
        JLabel info1 = html("<font color='#787F9F'>Gross PV ~$415K  |  Surrender fee ~$8K  |  Net ~$407K</font>");
        JLabel info2 = html("<font color='#787F9F'>If kept: $22,599/yr ordinary income from Apr 2028</font>");
        p.add(info1); p.add(new JLabel(""));
        p.add(info2); p.add(new JLabel(""));

        return p;
    }

    JPanel makeConversionCard() {
        JPanel p = card();
        p.setLayout(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(3, 5, 3, 5);
        g.fill = GridBagConstraints.HORIZONTAL;

        // Column headers
        g.gridy = 0;
        g.gridx = 0; p.add(colHdr("Year"),        g);
        g.gridx = 1; p.add(colHdr("Convert ($)"), g);
        g.gridx = 2; p.add(colHdr("Top Bracket"), g);

        for (int i = 0; i < YEARS; i++) {
            int year = PLAN_START + i;
            g.gridy = i + 1;

            g.gridx = 0;
            JLabel yl = mono(String.valueOf(year));
            yl.setForeground(C_BLUE);
            yl.setFont(new Font("Monospaced", Font.BOLD, 13));
            p.add(yl, g);

            g.gridx = 1;
            sConv[i] = dollarSpin(0, 0, 700_000, 5_000);
            final int fi = i;
            sConv[i].addChangeListener(e -> {
                convAmts[fi] = num(sConv[fi]);
                recalc();
            });
            p.add(sConv[i], g);

            g.gridx = 2;
            lBracket[i] = mono("—");
            lBracket[i].setForeground(C_DIM);
            lBracket[i].setPreferredSize(new Dimension(62, 22));
            p.add(lBracket[i], g);
        }
        return p;
    }

    JPanel makeStrategyCard() {
        JPanel p = card();
        p.setLayout(new GridLayout(2, 3, 8, 8));
        p.add(stratBtn("Fill to 22% Top",    "22top"));
        p.add(stratBtn("Fill to 24% Top",    "24top"));
        p.add(stratBtn("IRMAA Tier 1 Edge",  "irmaa1"));
        p.add(stratBtn("IRMAA Tier 2 Edge",  "irmaa2"));
        p.add(stratBtn("Flat $150K/yr",      "flat150"));
        p.add(stratBtn("Clear All",          "clear"));
        return p;
    }

    // ---- Right panel: outputs -----------------------------------------------

    JScrollPane makeRightPanel() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(C_BG);
        p.setBorder(new EmptyBorder(14, 10, 14, 14));

        p.add(sectionHead("YEAR-BY-YEAR PROJECTION"));
        p.add(vgap(8));
        p.add(makeProjectionGrid());
        p.add(vgap(16));

        p.add(sectionHead("SUMMARY TOTALS  (2026 – 2032)"));
        p.add(vgap(8));
        p.add(makeSummaryBar());
        p.add(vgap(16));

        p.add(sectionHead("STRATEGY COMPARISON  (auto-updated)"));
        p.add(vgap(8));
        p.add(makeComparisonTable());
        p.add(vgap(16));

        p.add(sectionHead("TAX BRACKETS & IRMAA REFERENCE"));
        p.add(vgap(8));
        p.add(makeRefPanel());
        p.add(vgap(8));

        JScrollPane sp = new JScrollPane(p);
        sp.setBorder(null);
        sp.getViewport().setBackground(C_BG);
        return sp;
    }

    JPanel makeProjectionGrid() {
        JPanel tbl = new JPanel(new GridBagLayout());
        tbl.setBackground(C_CARD);
        tbl.setBorder(new LineBorder(C_BORDER));
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(2, 7, 2, 7);
        g.fill = GridBagConstraints.HORIZONTAL;

        String[] hdrs = {"Year","MAGI","Fed Tax","State Tax",
                "IRMAA Tier","IRMAA Cost","Trad IRA","Roth IRA",
                "Net Conv Cost","Cum Tax Paid"};
        g.gridy = 0;
        for (int c = 0; c < hdrs.length; c++) {
            g.gridx = c;
            JLabel h = colHdr(hdrs[c]);
            h.setPreferredSize(new Dimension(c == 0 ? 40 : 95, 22));
            tbl.add(h, g);
        }

        for (int i = 0; i < YEARS; i++) {
            g.gridy = i + 1;
            Color rb = (i % 2 == 0) ? C_CARD : C_ROW_ALT;

            g.gridx = 0;
            JLabel yl = mono(String.valueOf(PLAN_START + i));
            yl.setForeground(C_BLUE); yl.setFont(new Font("Monospaced",Font.BOLD,12));
            yl.setOpaque(true); yl.setBackground(rb);
            tbl.add(yl, g);

            lMagi[i]      = outLabel(C_TEXT,  rb); g.gridx=1; tbl.add(lMagi[i],g);
            lFedTax[i]    = outLabel(C_RED,   rb); g.gridx=2; tbl.add(lFedTax[i],g);
            lStateTax[i]  = outLabel(C_AMBER, rb); g.gridx=3; tbl.add(lStateTax[i],g);
            lIrmaaTier[i] = outLabel(C_DIM,   rb); g.gridx=4; tbl.add(lIrmaaTier[i],g);
            lIrmaaCost[i] = outLabel(C_AMBER, rb); g.gridx=5; tbl.add(lIrmaaCost[i],g);
            lTradBal[i]   = outLabel(C_TEXT,  rb); g.gridx=6; tbl.add(lTradBal[i],g);
            lRothBal[i]   = outLabel(C_GREEN, rb); g.gridx=7; tbl.add(lRothBal[i],g);
            lNetCost[i]   = outLabel(C_RED,   rb); g.gridx=8; tbl.add(lNetCost[i],g);
            lCumTax[i]    = outLabel(C_DIM,   rb); g.gridx=9; tbl.add(lCumTax[i],g);
        }

        JScrollPane sp = new JScrollPane(tbl);
        sp.setBorder(null);
        sp.setPreferredSize(new Dimension(900, 210));
        sp.getViewport().setBackground(C_CARD);

        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setBackground(C_BG);
        wrap.setMaximumSize(new Dimension(Integer.MAX_VALUE, 220));
        wrap.add(sp);
        return wrap;
    }

    JPanel makeSummaryBar() {
        JPanel p = new JPanel(new GridLayout(1, 5, 12, 0));
        p.setBackground(C_BG);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 78));
        p.add(summCell("Total Fed + State Tax", lSumFedState, C_RED));
        p.add(summCell("Total IRMAA (paid yrs)", lSumIrmaa,   C_AMBER));
        p.add(summCell("Grand Total Cost",       lSumGrand,   C_RED));
        p.add(summCell("Trad IRA (end 2032)",    lSumTrad,    C_TEXT));
        p.add(summCell("Roth IRA (end 2032)",    lSumRoth,    C_GREEN));
        return p;
    }

    JPanel summCell(String title, JLabel val, Color vc) {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(new Color(18, 24, 40));
        p.setBorder(new CompoundBorder(new LineBorder(C_BORDER),
                new EmptyBorder(8, 10, 8, 10)));
        JLabel t = new JLabel(title, SwingConstants.CENTER);
        t.setFont(new Font("Monospaced", Font.PLAIN, 10));
        t.setForeground(C_DIM);
        val.setFont(new Font("Monospaced", Font.BOLD, 14));
        val.setForeground(vc);
        val.setHorizontalAlignment(SwingConstants.CENTER);
        p.add(t,   BorderLayout.NORTH);
        p.add(val, BorderLayout.CENTER);
        return p;
    }

    JPanel makeComparisonTable() {
        String[] cols = {"Strategy","Fed Tax","State Tax","IRMAA","Grand Total",
                "Final Roth","Final Trad","Roth %"};
        compModel = new DefaultTableModel(cols, 0) {
            public boolean isCellEditable(int r, int c) { return false; }
        };
        compTable = new JTable(compModel);
        styleTable(compTable);

        JScrollPane sp = new JScrollPane(compTable);
        sp.setBorder(new LineBorder(C_BORDER));
        sp.setPreferredSize(new Dimension(900, 175));
        sp.getViewport().setBackground(C_CARD);

        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(C_BG);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 185));
        p.add(sp);
        return p;
    }

    JPanel makeRefPanel() {
        JPanel p = new JPanel(new GridLayout(1, 2, 14, 0));
        p.setBackground(C_BG);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 190));

        p.add(refArea(
                "2026 MFJ Federal Brackets (taxable = MAGI - $30,000 std ded):\n" +
                        "  10%  :       $0  -  $23,200\n" +
                        "  12%  :  $23,200  -  $94,300\n" +
                        "  22%  :  $94,300  - $201,050\n" +
                        "  24%  : $201,050  - $383,900\n" +
                        "  32%  : $383,900  - $487,450\n" +
                        "  35%  : $487,450  - $731,200\n\n" +
                        "Arizona: 2.5% flat on taxable income\n" +
                        "SS:      85% of gross is taxable (above threshold)\n" +
                        "Growth:  6.70% nominal  /  3.79% inflation"
        ));

        p.add(refArea(
                "IRMAA Medicare Surcharge (2-YEAR income lookback):\n" +
                        "  Tier 0: MAGI <= $218,000      ->  $0/couple/yr\n" +
                        "  Tier 1: $218,001 - $273,000   ->  $1,188/couple/yr\n" +
                        "  Tier 2: $273,001 - $346,000   ->  $3,024/couple/yr\n" +
                        "  Tier 3: $346,001 - $750,000   ->  $4,836/couple/yr\n" +
                        "  Tier 4: > $750,000            ->  $5,508/couple/yr\n\n" +
                        "IRMAA cost displayed in the PAYMENT year (2 yrs after\n" +
                        "  the income year that triggered it).\n" +
                        "Annuity: $22,599/yr from Apr 2028 (9/12 first year).\n" +
                        "  If surrendered: net $407K added to Trad IRA pool."
        ));

        return p;
    }

    // ---- Footer -------------------------------------------------------------

    JPanel makeFooter() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.RIGHT, 16, 4));
        p.setBackground(new Color(10, 14, 22));
        p.setBorder(new MatteBorder(1, 0, 0, 0, C_BORDER));
        JLabel l = new JLabel(
                "com.hiflite.incomelabs_riskbased.RothConversionPlanner  |  " +
                        "Java 25 source (compiled 21)  |  Swing  |  " +
                        "Annuity PV gross $415K / net $407K after $8K surrender fee");
        l.setFont(new Font("Monospaced", Font.PLAIN, 10));
        l.setForeground(C_DIM);
        p.add(l);
        return p;
    }

    // =========================================================================
    //  CALCULATION ENGINE
    // =========================================================================

    // Result holder
    static class Yr {
        int    year;
        double magi, taxableIncome, fedTax, stateTax;
        int    irmaaTier;          // tier for IRMAA cost PAID this year (based on magi 2yr prior)
        double irmaaCost;          // dollars paid this year
        double tradBal, rothBal;
        double netConvCost;        // marginal tax cost of conversion only
        double cumTax;
        String topBracket;
    }

    void syncAndRecalc() {
        bobSSAnnual   = num(sBobSS);
        joSSAnnual    = num(sJoSS);
        bobSSStart    = (int) num(sBobSSYear);
        joSSStart     = (int) num(sJoSSYear);
        otherIncome   = num(sOther);
        surrenderAnnuity = cbSurrender.isSelected();
        annuityPVNet  = num(sPVNet);
        recalc();
    }

    void recalc() {
        Yr[] yrs = simulate(convAmts);
        SwingUtilities.invokeLater(() -> paint(yrs));
    }

    Yr[] simulate(double[] convs) {
        Yr[] out = new Yr[YEARS];

        // Starting balances
        double trad = BASE_TRAD_POOL + (surrenderAnnuity ? annuityPVNet : 0);
        double roth = BASE_ROTH_POOL;

        // MAGI history for IRMAA lookback.
        // Index by actual year; we need years 2024+ so offset from 2024.
        // magiByYear[0] = 2024, [1] = 2025, [2] = 2026 ...
        double[] magiByYear = new double[YEARS + 4]; // pre-filled with 0 (no income pre-2026)

        double cumTax = 0;

        for (int i = 0; i < YEARS; i++) {
            int year = PLAN_START + i;
            Yr yr = new Yr();
            yr.year = year;

            // --- Ordinary income this year ---
            double ss = 0;
            if (year >= bobSSStart) ss += bobSSAnnual;
            if (year >= joSSStart)  ss += joSSAnnual;
            double taxableSS = ss * SS_TAX_FRAC;

            double annuityInc = 0;
            if (!surrenderAnnuity && year >= ANNUITY_START_YEAR) {
                annuityInc = (year == ANNUITY_START_YEAR)
                        ? ANNUITY_ANNUAL * (9.0 / 12.0)   // starts April
                        : ANNUITY_ANNUAL;
            }

            double conv = convs[i];
            double magi = taxableSS + annuityInc + conv + otherIncome;
            yr.magi = magi;
            magiByYear[i + 2] = magi;  // index+2 because array[0]=2024

            double taxable = Math.max(0, magi - STD_DED);
            yr.taxableIncome = taxable;
            yr.fedTax    = fedTax(taxable);
            yr.stateTax  = taxable * 0.025;
            yr.topBracket = topBracket(taxable);

            // IRMAA: paid this year based on MAGI from 2 years ago
            double priorMagi = (i >= 2) ? magiByYear[i] : 0;  // magiByYear[i] = year-2
            yr.irmaaTier = irmaaTier(priorMagi);
            yr.irmaaCost = IRMAA_COUPLE_YR[yr.irmaaTier];

            // Net marginal cost of conversion (incremental tax vs no conversion)
            double magiNoConv = taxableSS + annuityInc + otherIncome;
            double taxNoConv  = Math.max(0, magiNoConv - STD_DED);
            yr.netConvCost = (yr.fedTax - fedTax(taxNoConv))
                    + (yr.stateTax - taxNoConv * 0.025);

            // Account growth: grow first, then apply conversion flow
            trad = trad * (1 + GROWTH) - conv;
            roth = roth * (1 + GROWTH) + conv;
            yr.tradBal = trad;
            yr.rothBal = roth;

            cumTax += yr.fedTax + yr.stateTax;
            yr.cumTax = cumTax;
            out[i] = yr;
        }
        return out;
    }

    void paint(Yr[] yrs) {
        double totFed = 0, totState = 0, totIrmaa = 0;

        for (int i = 0; i < YEARS; i++) {
            Yr yr = yrs[i];
            lMagi[i].setText(fmt(yr.magi));
            lFedTax[i].setText(fmt(yr.fedTax));
            lStateTax[i].setText(fmt(yr.stateTax));
            lIrmaaTier[i].setText(yr.irmaaTier == 0 ? "Base" : "Tier " + yr.irmaaTier);
            lIrmaaTier[i].setForeground(irmaaColor(yr.irmaaTier));
            lIrmaaCost[i].setText(yr.irmaaCost == 0 ? "\u2014" : fmt(yr.irmaaCost));
            lTradBal[i].setText(fmt(yr.tradBal));
            lRothBal[i].setText(fmt(yr.rothBal));
            lNetCost[i].setText(yr.netConvCost < 1 ? "\u2014" : fmt(yr.netConvCost));
            lCumTax[i].setText(fmt(yr.cumTax));
            lBracket[i].setText(yr.topBracket);
            lBracket[i].setForeground(bracketColor(yr.topBracket));

            totFed   += yr.fedTax;
            totState += yr.stateTax;
            totIrmaa += yr.irmaaCost;
        }

        double grand = totFed + totState + totIrmaa;
        lSumFedState.setText(fmt(totFed + totState));
        lSumIrmaa.setText(fmt(totIrmaa));
        lSumGrand.setText(fmt(grand));
        lSumTrad.setText(fmt(yrs[YEARS-1].tradBal));
        lSumRoth.setText(fmt(yrs[YEARS-1].rothBal));

        refreshComparison();
    }

    void refreshComparison() {
        compModel.setRowCount(0);
        addCompRow("Custom (current)",  simulate(convAmts));
        addCompRow("No Conversion",     simulate(stratConvs("clear")));
        addCompRow("Fill 22% Top",      simulate(stratConvs("22top")));
        addCompRow("Fill 24% Top",      simulate(stratConvs("24top")));
        addCompRow("IRMAA Tier 1 Edge", simulate(stratConvs("irmaa1")));
        addCompRow("IRMAA Tier 2 Edge", simulate(stratConvs("irmaa2")));
        addCompRow("Flat $150K/yr",     simulate(stratConvs("flat150")));
    }

    void addCompRow(String name, Yr[] yrs) {
        double fed=0, state=0, irmaa=0;
        for (Yr y : yrs) { fed+=y.fedTax; state+=y.stateTax; irmaa+=y.irmaaCost; }
        double grand = fed + state + irmaa;
        double finalRoth = yrs[YEARS-1].rothBal;
        double finalTrad = yrs[YEARS-1].tradBal;
        double rothPct   = finalRoth / (finalRoth + finalTrad) * 100;
        compModel.addRow(new Object[]{
                name, fmt(fed), fmt(state), fmt(irmaa), fmt(grand),
                fmt(finalRoth), fmt(finalTrad), String.format("%.1f%%", rothPct)
        });
    }

    // =========================================================================
    //  STRATEGY COMPUTATION
    // =========================================================================

    double[] stratConvs(String strat) {
        if ("clear".equals(strat)) return new double[YEARS];
        double[] c = new double[YEARS];
        for (int i = 0; i < YEARS; i++) {
            int year = PLAN_START + i;
            double ss = 0;
            if (year >= bobSSStart) ss += bobSSAnnual;
            if (year >= joSSStart)  ss += joSSAnnual;
            double taxSS = ss * SS_TAX_FRAC;
            double ann = (!surrenderAnnuity && year >= ANNUITY_START_YEAR)
                    ? (year == ANNUITY_START_YEAR ? ANNUITY_ANNUAL*9/12 : ANNUITY_ANNUAL) : 0;
            double baseOrd = taxSS + ann + otherIncome;
            double baseTax = Math.max(0, baseOrd - STD_DED);

            switch (strat) {
                case "22top"    -> c[i] = Math.max(0, 201_050 - baseTax);
                case "24top"    -> c[i] = Math.max(0, 383_900 - baseTax);
                case "irmaa1"   -> {
                    // Keep MAGI <= 272,999 (just under tier 2)
                    double maxConv = Math.max(0, 272_999 - baseOrd);
                    c[i] = maxConv;
                }
                case "irmaa2"   -> {
                    // Keep MAGI <= 345,999 (just under tier 3)
                    double maxConv = Math.max(0, 345_999 - baseOrd);
                    c[i] = maxConv;
                }
                case "flat150"  -> c[i] = 150_000;
                default         -> c[i] = 0;
            }
        }
        return c;
    }

    void applyStrategy(String strat) {
        double[] c = stratConvs(strat);
        for (int i = 0; i < YEARS; i++) {
            convAmts[i] = c[i];
            sConv[i].setValue((int)(Math.round(c[i] / 1000.0) * 1000));
        }
        recalc();
    }

    // =========================================================================
    //  TAX MATH
    // =========================================================================

    static double fedTax(double taxable) {
        double tax = 0;
        for (double[] b : BRACKETS) {
            if (taxable <= b[0]) break;
            tax += (Math.min(taxable, b[1]) - b[0]) * b[2];
        }
        return tax;
    }

    static int irmaaTier(double magi) {
        for (int t = 0; t < IRMAA_THRESH.length; t++)
            if (magi <= IRMAA_THRESH[t]) return t;
        return 4;
    }

    static String topBracket(double taxable) {
        String b = "10%";
        for (double[] br : BRACKETS) {
            if (taxable > br[0]) b = (int)(br[2]*100) + "%";
            else break;
        }
        return b;
    }

    // =========================================================================
    //  FORMATTING
    // =========================================================================

    String fmt(double v) {
        if (v == 0) return "\u2014";
        if (Math.abs(v) >= 1_000_000) return String.format("$%.3fM", v/1_000_000);
        return String.format("$%,.0f", v);
    }

    static double num(JSpinner s) {
        return ((Number) s.getValue()).doubleValue();
    }

    // =========================================================================
    //  COMPONENT FACTORIES
    // =========================================================================

    JPanel card() {
        JPanel p = new JPanel();
        p.setBackground(C_CARD);
        p.setBorder(new CompoundBorder(new LineBorder(C_BORDER),
                new EmptyBorder(10, 12, 10, 12)));
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        return p;
    }

    JPanel sectionHead(String text) {
        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        JLabel l = new JLabel(text);
        l.setFont(new Font("Monospaced", Font.BOLD, 11));
        l.setForeground(C_GOLD);
        JSeparator sep = new JSeparator();
        sep.setForeground(C_BORDER);
        p.add(l,   BorderLayout.NORTH);
        p.add(sep, BorderLayout.SOUTH);
        return p;
    }

    Component vgap(int h) {
        return Box.createRigidArea(new Dimension(0, h));
    }

    JLabel dim(String text) {
        JLabel l = new JLabel(text);
        l.setFont(new Font("Monospaced", Font.PLAIN, 12));
        l.setForeground(C_DIM);
        return l;
    }

    JLabel colHdr(String text) {
        JLabel l = new JLabel(text, SwingConstants.CENTER);
        l.setFont(new Font("Monospaced", Font.BOLD, 10));
        l.setForeground(C_GOLD);
        return l;
    }

    JLabel mono(String text) {
        JLabel l = new JLabel(text, SwingConstants.RIGHT);
        l.setFont(new Font("Monospaced", Font.PLAIN, 11));
        l.setForeground(C_TEXT);
        return l;
    }

    JLabel outLabel(Color fg, Color bg) {
        JLabel l = new JLabel("\u2014", SwingConstants.RIGHT);
        l.setFont(new Font("Monospaced", Font.PLAIN, 11));
        l.setForeground(fg);
        l.setBackground(bg);
        l.setOpaque(true);
        l.setPreferredSize(new Dimension(95, 20));
        return l;
    }

    JLabel html(String html) {
        JLabel l = new JLabel("<html>" + html + "</html>");
        l.setFont(new Font("Monospaced", Font.PLAIN, 10));
        return l;
    }

    JSpinner dollarSpin(int init, int min, int max, int step) {
        JSpinner s = new JSpinner(new SpinnerNumberModel(init, min, max, step));
        s.setFont(new Font("Monospaced", Font.PLAIN, 12));
        JSpinner.NumberEditor ed = new JSpinner.NumberEditor(s, "$#,##0");
        s.setEditor(ed);
        ed.getTextField().setBackground(C_BG);
        ed.getTextField().setForeground(C_TEXT);
        ed.getTextField().setCaretColor(C_BLUE);
        return s;
    }

    JSpinner yearSpin(int init, int min, int max) {
        JSpinner s = new JSpinner(new SpinnerNumberModel(init, min, max, 1));
        s.setFont(new Font("Monospaced", Font.PLAIN, 12));
        JSpinner.NumberEditor ed = new JSpinner.NumberEditor(s, "####");
        s.setEditor(ed);
        ed.getTextField().setBackground(C_BG);
        ed.getTextField().setForeground(C_TEXT);
        return s;
    }

    JButton stratBtn(String label, String strat) {
        JButton b = new JButton(label);
        b.setFont(new Font("Monospaced", Font.BOLD, 11));
        b.setBackground(new Color(38, 50, 74));
        b.setForeground(C_GOLD);
        b.setBorder(new CompoundBorder(new LineBorder(C_GOLD_DIM),
                new EmptyBorder(6, 8, 6, 8)));
        b.setFocusPainted(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.addActionListener(e -> applyStrategy(strat));
        b.addMouseListener(new MouseAdapter() {
            public void mouseEntered(MouseEvent e) { b.setBackground(new Color(54, 70, 100)); }
            public void mouseExited (MouseEvent e) { b.setBackground(new Color(38, 50,  74)); }
        });
        return b;
    }

    JTextArea refArea(String text) {
        JTextArea a = new JTextArea(text);
        a.setFont(new Font("Monospaced", Font.PLAIN, 10));
        a.setForeground(C_DIM);
        a.setBackground(C_CARD2);
        a.setEditable(false);
        a.setBorder(new CompoundBorder(new LineBorder(C_BORDER),
                new EmptyBorder(6, 8, 6, 8)));
        return a;
    }

    void styleTable(JTable t) {
        t.setBackground(C_CARD);
        t.setForeground(C_TEXT);
        t.setFont(new Font("Monospaced", Font.PLAIN, 11));
        t.setGridColor(C_BORDER);
        t.setRowHeight(23);
        t.setSelectionBackground(new Color(48, 68, 110));
        t.setSelectionForeground(C_TEXT);
        t.getTableHeader().setBackground(C_PANEL);
        t.getTableHeader().setForeground(C_GOLD);
        t.getTableHeader().setFont(new Font("Monospaced", Font.BOLD, 10));
        t.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            public Component getTableCellRendererComponent(JTable tbl, Object val,
                                                           boolean sel, boolean foc, int row, int col) {
                super.getTableCellRendererComponent(tbl, val, sel, foc, row, col);
                setBackground(sel ? new Color(48,68,110) : (row%2==0 ? C_CARD : C_ROW_ALT));
                Color fg = switch(col) {
                    case 0 -> C_BLUE;
                    case 4 -> C_RED;
                    case 5 -> C_GREEN;
                    case 7 -> C_DIM;
                    default -> C_TEXT;
                };
                setForeground(fg);
                setFont(new Font("Monospaced", Font.PLAIN, 11));
                setBorder(new EmptyBorder(0, 6, 0, 6));
                if (row == 0) setFont(new Font("Monospaced", Font.BOLD, 11));
                return this;
            }
        });
    }

    Color irmaaColor(int tier) {
        return switch(tier) {
            case 0 -> C_GREEN;
            case 1 -> C_AMBER;
            case 2 -> new Color(255, 140, 40);
            default -> C_RED;
        };
    }

    Color bracketColor(String b) {
        return switch(b) {
            case "10%","12%" -> C_GREEN;
            case "22%"       -> C_BLUE;
            case "24%"       -> C_AMBER;
            default          -> C_RED;
        };
    }

    // =========================================================================
    //  MAIN
    // =========================================================================

    public static void main(String[] args) {
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
        catch (Exception ignored) {}
        // Dark defaults
        UIManager.put("ScrollBar.thumb",    new Color(50, 65, 95));
        UIManager.put("ScrollBar.track",    C_PANEL);
        UIManager.put("SplitPane.dividerSize", 5);
        SwingUtilities.invokeLater(RothConversionPlanner::new);
    }
}
