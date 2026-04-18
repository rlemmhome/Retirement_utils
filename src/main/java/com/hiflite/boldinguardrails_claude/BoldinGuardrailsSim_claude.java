package com.hiflite.boldinguardrails_claude;


import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;

/**
     * BoldinGuardrailsSim_claude.java
     *
     * Simulates the Boldin "Guardrails" portfolio withdrawal strategy.
     *
     * DESIGN NOTE — why Annual Spending and Initial WR are linked:
     * ─────────────────────────────────────────────────────────────
     * In the Guyton-Klinger / Boldin guardrails framework the initial
     * withdrawal rate (IWR) IS the single authoritative input.  It is
     * applied to the starting portfolio to derive the Year-1 spending:
     *
     *   Year-1 Annual Spending  =  Portfolio Value  ×  IWR
     *
     * Both fields are shown in the GUI for convenience, but they are
     * mathematically linked — editing one recalculates the other in
     * real time so the user always sees a consistent pair.
     *
     * The IWR field is the "master"; Annual Spending is the "derived
     * display".  A user may also type a dollar amount directly into
     * Annual Spending, which back-calculates the IWR from the current
     * portfolio value.  A live note below both fields always shows the
     * arithmetic so the relationship is transparent.
     *
     * Once Year 1 begins, the IWR is fixed as the guardrail anchor.
     * Each subsequent year the *current* WR = (net withdrawal from
     * portfolio) / (current portfolio value) is compared against:
     *   • Upper guardrail  =  IWR − upperOffset  → spend MORE
     *   • Lower guardrail  =  IWR + lowerOffset  → spend LESS
     * In normal years spending is inflation-adjusted only.
     *
     * Compile:  javac BoldinGuardrailsSim_claude.java
     * Run:      java  BoldinGuardrailsSim_claude
     */
    @SuppressWarnings({"JavadocBlankLines", "UnnecessaryUnicodeEscape", "SpellCheckingInspection"})
    public class BoldinGuardrailsSim_claude extends JFrame {

        // ── Palette ──────────────────────────────────────────────────────────────────
        private static final Color BG_DARK      = new Color(15,  23,  42);
        private static final Color BG_PANEL     = new Color(22,  33,  62);
        private static final Color BG_CARD      = new Color(30,  45,  80);
        private static final Color ACCENT_GOLD  = new Color(251, 191,  36);
        private static final Color ACCENT_TEAL  = new Color( 45, 212, 191);
        private static final Color ACCENT_RED   = new Color(248, 113, 113);
        private static final Color ACCENT_GREEN = new Color(110, 231, 183);
        private static final Color TEXT_PRIMARY = new Color(241, 245, 249);
        private static final Color TEXT_MUTED   = new Color(148, 163, 184);
        private static final Color BORDER_COLOR = new Color( 51,  65, 100);

        // ── Default Input Values (change here to alter GUI startup values) ────────────
        private static final double DEF_PORTFOLIO           = 1_500_000;
        private static final double DEF_IWR                 =       5.0;  // %
        private static final double DEF_UPPER_BAND          =      20.0;   // % of IWR
        private static final double DEF_LOWER_BAND          =      20.0;   // % of IWR
        private static final double DEF_PROSPERITY_ADJ      =      10.0;   // %
        private static final double DEF_CUTBACK_ADJ         =      10.0;   // %
        private static final double DEF_NOMINAL_RETURN      =       6.7;   // %
        private static final double DEF_RETURN_STD_DEV      =     10.89;   // %
        private static final double DEF_INFLATION           =      3.79;   // %
        private static final double DEF_INFLATION_STD_DEV   =      2.73;   // %
        private static final int    DEF_PROJECTION_YEARS    =      30;
        private static final int    DEF_MC_RUNS             =  10_000;
        private static final int    DEF_CURRENT_AGE         =      64;
        private static final double DEF_SS                  =   40400;     // $/yr
        private static final int    DEF_SS_START_AGE        =      65;
        private static final int    DEF_BIRTH_YEAR          =    1961;   // → RMD age 75
        private static final int    DEF_SPOUSE_AGE          =      63;
        private static final double DEF_SPOUSE_SS           =   40520;     // $/yr
        private static final int    DEF_SPOUSE_SS_START_AGE =      65;
        private static final int    DEF_SPOUSE_BIRTH_YEAR   =    1962;   // → RMD age 75
        private static final double DEF_OTHER_INCOME        =   22599;     // $/yr
        private static final int    DEF_OTHER_INCOME_START  =      66;

        // ── Input fields ─────────────────────────────────────────────────────────────
        private JFormattedTextField tfPortfolio, tfAnnualSpend, tfInitialWR;
        private JFormattedTextField tfUpperGuardrail, tfLowerGuardrail;
        private JFormattedTextField tfProsperityAdj,  tfCutbackAdj;
        private JFormattedTextField tfInflation, tfInflationStdDev, tfNominalReturn, tfReturnStdDev;
        private JFormattedTextField tfProjectionYears, tfMonteCarloRuns;
        // Person 1
        private JFormattedTextField tfCurrentAge, tfBirthYear, tfSocialSecurity, tfSSStartAge;
        // Person 2 (spouse)
        private JFormattedTextField tfSpouseAge, tfSpouseBirthYear, tfSpouseSS, tfSpouseSSStartAge;
        // Other income
        private JFormattedTextField tfOtherIncome, tfOtherIncomeStartAge;
        private JCheckBox  cbInflationAdjustSS, cbUseMonteCarloRandom;
        private JComboBox<String> cbReturnModel;
        private JLabel     lblDerivedNote;    // live arithmetic note
        private JLabel     lblGuardrailNote;  // live guardrail threshold readout

        // guard against recursive listener firing
        private boolean updatingLinkedFields = false;

        // ── Run button + progress bar (need instance refs so SwingWorker can update) ──
        private JButton      btnRun;
        private JProgressBar progressBar;

        // ── Real/Nominal toggle and last sim result (for instant re-display) ──────────
        private JToggleButton btnRealDollars;
        private SimResult     lastDetResult;   // most recent deterministic run result

    private DefaultTableModel  tableModel;
        private JLabel lblSuccessRate, lblMedianFinal, lblAvgWithdrawal;
        private JLabel lblMinWithdrawal, lblMaxWithdrawal, lblRuinYear;
        private PortfolioChartPanel chartPanel;
        private JTextArea           txSummary;

        private final DecimalFormat dollarFmt = new DecimalFormat("$#,##0");
        private final DecimalFormat pctFmt    = new DecimalFormat("0.00%");
        private final DecimalFormat pct1Fmt   = new DecimalFormat("0.0%");

        // ── Constructor ───────────────────────────────────────────────────────────────
        public BoldinGuardrailsSim_claude() {
            super("Boldin Guardrails Withdrawal Simulator");
            setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            setSize(1340, 900);
            setMinimumSize(new Dimension(1100, 720));
            setBackground(BG_DARK);

            JPanel root = new JPanel(new BorderLayout());
            root.setBackground(BG_DARK);
            root.add(buildHeader(),      BorderLayout.NORTH);
            root.add(buildMainContent(), BorderLayout.CENTER);
            setContentPane(root);
            setLocationRelativeTo(null);
        }

        // ── Header ────────────────────────────────────────────────────────────────────
        private JPanel buildHeader() {
            JPanel header = new JPanel(new BorderLayout()) {
                @Override protected void paintComponent(Graphics g) {
                    super.paintComponent(g);
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setPaint(new GradientPaint(0,0,new Color(20,30,70),getWidth(),0,BG_DARK));
                    g2.fillRect(0, 0, getWidth(), getHeight());
                    g2.setColor(ACCENT_GOLD);
                    g2.fillRect(0, getHeight()-3, getWidth(), 3);
                    g2.dispose();
                }
            };
            header.setPreferredSize(new Dimension(0, 72));
            header.setBorder(new EmptyBorder(0, 28, 0, 28));

            JLabel title    = new JLabel("BOLDIN GUARDRAILS SIMULATOR");
            title.setFont(new Font("Georgia", Font.BOLD, 22));
            title.setForeground(ACCENT_GOLD);

            JLabel subtitle = new JLabel(
                    "Dynamic Portfolio Withdrawal Strategy  \u00b7  Guyton-Klinger Rules  \u00b7  IWR drives Year-1 Spending");
            subtitle.setFont(new Font("Georgia", Font.ITALIC, 12));
            subtitle.setForeground(TEXT_MUTED);

            JPanel titleBlock = new JPanel();
            titleBlock.setLayout(new BoxLayout(titleBlock, BoxLayout.Y_AXIS));
            titleBlock.setOpaque(false);
            titleBlock.add(Box.createVerticalStrut(12));
            titleBlock.add(title);
            titleBlock.add(subtitle);

            header.add(titleBlock, BorderLayout.WEST);

            btnRun = buildRunButton();

            progressBar = new JProgressBar(0, 100);
            progressBar.setStringPainted(true);
            progressBar.setString("");
            progressBar.setPreferredSize(new Dimension(340, 18));
            progressBar.setBackground(BG_CARD);
            progressBar.setForeground(ACCENT_TEAL);
            progressBar.setVisible(false);

            JPanel btnWrap = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 14));
            btnWrap.setOpaque(false);
            btnWrap.add(progressBar);
            btnWrap.add(btnRun);
            header.add(btnWrap, BorderLayout.EAST);
            return header;
        }

        private JButton buildRunButton() {
            JButton btn = new JButton("\u25b6  RUN SIMULATION") {
                @Override protected void paintComponent(Graphics g) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    Color c = getModel().isPressed() ? ACCENT_GOLD.darker()
                            : getModel().isRollover() ? ACCENT_GOLD.brighter() : ACCENT_GOLD;
                    g2.setColor(c);
                    g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
                    g2.setColor(BG_DARK);
                    g2.setFont(getFont());
                    FontMetrics fm = g2.getFontMetrics();
                    g2.drawString(getText(),
                            (getWidth() - fm.stringWidth(getText())) / 2,
                            (getHeight() + fm.getAscent() - fm.getDescent()) / 2);
                    g2.dispose();
                }
            };
            btn.setFont(new Font("Georgia", Font.BOLD, 13));
            btn.setPreferredSize(new Dimension(195, 38));
            btn.setBorderPainted(false); btn.setContentAreaFilled(false); btn.setFocusPainted(false);
            btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            btn.addActionListener(e -> runSimulation());
            return btn;
        }

        // ── Main split ────────────────────────────────────────────────────────────────
        private JSplitPane buildMainContent() {
            JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                    buildInputPanel(), buildOutputPanel());
            split.setDividerLocation(415);
            split.setDividerSize(6);
            split.setBackground(BG_DARK);
            split.setBorder(null);
            return split;
        }

        // ── Input panel ───────────────────────────────────────────────────────────────
        private JScrollPane buildInputPanel() {
            JPanel panel = new JPanel();
            panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
            panel.setBackground(BG_PANEL);
            panel.setBorder(new EmptyBorder(16, 16, 16, 16));

            panel.add(buildSection("Portfolio & Spending",           buildPortfolioFields()));
            panel.add(Box.createVerticalStrut(10));
            panel.add(buildSection("Guardrail Settings",             buildGuardrailFields()));
            panel.add(Box.createVerticalStrut(10));
            panel.add(buildSection("Return & Inflation Assumptions", buildReturnFields()));
            panel.add(Box.createVerticalStrut(10));
            panel.add(buildSection("Personal & Income",              buildPersonalFields()));
            panel.add(Box.createVerticalStrut(10));
            panel.add(buildSection("Simulation Settings",            buildSimFields()));
            panel.add(Box.createVerticalStrut(16));

            JScrollPane scroll = new JScrollPane(panel);
            scroll.setBorder(null);
            scroll.getViewport().setBackground(BG_PANEL);
            scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
            scroll.setPreferredSize(new Dimension(415, 0));
            return scroll;
        }

        private JPanel buildSection(String title, JPanel content) {
            JPanel section = new JPanel(new BorderLayout()) {
                @Override protected void paintComponent(Graphics g) {
                    super.paintComponent(g);
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(BG_CARD);
                    g2.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
                    g2.setColor(BORDER_COLOR);
                    g2.setStroke(new BasicStroke(1));
                    g2.drawRoundRect(0, 0, getWidth()-1, getHeight()-1, 12, 12);
                    g2.dispose();
                }
            };
            section.setOpaque(false);
            section.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

            JLabel lbl = new JLabel("  " + title);
            lbl.setFont(new Font("Georgia", Font.BOLD, 12));
            lbl.setForeground(ACCENT_TEAL);
            lbl.setBorder(new EmptyBorder(8, 8, 4, 8));
            lbl.setOpaque(false);

            JSeparator sep = new JSeparator();
            sep.setForeground(BORDER_COLOR);

            JPanel top = new JPanel(new BorderLayout());
            top.setOpaque(false);
            top.add(lbl, BorderLayout.NORTH);
            top.add(sep, BorderLayout.SOUTH);

            section.add(top,     BorderLayout.NORTH);
            section.add(content, BorderLayout.CENTER);
            return section;
        }

        // ── Portfolio section with live-linked IWR <-> Spending ───────────────────────
        /**
         * The two linked fields obey exactly one equation:
         *
         *   Annual Spending  =  Portfolio Value  x  (IWR / 100)
         *
         * IWR is the MASTER input (gold star label).
         * Annual Spending is the DERIVED display (teal text).
         *
         * Either field can be edited:
         *   Edit Portfolio or IWR  ->  recalculate Spending
         *   Edit Spending          ->  back-calculate IWR
         *
         * A live note line always shows the arithmetic equation so the
         * user can see they are always consistent.
         */
        private JPanel buildPortfolioFields() {
            JPanel p = new JPanel(new GridBagLayout());
            p.setOpaque(false);
            p.setBorder(new EmptyBorder(10, 12, 14, 12));

            GridBagConstraints lc = new GridBagConstraints();
            lc.anchor  = GridBagConstraints.WEST;
            lc.fill    = GridBagConstraints.HORIZONTAL;
            lc.insets  = new Insets(3, 0, 3, 8);
            lc.gridx   = 0;
            lc.weightx = 0.42;

            GridBagConstraints fc = new GridBagConstraints();
            fc.anchor  = GridBagConstraints.WEST;
            fc.fill    = GridBagConstraints.HORIZONTAL;
            fc.insets  = new Insets(3, 0, 3, 0);
            fc.gridx   = 1;
            fc.weightx = 0.58;

            GridBagConstraints nc = new GridBagConstraints();
            nc.gridx      = 0;
            nc.gridwidth  = 2;
            nc.fill       = GridBagConstraints.HORIZONTAL;
            nc.insets     = new Insets(0, 2, 6, 0);

            // Row 0: Portfolio
            lc.gridy = 0; fc.gridy = 0;
            tfPortfolio = makeField(String.valueOf((long) DEF_PORTFOLIO));
            p.add(label("Portfolio Value ($)"), lc);
            p.add(tfPortfolio, fc);

            // Row 1: IWR (MASTER)
            lc.gridy = 1; fc.gridy = 1;
            tfInitialWR = makeField(String.format("%.2f", DEF_IWR));
            JLabel iwrLbl = new JLabel("<html>Initial W/R %  <font color='#FBBF24'>\u2605 master</font></html>");
            iwrLbl.setFont(new Font("SansSerif", Font.PLAIN, 11));
            iwrLbl.setForeground(TEXT_MUTED);
            addTooltip(tfInitialWR,
                    "<html><b>Initial Withdrawal Rate (IWR)</b><br>"
                            + "The single authoritative input.<br>"
                            + "Year-1 Spending = Portfolio x IWR.<br>"
                            + "This rate is fixed as the guardrail anchor.</html>");
            p.add(iwrLbl,    lc);
            p.add(tfInitialWR, fc);

            // Row 2: Derived Spending
            lc.gridy = 2; fc.gridy = 2;
            tfAnnualSpend = makeField(String.valueOf((long)(DEF_PORTFOLIO * DEF_IWR / 100.0)));
            tfAnnualSpend.setForeground(ACCENT_TEAL);
            JLabel spendLbl = new JLabel(
                    "<html>Year-1 Portfolio Draw ($)  <font color='#2DD4BF'>derived</font></html>");
            spendLbl.setFont(new Font("SansSerif", Font.PLAIN, 11));
            spendLbl.setForeground(TEXT_MUTED);
            addTooltip(tfAnnualSpend,
                    "<html><b>Year-1 Portfolio Withdrawal</b><br>"
                            + "= Portfolio x IWR.<br>"
                            + "<b>This is NOT total spending power.</b><br>"
                            + "Total spending = this amount + SS + Other Income.<br>"
                            + "Edit here to back-calculate IWR.<br>"
                            + "Both stay in sync at all times.</html>");
            p.add(spendLbl,    lc);
            p.add(tfAnnualSpend, fc);

            // Row 3: Live note
            nc.gridy = 3;
            lblDerivedNote = new JLabel(" ");
            lblDerivedNote.setFont(new Font("SansSerif", Font.ITALIC, 10));
            lblDerivedNote.setForeground(ACCENT_GOLD);
            p.add(lblDerivedNote, nc);

            // ── Listeners: fire on every keystroke, not just focus-lost ─────────────
            javax.swing.event.DocumentListener iwrOrPfListener =
                    new javax.swing.event.DocumentListener() {
                        public void insertUpdate (javax.swing.event.DocumentEvent e) { recalcSpendingFromIWR(); }
                        public void removeUpdate (javax.swing.event.DocumentEvent e) { recalcSpendingFromIWR(); }
                        public void changedUpdate(javax.swing.event.DocumentEvent e) { recalcSpendingFromIWR(); }
                    };
            tfPortfolio.getDocument().addDocumentListener(iwrOrPfListener);
            tfInitialWR.getDocument().addDocumentListener(iwrOrPfListener);

            tfAnnualSpend.getDocument().addDocumentListener(
                    new javax.swing.event.DocumentListener() {
                        public void insertUpdate (javax.swing.event.DocumentEvent e) { recalcIWRFromSpending(); }
                        public void removeUpdate (javax.swing.event.DocumentEvent e) { recalcIWRFromSpending(); }
                        public void changedUpdate(javax.swing.event.DocumentEvent e) { recalcIWRFromSpending(); }
                    });

            // Initial sync on startup
            SwingUtilities.invokeLater(this::recalcSpendingFromIWR);
            return p;
        }

        /** IWR is master: Spending = Portfolio * IWR/100 */
        private void recalcSpendingFromIWR() {
            if (updatingLinkedFields) return;
            updatingLinkedFields = true;
            try {
                double pf  = parseDouble(tfPortfolio, DEF_PORTFOLIO);
                double iwr = parseDouble(tfInitialWR,  DEF_IWR);
                double sp  = pf * (iwr / 100.0);
                tfAnnualSpend.setValue(String.format("%.0f", sp));
                updateNote(pf, iwr, sp);
            } finally { updatingLinkedFields = false; }
        }

        /** Spending edited: IWR = Spending / Portfolio * 100 */
        private void recalcIWRFromSpending() {
            if (updatingLinkedFields) return;
            updatingLinkedFields = true;
            try {
                double pf  = parseDouble(tfPortfolio,   DEF_PORTFOLIO);
                double sp  = parseDouble(tfAnnualSpend, DEF_PORTFOLIO * DEF_IWR / 100.0);
                double iwr = (pf > 0) ? (sp / pf) * 100.0 : 0;
                tfInitialWR.setValue(String.format("%.2f", iwr));
                updateNote(pf, iwr, sp);
            } finally { updatingLinkedFields = false; }
        }

        private void updateNote(double pf, double iwr, double sp) {
            lblDerivedNote.setText(String.format(
                    "  %s  x  %.2f%%  =  %s",
                    dollarFmt.format(pf), iwr, dollarFmt.format(sp)));
        }

        // ── Other sections ────────────────────────────────────────────────────────────
        private JPanel buildGuardrailFields() {
            JPanel p = new JPanel(new GridLayout(0, 2, 8, 6));
            p.setOpaque(false);
            p.setBorder(new EmptyBorder(10, 12, 14, 12));

            tfUpperGuardrail = addField(p, "Upper Band (% of IWR)", String.valueOf((int) DEF_UPPER_BAND));
            tfLowerGuardrail = addField(p, "Lower Band (% of IWR)", String.valueOf((int) DEF_LOWER_BAND));
            tfProsperityAdj  = addField(p, "Prosperity Increase (%)", String.valueOf((int) DEF_PROSPERITY_ADJ));
            tfCutbackAdj     = addField(p, "Cutback Reduction (%)",   String.valueOf((int) DEF_CUTBACK_ADJ));

            addTooltip(tfUpperGuardrail,
                    "<html><b>Upper Guardrail Band</b> (Guyton-Klinger)<br><br>"
                            + "Expressed as a <b>% of IWR</b>.<br>"
                            + "Upper trigger = IWR × (1 − band%)<br><br>"
                            + "Example: IWR=5%, band=20% → triggers when<br>"
                            + "current WR drops below <b>4.0%</b><br><br>"
                            + "Portfolio has grown strongly → reward yourself<br>"
                            + "with a prosperity spending increase.</html>");
            addTooltip(tfLowerGuardrail,
                    "<html><b>Lower Guardrail Band</b> (Guyton-Klinger)<br><br>"
                            + "Expressed as a <b>% of IWR</b>.<br>"
                            + "Lower trigger = IWR × (1 + band%)<br><br>"
                            + "Example: IWR=5%, band=20% → triggers when<br>"
                            + "current WR rises above <b>6.0%</b><br><br>"
                            + "Portfolio is stressed → cut spending to protect<br>"
                            + "long-term sustainability.</html>");
            addTooltip(tfProsperityAdj,
                    "% to INCREASE annual spending when upper guardrail fires.<br>"
                            + "Guyton-Klinger original: 10%.");
            addTooltip(tfCutbackAdj,
                    "% to CUT annual spending when lower guardrail fires.<br>"
                            + "Guyton-Klinger original: 10%.");

            // Live threshold readout spanning both columns
            GridBagConstraints nc = new GridBagConstraints();
            lblGuardrailNote = new JLabel(" ");
            lblGuardrailNote.setFont(new Font("SansSerif", Font.ITALIC, 10));
            lblGuardrailNote.setForeground(ACCENT_GOLD);
            p.add(new JLabel(""));         // spacer
            p.add(lblGuardrailNote);

            // Attach listeners to IWR, upper band, lower band to keep readout live
            javax.swing.event.DocumentListener gl = new javax.swing.event.DocumentListener() {
                public void insertUpdate (javax.swing.event.DocumentEvent e) { updateGuardrailNote(); }
                public void removeUpdate (javax.swing.event.DocumentEvent e) { updateGuardrailNote(); }
                public void changedUpdate(javax.swing.event.DocumentEvent e) { updateGuardrailNote(); }
            };
            tfInitialWR     .getDocument().addDocumentListener(gl);
            tfUpperGuardrail.getDocument().addDocumentListener(gl);
            tfLowerGuardrail.getDocument().addDocumentListener(gl);

            SwingUtilities.invokeLater(this::updateGuardrailNote);
            return p;
        }

        private void updateGuardrailNote() {
            if (lblGuardrailNote == null) return;
            try {
                double iwr   = parseDouble(tfInitialWR,       DEF_IWR);
                double upper = parseDouble(tfUpperGuardrail,  DEF_UPPER_BAND);
                double lower = parseDouble(tfLowerGuardrail,  DEF_LOWER_BAND);
                double upperTrigger = iwr * (1.0 - upper / 100.0);
                double lowerTrigger = iwr * (1.0 + lower / 100.0);
                lblGuardrailNote.setText(String.format(
                        "  Triggers: raise < %.2f%%  |  cut > %.2f%%",
                        upperTrigger, lowerTrigger));
            } catch (Exception ex) {
                lblGuardrailNote.setText(" ");
            }
        }

        private JPanel buildReturnFields() {
            JPanel p = gridPanel();
            tfNominalReturn  = addField(p, "Mean Annual Return (%)",  String.valueOf(DEF_NOMINAL_RETURN));
            tfReturnStdDev   = addField(p, "Return Std Dev (%)",      String.valueOf(DEF_RETURN_STD_DEV));
            tfInflation      = addField(p, "Mean Inflation Rate (%)", String.valueOf(DEF_INFLATION));
            tfInflationStdDev= addField(p, "Inflation Std Dev (%)",   String.valueOf(DEF_INFLATION_STD_DEV));
            addTooltip(tfInflation,       "Mean annual inflation rate. Used every year in Fixed Return mode; used as the mean in MC modes.");
            addTooltip(tfInflationStdDev, "Year-to-year standard deviation of inflation. Only applied in Monte Carlo modes. Set to 0 for fixed inflation.");
            String[] models = {"Fixed Return", "Monte Carlo (Normal)", "Monte Carlo (Log-Normal)"};
            cbReturnModel = new JComboBox<>(models);
            styleCombo(cbReturnModel);
            cbUseMonteCarloRandom = new JCheckBox("Randomize seed each run");
            cbUseMonteCarloRandom.setOpaque(false);
            cbUseMonteCarloRandom.setForeground(TEXT_MUTED);
            cbUseMonteCarloRandom.setFont(new Font("SansSerif", Font.PLAIN, 11));
            cbUseMonteCarloRandom.setSelected(true);
            p.add(label("Return Model")); p.add(cbReturnModel);
            p.add(new JLabel(""));        p.add(cbUseMonteCarloRandom);
            return p;
        }

        private JPanel buildPersonalFields() {
            JPanel p = gridPanel();

            // ── Projection ────────────────────────────────────────────────────────────
            tfCurrentAge      = addField(p, "Your Current Age",    String.valueOf(DEF_CURRENT_AGE));
            tfBirthYear       = addField(p, "Your Birth Year",     String.valueOf(DEF_BIRTH_YEAR));
            tfProjectionYears = addField(p, "Projection Years",    String.valueOf(DEF_PROJECTION_YEARS));
            addTooltip(tfBirthYear,
                    "<html><b>Birth year determines your RMD start age:</b><br>"
                            + "Born 1951–1959 → RMD starts at 73<br>"
                            + "Born 1960 or later → RMD starts at 75 (SECURE 2.0)<br>"
                            + "RMD = portfolio balance ÷ IRS Uniform Lifetime factor.</html>");

            // ── Person 1 SS ───────────────────────────────────────────────────────────
            p.add(label("")); p.add(new JLabel(""));   // spacer row
            JLabel hdr1 = label("── Person 1 Social Security ──");
            hdr1.setForeground(ACCENT_TEAL);
            p.add(hdr1); p.add(new JLabel(""));

            tfSocialSecurity  = addField(p, "SS Benefit ($/yr)",   String.valueOf((long) DEF_SS));
            tfSSStartAge      = addField(p, "SS Start Age",         String.valueOf(DEF_SS_START_AGE));

            // ── Person 2 (Spouse) SS ─────────────────────────────────────────────────
            p.add(label("")); p.add(new JLabel(""));   // spacer row
            JLabel hdr2 = label("── Spouse Social Security ──");
            hdr2.setForeground(ACCENT_TEAL);
            p.add(hdr2); p.add(new JLabel(""));

            tfSpouseAge        = addField(p, "Spouse Current Age",      String.valueOf(DEF_SPOUSE_AGE));
            tfSpouseBirthYear  = addField(p, "Spouse Birth Year",       String.valueOf(DEF_SPOUSE_BIRTH_YEAR));
            tfSpouseSS         = addField(p, "Spouse SS Benefit ($/yr)", String.valueOf((long) DEF_SPOUSE_SS));
            tfSpouseSSStartAge = addField(p, "Spouse SS Start Age",      String.valueOf(DEF_SPOUSE_SS_START_AGE));
            addTooltip(tfSpouseBirthYear,
                    "<html><b>Spouse birth year determines their RMD start age:</b><br>"
                            + "Born 1951–1959 → RMD starts at 73<br>"
                            + "Born 1960 or later → RMD starts at 75 (SECURE 2.0)</html>");
            addTooltip(tfSpouseSS,         "Set to 0 if no spouse or no spousal SS benefit.");
            addTooltip(tfSpouseSSStartAge, "Age at which spouse begins collecting SS.");

            // ── Other Income ─────────────────────────────────────────────────────────
            p.add(label("")); p.add(new JLabel(""));   // spacer row
            JLabel hdr3 = label("── Other Income (Annuity, Pension…) ──");
            hdr3.setForeground(ACCENT_TEAL);
            p.add(hdr3); p.add(new JLabel(""));

            tfOtherIncome         = addField(p, "Other Income ($/yr)",    String.valueOf((long) DEF_OTHER_INCOME));
            tfOtherIncomeStartAge = addField(p, "Other Income Start Age", String.valueOf(DEF_OTHER_INCOME_START));
            addTooltip(tfOtherIncome,         "Annual amount from annuity, pension, rental, or other fixed source.");
            addTooltip(tfOtherIncomeStartAge, "Age at which this income stream begins. Use your current age if it starts immediately.");

            // ── COLA checkbox ─────────────────────────────────────────────────────────
            cbInflationAdjustSS = new JCheckBox("Inflation-adjust SS & Other Income (COLA)");
            cbInflationAdjustSS.setOpaque(false);
            cbInflationAdjustSS.setForeground(TEXT_MUTED);
            cbInflationAdjustSS.setFont(new Font("SansSerif", Font.PLAIN, 11));
            cbInflationAdjustSS.setSelected(true);
            p.add(new JLabel("")); p.add(cbInflationAdjustSS);
            return p;
        }

        private JPanel buildSimFields() {
            JPanel p = gridPanel();
            tfMonteCarloRuns = addField(p, "Monte Carlo Runs", String.valueOf(DEF_MC_RUNS));
            addTooltip(tfMonteCarloRuns, "Number of simulation paths (1 – 1,000,000). At 10,000 runs expect ~20 ms on fast hardware.");
            return p;
        }

        // ── Output panel ──────────────────────────────────────────────────────────────
        private JPanel buildOutputPanel() {
            JPanel panel = new JPanel(new BorderLayout());
            panel.setBackground(BG_DARK);
            panel.add(buildStatsRow(), BorderLayout.NORTH);

            // ── Real / Nominal toggle bar ─────────────────────────────────────────────
            btnRealDollars = new JToggleButton("Show in Today's (2026) Dollars");
            btnRealDollars.setFont(new Font("Georgia", Font.PLAIN, 11));
            btnRealDollars.setForeground(TEXT_MUTED);
            btnRealDollars.setBackground(BG_CARD);
            btnRealDollars.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(BORDER_COLOR, 1, true),
                    new EmptyBorder(4, 14, 4, 14)));
            btnRealDollars.setFocusPainted(false);
            btnRealDollars.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            btnRealDollars.addActionListener(e -> {
                boolean real = btnRealDollars.isSelected();
                btnRealDollars.setText(real
                        ? "Showing: Today's (2026) Dollars  ✓"
                        : "Show in Today's (2026) Dollars");
                btnRealDollars.setForeground(real ? ACCENT_TEAL : TEXT_MUTED);
                if (lastDetResult != null) {
                    populateTable(lastDetResult);           // re-render table instantly
                    chartPanel.setRealDollars(real);        // re-render chart instantly
                }
            });

            JPanel toggleBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 4));
            toggleBar.setBackground(BG_DARK);
            JLabel note = new JLabel("Dollar display:");
            note.setFont(new Font("SansSerif", Font.PLAIN, 11));
            note.setForeground(TEXT_MUTED);
            toggleBar.add(note);
            toggleBar.add(btnRealDollars);

            JTabbedPane tabs = new JTabbedPane();
            tabs.setBackground(BG_DARK); tabs.setForeground(TEXT_PRIMARY);
            tabs.setFont(new Font("Georgia", Font.PLAIN, 12));

            chartPanel = new PortfolioChartPanel();
            tabs.addTab("Portfolio Chart", chartPanel);
            tabs.addTab("Year-by-Year Detail", buildResultTable());

            txSummary = new JTextArea();
            txSummary.setEditable(false);
            txSummary.setBackground(BG_CARD); txSummary.setForeground(TEXT_PRIMARY);
            txSummary.setFont(new Font("Monospaced", Font.PLAIN, 12));
            txSummary.setBorder(new EmptyBorder(12, 14, 12, 14));
            tabs.addTab("Simulation Log", new JScrollPane(txSummary));

            JPanel centerBlock = new JPanel(new BorderLayout());
            centerBlock.setBackground(BG_DARK);
            centerBlock.add(toggleBar, BorderLayout.NORTH);
            centerBlock.add(tabs,      BorderLayout.CENTER);

            panel.add(centerBlock, BorderLayout.CENTER);
            return panel;
        }

        private JPanel buildStatsRow() {
            JPanel row = new JPanel(new GridLayout(1, 6, 8, 0));
            row.setBackground(BG_DARK);
            row.setBorder(new EmptyBorder(10, 10, 6, 10));
            lblSuccessRate   = addStatCard(row, "Success Rate",       "—", ACCENT_GREEN);
            lblMedianFinal   = addStatCard(row, "Median End Value",   "—", ACCENT_TEAL);
            lblAvgWithdrawal = addStatCard(row, "Avg Withdrawal",     "—", ACCENT_GOLD);
            lblMinWithdrawal = addStatCard(row, "Min Withdrawal",     "—", TEXT_MUTED);
            lblMaxWithdrawal = addStatCard(row, "Max Withdrawal",     "—", TEXT_MUTED);
            lblRuinYear      = addStatCard(row, "Ruin Year (median)", "—", ACCENT_RED);
            return row;
        }

        private JLabel addStatCard(JPanel parent, String title, String value, Color vc) {
            JPanel card = new JPanel(new BorderLayout()) {
                @Override protected void paintComponent(Graphics g) {
                    super.paintComponent(g);
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(BG_CARD);
                    g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
                    g2.setColor(BORDER_COLOR);
                    g2.drawRoundRect(0, 0, getWidth()-1, getHeight()-1, 10, 10);
                    g2.dispose();
                }
            };
            card.setOpaque(false);
            card.setBorder(new EmptyBorder(8, 10, 8, 10));
            JLabel lt = new JLabel(title, SwingConstants.CENTER);
            lt.setFont(new Font("SansSerif", Font.PLAIN, 10));
            lt.setForeground(TEXT_MUTED);
            JLabel lv = new JLabel(value, SwingConstants.CENTER);
            lv.setFont(new Font("Georgia", Font.BOLD, 16));
            lv.setForeground(vc);
            card.add(lt, BorderLayout.NORTH);
            card.add(lv, BorderLayout.CENTER);
            parent.add(card);
            return lv;
        }

        private JScrollPane buildResultTable() {
            String[] cols = {"Year","Age","Portfolio","Withdrawal","Total Spending",
                    "RMD","WR%","SS/Other Inc","Return%","Inflation%","Cum. CPI","Guardrail","End Balance"};
            tableModel = new DefaultTableModel(cols, 0) {
                @Override public boolean isCellEditable(int r, int c) { return false; }
            };
            // ── Output widgets ────────────────────────────────────────────────────────────
            JTable resultTable = new JTable(tableModel);
            resultTable.setBackground(BG_CARD); resultTable.setForeground(TEXT_PRIMARY);
            resultTable.setGridColor(BORDER_COLOR);
            resultTable.setFont(new Font("Monospaced", Font.PLAIN, 12));
            resultTable.setRowHeight(22);
            resultTable.getTableHeader().setBackground(BG_PANEL);
            resultTable.getTableHeader().setForeground(ACCENT_TEAL);
            resultTable.getTableHeader().setFont(new Font("Georgia", Font.BOLD, 12));
            resultTable.setSelectionBackground(new Color(51, 65, 100));
            resultTable.setSelectionForeground(TEXT_PRIMARY);
            //                Y    A    Port  WD   TotSpd RMD  WR%  SS   Ret  Inf  CPI  GR   EndBal
            int[] widths = { 45,  45,  105, 100,   110,  100,  58, 100,  62,  68,  68, 130,  105 };
            for (int i = 0; i < widths.length; i++)
                resultTable.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);

            // RMD column (index 5): amber when RMD > planned withdrawal, gold otherwise
            resultTable.getColumnModel().getColumn(5).setCellRenderer(
                    new DefaultTableCellRenderer() {
                        @Override public Component getTableCellRendererComponent(
                                JTable t, Object val, boolean sel, boolean foc, int row, int col) {
                            super.getTableCellRendererComponent(t, val, sel, foc, row, col);
                            setBackground(BG_CARD);
                            String s = val == null ? "" : val.toString();
                            if (s.equals("—")) {
                                setForeground(TEXT_MUTED);
                            } else if (s.startsWith("!")) {
                                // RMD exceeds planned withdrawal — flag in amber
                                setText(s.substring(1));
                                setForeground(new Color(251, 146, 60));   // amber
                                setBackground(new Color(50, 35, 10));
                            } else {
                                setForeground(ACCENT_GOLD);
                            }
                            return this;
                        }
                    });

            // Guardrail column is now index 11
            resultTable.getColumnModel().getColumn(11).setCellRenderer(
                    new DefaultTableCellRenderer() {
                        @Override public Component getTableCellRendererComponent(
                                JTable t, Object val, boolean sel, boolean foc, int row, int col) {
                            super.getTableCellRendererComponent(t, val, sel, foc, row, col);
                            setBackground(BG_CARD); setForeground(TEXT_PRIMARY);
                            String s = val == null ? "" : val.toString();
                            if      (s.contains("INCREASE")) setForeground(ACCENT_GREEN);
                            else if (s.contains("CUTBACK"))  setForeground(ACCENT_RED);
                            else if (s.contains("DEPLETED")) {
                                setForeground(ACCENT_RED);
                                setBackground(new Color(60, 20, 20));
                            }
                            return this;
                        }
                    });
            JScrollPane scroll = new JScrollPane(resultTable);
            scroll.getViewport().setBackground(BG_CARD);
            scroll.setBorder(null);
            return scroll;
        }

        // ── Simulation engine ─────────────────────────────────────────────────────────
        private void runSimulation() {
            // Commit any in-progress edit, then force IWR→Spending sync
            KeyboardFocusManager.getCurrentKeyboardFocusManager().clearFocusOwner();
            recalcSpendingFromIWR();

            // ── Read and validate all inputs on the EDT before handing off ────────────
            final double portfolio;
            final double iwr;
            final double annualSpend;
            final double upperOffset, lowerOffset, prosperityAdj, cutbackAdj;
            final double nominalReturn, returnStdDev, inflation, inflationStdDev;
            final int    years, mcRuns, ssAge, currentAge, birthYear;
            final double ss, otherIncome;
            final int    spouseAge, spouseSSStartAge, otherIncomeStartAge, spouseBirthYear;
            final double spouseSS;
            final boolean inflAdjSS, randomSeed;
            final int    returnModel;

            try {
                portfolio    = parseDouble(tfPortfolio,    DEF_PORTFOLIO);
                iwr          = parseDouble(tfInitialWR,    DEF_IWR) / 100.0;
                annualSpend  = portfolio * iwr;
                upperOffset  = parseDouble(tfUpperGuardrail, DEF_UPPER_BAND)   / 100.0;
                lowerOffset  = parseDouble(tfLowerGuardrail, DEF_LOWER_BAND)   / 100.0;
                prosperityAdj= parseDouble(tfProsperityAdj,  DEF_PROSPERITY_ADJ) / 100.0;
                cutbackAdj   = parseDouble(tfCutbackAdj,     DEF_CUTBACK_ADJ)    / 100.0;
                nominalReturn= parseDouble(tfNominalReturn,  DEF_NOMINAL_RETURN)  / 100.0;
                returnStdDev = parseDouble(tfReturnStdDev,   DEF_RETURN_STD_DEV)  / 100.0;
                inflation    = parseDouble(tfInflation,      DEF_INFLATION)       / 100.0;
                years        = (int) parseDouble(tfProjectionYears, DEF_PROJECTION_YEARS);
                mcRuns       = (int) parseDouble(tfMonteCarloRuns,  DEF_MC_RUNS);
                ss           = parseDouble(tfSocialSecurity,        DEF_SS);
                ssAge        = (int) parseDouble(tfSSStartAge,      DEF_SS_START_AGE);
                currentAge   = (int) parseDouble(tfCurrentAge,      DEF_CURRENT_AGE);
                otherIncome  = parseDouble(tfOtherIncome,           DEF_OTHER_INCOME);
                inflAdjSS    = cbInflationAdjustSS.isSelected();
                returnModel  = cbReturnModel.getSelectedIndex();
                randomSeed   = cbUseMonteCarloRandom.isSelected();

                // New fields
                inflationStdDev    = parseDouble(tfInflationStdDev,      DEF_INFLATION_STD_DEV) / 100.0;
                birthYear          = (int) parseDouble(tfBirthYear,       DEF_BIRTH_YEAR);
                spouseAge          = (int) parseDouble(tfSpouseAge,       DEF_SPOUSE_AGE);
                spouseBirthYear    = (int) parseDouble(tfSpouseBirthYear, DEF_SPOUSE_BIRTH_YEAR);
                spouseSS           = parseDouble(tfSpouseSS,              DEF_SPOUSE_SS);
                spouseSSStartAge   = (int) parseDouble(tfSpouseSSStartAge,DEF_SPOUSE_SS_START_AGE);
                otherIncomeStartAge= (int) parseDouble(tfOtherIncomeStartAge, DEF_OTHER_INCOME_START);

                if (portfolio <= 0)
                    throw new IllegalArgumentException("Portfolio must be positive.");
                if (iwr <= 0 || iwr > 0.20)
                    throw new IllegalArgumentException("Initial W/R must be 0.01%–20%.");
                if (years < 1 || years > 60)
                    throw new IllegalArgumentException("Projection years must be 1–60.");
                if (mcRuns < 1 || mcRuns > 1_000_000)
                    throw new IllegalArgumentException("Monte Carlo runs must be 1–1,000,000.");
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this,
                        "Input error: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }

            // ── Fixed Return: single deterministic run populates table/chart/log ───────
            // ── MC modes: table/log will be updated in done() with the median path ─────
            final boolean displayStochastic = (returnModel != 0);

            if (returnModel == 0) {
                // Fixed return — run once, display immediately, skip MC batch
                SimResult det = runOneSimulation(
                        portfolio, annualSpend, iwr,
                        upperOffset, lowerOffset, prosperityAdj, cutbackAdj,
                        nominalReturn, returnStdDev, inflation, inflationStdDev, years,
                        ss, ssAge, spouseSS, spouseAge, spouseSSStartAge,
                        currentAge, birthYear, otherIncome, otherIncomeStartAge,
                        inflAdjSS, returnModel, false, 42L);

                populateTable(det);
                chartPanel.setData(det);
                txSummary.setText(buildLog(det, currentAge, portfolio, iwr, annualSpend,
                        returnModel, returnStdDev, inflationStdDev));
                lblSuccessRate.setText(det.ruined ? "N/A" : "100.0%");
                lblSuccessRate.setForeground(ACCENT_TEAL);
                lblMedianFinal.setText(dollarFmt.format(det.finalBalance));
                lblAvgWithdrawal.setText(dollarFmt.format(det.avgWithdrawal));
                lblMinWithdrawal.setText(dollarFmt.format(det.avgWithdrawal));
                lblMaxWithdrawal.setText(dollarFmt.format(det.avgWithdrawal));
                lblRuinYear.setText(det.ruined ? "Year " + det.ruinYear : "None");
                progressBar.setVisible(false);
                btnRun.setEnabled(true);
                btnRun.setText("\u25b6  RUN SIMULATION");
                return;
            }

            // ── Stochastic MC batch runs on background thread ─────────────────────────
            btnRun.setEnabled(false);
            btnRun.setText("  Running…");
            progressBar.setValue(0);
            progressBar.setString("0 / " + mcRuns);
            progressBar.setVisible(true);

            // Clear stat cards while computing
            lblSuccessRate.setText("…");
            lblMedianFinal.setText("…");
            lblAvgWithdrawal.setText("…");
            lblMinWithdrawal.setText("…");
            lblMaxWithdrawal.setText("…");
            lblRuinYear.setText("…");

            SwingWorker<int[], Integer> worker = new SwingWorker<>() {

                final List<Double>    finalValues    = new ArrayList<>();
                final List<Double>    avgWithdrawals = new ArrayList<>();
                final List<Integer>   ruinYears      = new ArrayList<>();
                final List<SimResult> allResults     = new ArrayList<>();
                int      successCount = 0;
                long     elapsedMs    = 0;
                double[][] allFanPaths = new double[mcRuns][];

                @Override
                protected int[] doInBackground() {
                    long startNs    = System.nanoTime();
                    long masterSeed = randomSeed ? System.nanoTime() : 12345L;
                    java.util.Random rng = new java.util.Random(masterSeed);
                    int reportEvery = Math.max(1, mcRuns / 200);

                    for (int run = 0; run < mcRuns; run++) {
                        SimResult r = runOneSimulation(
                                portfolio, annualSpend, iwr,
                                upperOffset, lowerOffset, prosperityAdj, cutbackAdj,
                                nominalReturn, returnStdDev, inflation, inflationStdDev, years,
                                ss, ssAge, spouseSS, spouseAge, spouseSSStartAge,
                                currentAge, birthYear, otherIncome, otherIncomeStartAge,
                                inflAdjSS, returnModel, true, rng.nextLong());

                        allResults.add(r);
                        allFanPaths[run] = r.portfolioAfter;

                        if (!r.ruined) { successCount++; finalValues.add(r.finalBalance); }
                        else           { ruinYears.add(r.ruinYear); }
                        avgWithdrawals.add(r.avgWithdrawal);

                        if (run % reportEvery == 0) publish(run + 1);
                    }
                    publish(mcRuns);
                    elapsedMs = (System.nanoTime() - startNs) / 1_000_000;
                    return null;
                }

                @Override
                protected void process(List<Integer> chunks) {
                    int latest = chunks.get(chunks.size() - 1);
                    int pct = (int)(100.0 * latest / mcRuns);
                    progressBar.setValue(pct);
                    progressBar.setString(latest + " / " + mcRuns);
                }

                @Override
                protected void done() {
                    progressBar.setValue(100);
                    String modelLabel = returnModel == 1 ? "Normal" : "Log-Normal";
                    progressBar.setString(String.format("Done — %,d runs (%s) in %,d ms",
                            mcRuns, modelLabel, elapsedMs));

                    // ── Find median path by final balance ─────────────────────────────
                    // Sort all results by finalBalance, pick the middle one.
                    // Ruined paths have finalBalance=0; they naturally sort to the bottom.
                    List<SimResult> sorted = new ArrayList<>(allResults);
                    sorted.sort(java.util.Comparator.comparingDouble(r -> r.finalBalance));
                    SimResult medianRun = sorted.get(sorted.size() / 2);

                    // Update table, chart highlight, and log with the true median path
                    populateTable(medianRun);
                    chartPanel.setData(medianRun);   // sets the bright highlighted line
                    txSummary.setText(buildLog(medianRun, currentAge, portfolio, iwr,
                            annualSpend, returnModel, returnStdDev, inflationStdDev));

                    // Send all fan paths to chart for the background fan rendering
                    chartPanel.setFanData(allFanPaths, mcRuns);

                    // ── Stat cards ────────────────────────────────────────────────────
                    double successRate = (double) successCount / mcRuns;
                    double medFinal    = finalValues.isEmpty() ? 0 : median(finalValues);
                    double avgWD       = median(avgWithdrawals);
                    double minWD       = avgWithdrawals.stream().mapToDouble(d->d).min().orElse(0);
                    double maxWD       = avgWithdrawals.stream().mapToDouble(d->d).max().orElse(0);
                    String ruinStr     = ruinYears.isEmpty() ? "None"
                            : "Year " + (int) median(
                            ruinYears.stream().mapToDouble(i->(double)i)
                            .boxed().collect(java.util.stream.Collectors.toList()));

                    lblSuccessRate.setText(pct1Fmt.format(successRate));
                    lblSuccessRate.setForeground(successRate >= 0.85 ? ACCENT_GREEN
                            : successRate >= 0.70 ? ACCENT_GOLD : ACCENT_RED);
                    lblMedianFinal.setText(successCount == 0 ? "$0" : dollarFmt.format(medFinal));
                    lblAvgWithdrawal.setText(dollarFmt.format(avgWD));
                    lblMinWithdrawal.setText(dollarFmt.format(minWD));
                    lblMaxWithdrawal.setText(dollarFmt.format(maxWD));
                    lblRuinYear.setText(ruinStr);

                    btnRun.setEnabled(true);
                    btnRun.setText("\u25b6  RUN SIMULATION");

                    javax.swing.Timer hideTimer = new javax.swing.Timer(1500,
                            e -> progressBar.setVisible(false));
                    hideTimer.setRepeats(false);
                    hideTimer.start();
                }
            };

            worker.execute();
        }

        /**
         * Run one complete simulation.
         *
         * @param annualSpend  Year-1 gross spending  =  portfolio * iwr  (always)
         * @param iwr          Initial withdrawal rate — fixed guardrail anchor
         */
        private SimResult runOneSimulation(
                double portfolio,   double annualSpend, double iwr,
                double upperOffset, double lowerOffset,
                double prosperityAdj, double cutbackAdj,
                double nominalReturn, double returnStdDev,
                double inflation,   double inflationStdDev, int years,
                double ss,    int ssAge,
                double spouseSS, int spouseAge, int spouseSSStartAge,
                int currentAge, int birthYear,
                double otherIncome, int otherIncomeStartAge,
                boolean inflAdjSS, int returnModel,
                boolean stochastic, long seed) {

            java.util.Random rng = new java.util.Random(seed);
            SimResult res = new SimResult(years);

            double balance    = portfolio;
            double withdrawal = annualSpend;
            double ss1Amount  = 0;
            double ss2Amount  = 0;
            double cumulCPI   = 1.0;

            for (int yr = 0; yr < years; yr++) {
                int age1 = currentAge + yr;
                int age2 = spouseAge  + yr;
                res.ages[yr] = age1;

                // ── Stochastic inflation draw ─────────────────────────────────────────
                double inflRate = inflation;
                if (stochastic && inflationStdDev > 0)
                    inflRate = Math.max(-0.05,  // floor at -5% deflation
                            inflation + rng.nextGaussian() * inflationStdDev);

                // ── Social Security — Person 1 ────────────────────────────────────────
                if (age1 >= ssAge)
                    ss1Amount = inflAdjSS ? ss * cumulCPI : ss;

                // ── Social Security — Spouse ──────────────────────────────────────────
                if (spouseSS > 0 && age2 >= spouseSSStartAge)
                    ss2Amount = inflAdjSS ? spouseSS * cumulCPI : spouseSS;

                // ── Other Income (annuity/pension — deferred start) ───────────────────
                double otherAmt = 0;
                if (age1 >= otherIncomeStartAge)
                    otherAmt = inflAdjSS ? otherIncome * cumulCPI : otherIncome;

                double incomeFromSources = ss1Amount + ss2Amount + otherAmt;

                // Net portfolio draw after external income offsets spending target
                double netWithdrawal = Math.max(0, withdrawal - incomeFromSources);

                // ── Portfolio already depleted ────────────────────────────────────────
                if (balance <= 0) {
                    res.portfolioBefore[yr] = 0;
                    res.withdrawals[yr]     = 0;
                    res.wRates[yr]          = 0;
                    res.guardrails[yr]      = "DEPLETED";
                    res.returns[yr]         = 0;
                    res.portfolioAfter[yr]  = 0;
                    res.incomes[yr]         = incomeFromSources;
                    res.inflRates[yr]       = inflRate;
                    if (!res.ruined) { res.ruined = true; res.ruinYear = yr + 1; }
                    continue;
                }

                res.portfolioBefore[yr] = balance;

                // ── Current withdrawal rate: GROSS spending / portfolio ───────────────
                // IMPORTANT: guardrails compare the gross spending rate against IWR,
                // NOT the net portfolio draw after income offsets.  Using net draw
                // causes WR%=0 whenever SS covers all spending, which falsely fires
                // the upper guardrail every year.
                double currentWR = withdrawal / balance;

                // ── Guardrail evaluation ──────────────────────────────────────────────
                String guardrailEvent;
                if (yr == 0) {
                    // Year 1: no prior balance to compare; simply establish baseline
                    guardrailEvent = "Year 1 (baseline)";
                } else {
                    // Guyton-Klinger relative bands:
                    //   Upper trigger = IWR × (1 − upperBand)  e.g. 5% × 0.80 = 4.0%
                    //   Lower trigger = IWR × (1 + lowerBand)  e.g. 5% × 1.20 = 6.0%
                    double upperThreshold = iwr * (1.0 - upperOffset);
                    double lowerThreshold = iwr * (1.0 + lowerOffset);

                    if (currentWR < upperThreshold) {
                        // Portfolio outperformed -> increase spending
                        withdrawal    *= (1 + prosperityAdj);
                        netWithdrawal  = Math.max(0, withdrawal - incomeFromSources);
                        guardrailEvent = "INCREASE +" + (int)(prosperityAdj * 100) + "%";
                    } else if (currentWR > lowerThreshold) {
                        // Portfolio underperformed -> cut spending
                        withdrawal    *= (1 - cutbackAdj);
                        netWithdrawal  = Math.max(0, withdrawal - incomeFromSources);
                        guardrailEvent = "CUTBACK -" + (int)(cutbackAdj * 100) + "%";
                    } else {
                        // Normal year: maintain real spending via (stochastic) inflation adjustment
                        withdrawal    *= (1 + inflRate);
                        netWithdrawal  = Math.max(0, withdrawal - incomeFromSources);
                        guardrailEvent = "Inflation adj";
                    }
                }

                res.withdrawals[yr]    = withdrawal;
                res.wRates[yr]         = currentWR;
                res.guardrails[yr]     = guardrailEvent;
                res.incomes[yr]        = incomeFromSources;
                res.inflRates[yr]      = inflRate;
                res.totalSpending[yr]  = withdrawal + incomeFromSources;

                // ── RMD: IRS requires minimum withdrawal from tax-deferred accounts ────
                // RMD = prior year-end balance / IRS Uniform Lifetime factor.
                // We use portfolioBefore (start-of-year balance) as the basis.
                // If RMD > planned withdrawal the retiree must take the larger amount.
                // We record the RMD for display but do NOT force it in the simulation
                // (the user decides whether to model it — flagged visually in the table).
                double rmd = (balance > 0)
                        ? SimResult.rmdFactor(res.ages[yr], birthYear) > 0
                          ? res.portfolioBefore[yr] / SimResult.rmdFactor(res.ages[yr], birthYear)
                          : 0
                        : 0;
                res.rmdAmounts[yr] = rmd;

                // ── Deduct net portfolio withdrawal ───────────────────────────────────
                balance -= netWithdrawal;
                if (balance < 0) balance = 0;

                // ── Apply annual return ───────────────────────────────────────────────
                double annualRet = nominalReturn;
                if (stochastic) {
                    switch (returnModel) {
                        case 1 ->   // Monte Carlo Normal
                                annualRet = nominalReturn + rng.nextGaussian() * returnStdDev;
                        case 2 ->   // Monte Carlo Log-Normal
                                annualRet = Math.exp(
                                        Math.log(1 + nominalReturn)
                                                - 0.5 * returnStdDev * returnStdDev
                                                + rng.nextGaussian() * returnStdDev) - 1;
                        default -> { /* fixed return, no change */ }
                    }
                }
                res.returns[yr] = annualRet;
                balance = Math.max(0, balance * (1 + annualRet));
                res.portfolioAfter[yr] = balance;

                res.cumulCPI[yr] = cumulCPI;
                cumulCPI *= (1 + inflRate);
            }

            res.finalBalance = res.portfolioAfter[years - 1];
            double sumWD = 0; int cnt = 0;
            for (double w : res.withdrawals) { if (w > 0) { sumWD += w; cnt++; } }
            res.avgWithdrawal = cnt > 0 ? sumWD / cnt : 0;
            return res;
        }

        // ── Table population ──────────────────────────────────────────────────────────
        private void populateTable(SimResult r) {
            lastDetResult = r;
            boolean real = (btnRealDollars != null && btnRealDollars.isSelected());
            DecimalFormat cpiFmt = new DecimalFormat("0.000");
            tableModel.setRowCount(0);
            for (int yr = 0; yr < r.ages.length; yr++) {
                double cpi = r.cumulCPI[yr];
                double d   = real ? cpi : 1.0;

                // RMD cell: "—" if none due, prefix "!" if RMD > planned withdrawal (amber flag)
                String rmdCell;
                if (r.rmdAmounts[yr] <= 0) {
                    rmdCell = "—";
                } else {
                    String fmt = dollarFmt.format(r.rmdAmounts[yr] / d);
                    rmdCell = (r.rmdAmounts[yr] > r.withdrawals[yr]) ? "!" + fmt : fmt;
                }

                tableModel.addRow(new Object[]{
                        yr + 1,
                        r.ages[yr],
                        dollarFmt.format(r.portfolioBefore[yr]  / d),
                        dollarFmt.format(r.withdrawals[yr]      / d),
                        dollarFmt.format(r.totalSpending[yr]    / d),
                        rmdCell,
                        pctFmt.format(r.wRates[yr]),
                        dollarFmt.format(r.incomes[yr]          / d),
                        pctFmt.format(r.returns[yr]),
                        pctFmt.format(r.inflRates[yr]),
                        cpiFmt.format(cpi),
                        r.guardrails[yr],
                        dollarFmt.format(r.portfolioAfter[yr]   / d)
                });
            }
        }

        // ── Simulation log ────────────────────────────────────────────────────────────
        private String buildLog(SimResult r, int currentAge,
                                double portfolio, double iwr, double annualSpend,
                                int returnModel, double returnStdDev, double inflationStdDev) {
            String[] modelNames = {"Fixed Return (deterministic)",
                    "Monte Carlo – Normal distribution (MEDIAN PATH)",
                    "Monte Carlo – Log-Normal distribution (MEDIAN PATH)"};
            String modelDesc = returnModel < modelNames.length ? modelNames[returnModel] : "Unknown";
            StringBuilder sb = new StringBuilder();
            sb.append("=== BOLDIN GUARDRAILS SIMULATION LOG ===\n");
            sb.append("    Return model   : ").append(modelDesc).append("\n");
            if (returnModel != 0) {
                sb.append(String.format("    Return Std Dev : %.1f%%%n", returnStdDev));
                sb.append(String.format("    Infl. Std Dev  : %.1f%%%n", inflationStdDev));
            }
            sb.append("\nINPUT CONSISTENCY CHECK\n");
            sb.append("  Starting Portfolio  : ").append(dollarFmt.format(portfolio)).append("\n");
            sb.append(String.format("  Initial W/R (IWR)   : %.2f%%%n", iwr * 100));
            sb.append(String.format("  Year-1 Spending     : %s  (= %s x %.2f%%)%n",
                    dollarFmt.format(annualSpend), dollarFmt.format(portfolio), iwr * 100));
            sb.append(String.format("  Verification        : %.2f%% of %s = %s%n%n",
                    iwr * 100, dollarFmt.format(portfolio), dollarFmt.format(portfolio * iwr)));

            sb.append(String.format("%-6s %-5s %-14s %-13s %-13s %-10s %-8s %-14s %-10s %-10s %-10s %-22s %-14s%n",
                    "Year","Age","Portfolio","Withdrawal","TotSpending","RMD","CurWR%",
                    "SS+Other","Return%","Infl%","CumCPI","Guardrail","End Balance"));
            sb.append("-".repeat(155)).append("\n");
            for (int yr = 0; yr < r.ages.length; yr++) {
                String rmdStr = r.rmdAmounts[yr] <= 0 ? "—"
                        : (r.rmdAmounts[yr] > r.withdrawals[yr] ? "!" : "")
                          + dollarFmt.format(r.rmdAmounts[yr]);
                sb.append(String.format("%-6d %-5d %-14s %-13s %-13s %-10s %-8s %-14s %-10s %-10s %-10s %-22s %-14s%n",
                        yr + 1, r.ages[yr],
                        dollarFmt.format(r.portfolioBefore[yr]),
                        dollarFmt.format(r.withdrawals[yr]),
                        dollarFmt.format(r.totalSpending[yr]),
                        rmdStr,
                        pctFmt.format(r.wRates[yr]),
                        dollarFmt.format(r.incomes[yr]),
                        pctFmt.format(r.returns[yr]),
                        pctFmt.format(r.inflRates[yr]),
                        String.format("%.3f", r.cumulCPI[yr]),
                        r.guardrails[yr],
                        dollarFmt.format(r.portfolioAfter[yr])));
            }
            sb.append("\n").append("=".repeat(112)).append("\n");
            sb.append(r.ruined
                    ? "PORTFOLIO DEPLETED at Year " + r.ruinYear + ".\n"
                    : "Portfolio survived the full projection period.\n");
            sb.append(String.format("  Final Balance   : %s%n", dollarFmt.format(r.finalBalance)));
            sb.append(String.format("  Avg Withdrawal  : %s%n", dollarFmt.format(r.avgWithdrawal)));
            return sb.toString();
        }

        // ── Helpers ───────────────────────────────────────────────────────────────────
        private double median(List<Double> list) {
            if (list.isEmpty()) return 0;
            List<Double> s = new ArrayList<>(list);
            java.util.Collections.sort(s);
            int m = s.size() / 2;
            return s.size() % 2 == 0 ? (s.get(m-1) + s.get(m)) / 2.0 : s.get(m);
        }

        private JPanel gridPanel() {
            JPanel p = new JPanel(new GridLayout(0, 2, 8, 6));
            p.setOpaque(false);
            p.setBorder(new EmptyBorder(10, 12, 12, 12));
            return p;
        }

        private JFormattedTextField addField(JPanel p, String lbl, String def) {
            p.add(label(lbl));
            JFormattedTextField tf = makeField(def);
            p.add(tf);
            return tf;
        }

        private JFormattedTextField makeField(String def) {
            JFormattedTextField tf = new JFormattedTextField();
            tf.setValue(def);
            tf.setBackground(new Color(20, 30, 58));
            tf.setForeground(TEXT_PRIMARY);
            tf.setCaretColor(ACCENT_GOLD);
            tf.setFont(new Font("Monospaced", Font.PLAIN, 13));
            tf.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(BORDER_COLOR, 1, true),
                    new EmptyBorder(3, 7, 3, 7)));
            tf.setHorizontalAlignment(JTextField.RIGHT);
            return tf;
        }

        private JLabel label(String text) {
            JLabel l = new JLabel(text);
            l.setFont(new Font("SansSerif", Font.PLAIN, 11));
            l.setForeground(TEXT_MUTED);
            return l;
        }

        private void styleCombo(JComboBox<?> cb) {
            cb.setBackground(new Color(20, 30, 58));
            cb.setForeground(TEXT_PRIMARY);
            cb.setFont(new Font("SansSerif", Font.PLAIN, 11));
        }

        private void addTooltip(JComponent c, String html) {
            c.setToolTipText("<html><body style='width:200px'>" + html + "</body></html>");
        }

        private double parseDouble(JFormattedTextField tf, double def) {
            try {
                return Double.parseDouble(tf.getText().replaceAll("[^\\d.\\-]", ""));
            } catch (Exception e) { return def; }
        }

        // ── SimResult ─────────────────────────────────────────────────────────────────
        static class SimResult {
            int[]    ages;
            double[] portfolioBefore, withdrawals, wRates, returns, portfolioAfter, incomes;
            double[] cumulCPI;          // cumulative inflation factor (1.0 = base year)
            double[] inflRates;         // actual inflation rate used each year
            double[] totalSpending;     // withdrawals[yr] + incomes[yr]  (gross spending power)
            double[] rmdAmounts;        // IRS RMD required for this year (0 if age < 73)
            String[] guardrails;
            double   finalBalance, avgWithdrawal;
            boolean  ruined;
            int      ruinYear;

            SimResult(int n) {
                ages            = new int[n];
                portfolioBefore = new double[n];
                withdrawals     = new double[n];
                wRates          = new double[n];
                returns         = new double[n];
                portfolioAfter  = new double[n];
                incomes         = new double[n];
                cumulCPI        = new double[n];
                inflRates       = new double[n];
                totalSpending   = new double[n];
                rmdAmounts      = new double[n];
                guardrails      = new String[n];
                java.util.Arrays.fill(guardrails, "—");
                java.util.Arrays.fill(cumulCPI,    1.0);
            }

            /**
             * IRS Uniform Lifetime Table (Publication 590-B).
             * Returns the life expectancy factor for a given age.
             * Used to compute RMD = account balance / factor.
             *
             * RMD start age (SECURE 2.0):
             *   Born 1950 or earlier : age 72 (pre-SECURE)
             *   Born 1951–1959       : age 73
             *   Born 1960 or later   : age 75
             *
             * Returns 0 if age is below the applicable RMD start age.
             * Ages above 115 use factor 1.9.
             */
            static int rmdStartAge(int birthYear) {
                if (birthYear <= 1950) return 72;
                if (birthYear <= 1959) return 73;
                return 75;  // Born 1960 or later — SECURE 2.0
            }

            static double rmdFactor(int age, int birthYear) {
                int startAge = rmdStartAge(birthYear);
                if (age < startAge) return 0;
                // IRS Uniform Lifetime Table — indexed from age 72
                final double[] factors = {
                        27.4, 26.5, 25.5, 24.6, 23.7, 22.9, 22.0, 21.1, 20.2, 19.4, // 72–81
                        18.5, 17.7, 16.8, 16.0, 15.2, 14.4, 13.7, 12.9, 12.2, 11.5, // 82–91
                        10.8, 10.1,  9.5,  8.9,  8.4,  7.8,  7.3,  6.8,  6.4,  6.0, // 92–101
                        5.6,  5.2,  4.9,  4.6,  4.3,  4.1,  3.9,  3.7,  3.5,  3.4, // 102–111
                        3.3,  3.1,  3.0,  2.9                                         // 112–115
                };
                int idx = age - 72;
                if (idx >= factors.length) return 1.9;
                return factors[idx];
            }
        }

        // ── Chart panel ───────────────────────────────────────────────────────────────
        static class PortfolioChartPanel extends JPanel {
            private SimResult    data;
            private double[][]   fanPaths;     // [pathIndex][year] = portfolioAfter
            private int          fanCount;     // how many fan paths stored
            private boolean      hasFan;
            private boolean      realDollars = false;

            void setRealDollars(boolean real) { this.realDollars = real; repaint(); }

            PortfolioChartPanel() { setBackground(BG_DARK); }

            void setData(SimResult r) {
                this.data   = r;
                this.hasFan = false;
                repaint();
            }

            /**
             * Called by the SwingWorker when the MC batch finishes.
             * We cap the fan at 2000 rendered paths regardless of how many were run —
             * beyond that the visual becomes solid paint and individual paths are
             * indistinguishable. We evenly subsample from the full run set.
             */
            void setFanData(double[][] allPaths, int totalRuns) {
                int maxFanLines = 2000;
                if (totalRuns <= maxFanLines) {
                    fanPaths = allPaths;
                    fanCount = totalRuns;
                } else {
                    // subsample evenly
                    fanPaths = new double[maxFanLines][];
                    double step = (double) totalRuns / maxFanLines;
                    for (int i = 0; i < maxFanLines; i++)
                        fanPaths[i] = allPaths[(int)(i * step)];
                    fanCount = maxFanLines;
                }
                hasFan = true;
                repaint();
            }

            @Override protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                if (data == null) { drawPlaceholder(g); return; }
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                int W = getWidth(), H = getHeight();
                int padL=90, padR=30, padT=34, padB=52;
                int cW = W-padL-padR, cH = H-padT-padB;
                int n = data.portfolioAfter.length;

                // ── Build deflated display arrays ─────────────────────────────────────
                double[] dispAfter  = new double[n];
                double[] dispBefore = new double[n];
                double[] dispWD     = new double[n];
                for (int i = 0; i < n; i++) {
                    double d = realDollars ? data.cumulCPI[i] : 1.0;
                    dispAfter [i] = data.portfolioAfter [i] / d;
                    dispBefore[i] = data.portfolioBefore[i] / d;
                    dispWD    [i] = data.withdrawals    [i] / d;
                }

                // ── Determine Y axis max ──────────────────────────────────────────────
                double maxPF = 1;
                for (double v : dispBefore) maxPF = Math.max(maxPF, v);
                for (double v : dispAfter)  maxPF = Math.max(maxPF, v);
                if (hasFan) {
                    for (double[] path : fanPaths)
                        if (path != null)
                            for (int i = 0; i < path.length && i < n; i++) {
                                double d = realDollars ? data.cumulCPI[i] : 1.0;
                                maxPF = Math.max(maxPF, path[i] / d);
                            }
                }
                // Round up to a clean number
                double scale = Math.pow(10, Math.floor(Math.log10(maxPF)));
                maxPF = Math.ceil(maxPF / scale) * scale;

                // ── Background card ───────────────────────────────────────────────────
                g2.setColor(BG_CARD);
                g2.fillRoundRect(2, 2, W-4, H-4, 12, 12);

                // ── Grid lines ────────────────────────────────────────────────────────
                g2.setColor(new Color(40, 55, 85));
                g2.setStroke(new BasicStroke(0.5f, BasicStroke.CAP_BUTT,
                        BasicStroke.JOIN_MITER, 1f, new float[]{4,4}, 0));
                for (int gl = 0; gl <= 6; gl++) {
                    int y = padT + (int)((1.0 - (double)gl/6) * cH);
                    g2.drawLine(padL, y, padL+cW, y);
                    g2.setColor(TEXT_MUTED);
                    g2.setFont(new Font("SansSerif", Font.PLAIN, 10));
                    String lbl = fmt(maxPF * gl / 6);
                    g2.drawString(lbl, padL - g2.getFontMetrics().stringWidth(lbl) - 5, y+4);
                    g2.setColor(new Color(40, 55, 85));
                }

                // ── X labels ─────────────────────────────────────────────────────────
                g2.setColor(TEXT_MUTED);
                g2.setFont(new Font("SansSerif", Font.PLAIN, 10));
                int step = Math.max(1, n/10);
                int[] xCoords = new int[n];
                int[] yCoords = new int[n];
                for (int i = 0; i < n; i++) {
                    xCoords[i] = padL + (int)((double)i / Math.max(1,n-1) * cW);
                    yCoords[i] = padT + (int)((1.0 - dispAfter[i] / maxPF) * cH);
                    if (i % step == 0)
                        g2.drawString("Yr"+(i+1), xCoords[i]-10, H-padB+14);
                }

                // ── Fan paths (drawn first, behind everything) ────────────────────────
                if (hasFan && fanPaths != null) {
                    // Alpha scales down as path count grows so dense fans stay readable
                    int alpha = Math.max(4, Math.min(35, 6000 / fanCount));
                    g2.setStroke(new BasicStroke(0.7f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL));
                    for (double[] path : fanPaths) {
                        if (path == null) continue;
                        boolean ruined = path[path.length - 1] <= 0;
                        g2.setColor(ruined
                                ? new Color(248, 113, 113, alpha)
                                : new Color( 45, 212, 191, alpha));
                        for (int i = 1; i < n && i < path.length; i++) {
                            double d0 = realDollars ? data.cumulCPI[i-1] : 1.0;
                            double d1 = realDollars ? data.cumulCPI[i]   : 1.0;
                            int x1 = xCoords[i-1];
                            int y1 = padT + (int)((1.0 - path[i-1]/d0 / maxPF) * cH);
                            int x2 = xCoords[i];
                            int y2 = padT + (int)((1.0 - path[i]  /d1 / maxPF) * cH);
                            y1 = Math.max(padT, Math.min(padT+cH, y1));
                            y2 = Math.max(padT, Math.min(padT+cH, y2));
                            g2.drawLine(x1, y1, x2, y2);
                        }
                    }
                }

                // ── Primary portfolio shaded area ─────────────────────────────────────
                int[] xp = new int[n+2], yp = new int[n+2];
                System.arraycopy(xCoords, 0, xp, 0, n);
                System.arraycopy(yCoords, 0, yp, 0, n);
                xp[n]=padL+cW; yp[n]=padT+cH; xp[n+1]=padL; yp[n+1]=padT+cH;
                g2.setColor(new Color(45, 212, 191, hasFan ? 12 : 28));
                g2.fillPolygon(xp, yp, n+2);

                // ── Primary portfolio line ────────────────────────────────────────────
                g2.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.setColor(ACCENT_TEAL);
                for (int i = 1; i < n; i++)
                    g2.drawLine(xCoords[i-1], yCoords[i-1], xCoords[i], yCoords[i]);

                // ── Withdrawal line ───────────────────────────────────────────────────
                g2.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.setColor(ACCENT_GOLD);
                for (int i = 1; i < n; i++) {
                    int x1 = xCoords[i-1], x2 = xCoords[i];
                    int y1 = padT + (int)((1.0 - dispWD[i-1] / maxPF) * cH);
                    int y2 = padT + (int)((1.0 - dispWD[i]   / maxPF) * cH);
                    g2.drawLine(x1, y1, x2, y2);
                }

                // ── Guardrail event dots ──────────────────────────────────────────────
                for (int i = 0; i < n; i++) {
                    String gr = data.guardrails[i];
                    if (gr == null) continue;
                    if (gr.contains("INCREASE")) {
                        g2.setColor(ACCENT_GREEN);
                        g2.fillOval(xCoords[i]-5, yCoords[i]-5, 10, 10);
                    } else if (gr.contains("CUTBACK")) {
                        g2.setColor(ACCENT_RED);
                        g2.fillOval(xCoords[i]-5, yCoords[i]-5, 10, 10);
                    } else if (gr.contains("DEPLETED")) {
                        g2.setColor(ACCENT_RED);
                        g2.setStroke(new BasicStroke(2));
                        g2.drawLine(xCoords[i], padT, xCoords[i], padT+cH);
                    }
                }

                // ── Axes ──────────────────────────────────────────────────────────────
                g2.setColor(TEXT_MUTED);
                g2.setStroke(new BasicStroke(1));
                g2.drawLine(padL, padT, padL, padT+cH);
                g2.drawLine(padL, padT+cH, padL+cW, padT+cH);

                // ── Legend ────────────────────────────────────────────────────────────
                int lx = padL+cW-360, ly = padT+16;
                leg(g2, lx,     ly, ACCENT_TEAL,  "Selected Path");
                leg(g2, lx+130, ly, ACCENT_GOLD,  "Withdrawal");
                if (hasFan) {
                    leg(g2, lx+250, ly, new Color(45, 212, 191, 120), "MC Survival");
                    leg(g2, lx+370, ly, new Color(248, 113, 113, 120), "MC Ruin");
                }
                // Guardrail dot legend
                int gx = padL + 10;
                g2.setColor(ACCENT_GREEN); g2.fillOval(gx, ly-7, 9, 9);
                g2.setColor(TEXT_MUTED);   g2.setFont(new Font("SansSerif",Font.PLAIN,10));
                g2.drawString("INCREASE", gx+12, ly);
                g2.setColor(ACCENT_RED);   g2.fillOval(gx+80, ly-7, 9, 9);
                g2.drawString("CUTBACK", gx+93, ly);

                g2.dispose();
            }

            private void leg(Graphics2D g2, int x, int y, Color c, String t){
                g2.setColor(c); g2.fillRect(x, y-7, 18, 4);
                g2.setColor(TEXT_MUTED); g2.setFont(new Font("SansSerif",Font.PLAIN,10));
                g2.drawString(t, x+22, y);
            }
            private void drawPlaceholder(Graphics g){
                g.setColor(TEXT_MUTED);
                g.setFont(new Font("Georgia", Font.ITALIC, 14));
                String m = "Run a simulation to see the portfolio chart";
                FontMetrics fm = g.getFontMetrics();
                g.drawString(m, (getWidth()-fm.stringWidth(m))/2, getHeight()/2);
            }
            private static String fmt(double v){
                if (v >= 1_000_000) return String.format("$%.1fM", v/1_000_000);
                if (v >= 1_000)     return String.format("$%.0fK", v/1_000);
                return String.format("$%.0f", v);
            }
        }

        // ── Main ──────────────────────────────────────────────────────────────────────
        public static void main(String[] args) {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
            catch (Exception ignored) {}
            UIManager.put("ToolTip.background", new Color(30, 45, 80));
            UIManager.put("ToolTip.foreground", new Color(241, 245, 249));
            UIManager.put("ToolTip.border",
                    BorderFactory.createLineBorder(new Color(51, 65, 100)));
            SwingUtilities.invokeLater(() -> new BoldinGuardrailsSim_claude().setVisible(true));
        }
    }
