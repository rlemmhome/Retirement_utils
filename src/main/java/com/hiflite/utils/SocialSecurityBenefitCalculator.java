package com.hiflite.utils;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import java.awt.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Social Security Benefit Calculator — Tree View (birth-month anchored)
 *
 * Each "Age XX" node starts in the person's actual birth month, not January.
 * Each child month node shows the calendar month + year alongside the benefit.
 *
 * SSA claiming rules:
 *  - FRA based on birth year (65–67 per SSA schedule)
 *  - Early reduction: 5/9 % per month for first 36 mo before FRA,
 *                     5/12% per month beyond 36 mo
 *  - Delayed credits:  2/3 % per month after FRA, capped at age 70
 *
 * Benefit amount is fixed from the first month you claim — it does NOT
 * change month-to-month within the same claiming decision.  What this
 * table shows is: "If I start collecting in THIS calendar month, what
 * would my permanent monthly benefit be?"
 */
public class SocialSecurityBenefitCalculator {

    private static final String[] MONTH_NAMES = {
            "January","February","March","April","May","June",
            "July","August","September","October","November","December"
    };

    // ---------------------------------------------------------------
    public static void main(String[] args) {
        SwingUtilities.invokeLater(SocialSecurityBenefitCalculator::showInputDialog);
    }

    // ---------------------------------------------------------------
    // Input dialog
    // ---------------------------------------------------------------
    private static void showInputDialog() {
        JDialog dlg = new JDialog((Frame) null, "Social Security Benefit Calculator", true);
        dlg.setLayout(new BorderLayout(10, 10));
        dlg.setResizable(false);

        JLabel title = new JLabel("Social Security Benefit Calculator", SwingConstants.CENTER);
        title.setFont(new Font("SansSerif", Font.BOLD, 16));
        title.setBorder(BorderFactory.createEmptyBorder(14, 10, 4, 10));
        dlg.add(title, BorderLayout.NORTH);

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(8, 20, 8, 20));
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets  = new Insets(6, 6, 6, 6);
        gc.anchor  = GridBagConstraints.WEST;

        gc.gridx = 0; gc.gridy = 0;
        form.add(new JLabel("Primary Insurance Amount (PIA) $:"), gc);
        gc.gridx = 1;
        JTextField piaField = new JTextField(12);
        piaField.setToolTipText("e.g. 1850.00");
        form.add(piaField, gc);

        gc.gridx = 0; gc.gridy = 1;
        form.add(new JLabel("Date of Birth (MM/DD/YYYY):"), gc);
        gc.gridx = 1;
        JTextField dobField = new JTextField(12);
        dobField.setToolTipText("e.g. 09/08/1961");
        form.add(dobField, gc);

        dlg.add(form, BorderLayout.CENTER);

        JPanel btns = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 10));
        JButton calc   = new JButton("Calculate");
        JButton cancel = new JButton("Cancel");
        calc.setPreferredSize(new Dimension(110, 30));
        cancel.setPreferredSize(new Dimension(110, 30));
        btns.add(calc);
        btns.add(cancel);
        dlg.add(btns, BorderLayout.SOUTH);

        cancel.addActionListener(e -> { dlg.dispose(); System.exit(0); });

        calc.addActionListener(e -> {
            String piaText = piaField.getText().trim().replace(",", "").replace("$", "");
            String dobText = dobField.getText().trim();
            double pia;
            LocalDate dob;

            try {
                pia = Double.parseDouble(piaText);
                if (pia <= 0) throw new NumberFormatException();
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(dlg,
                        "Please enter a valid positive dollar amount for the PIA.",
                        "Invalid PIA", JOptionPane.ERROR_MESSAGE);
                return;
            }

            try {
                dob = LocalDate.parse(dobText, DateTimeFormatter.ofPattern("MM/dd/yyyy"));
                if (dob.isAfter(LocalDate.now().minusYears(62))) {
                    JOptionPane.showMessageDialog(dlg,
                            "Person must be at least 62 to claim Social Security.",
                            "Invalid Date of Birth", JOptionPane.ERROR_MESSAGE);
                    return;
                }
            } catch (DateTimeParseException ex) {
                JOptionPane.showMessageDialog(dlg,
                        "Please enter date of birth in MM/DD/YYYY format.",
                        "Invalid Date", JOptionPane.ERROR_MESSAGE);
                return;
            }

            dlg.dispose();
            showResultsTree(pia, dob);
        });

        dlg.getRootPane().setDefaultButton(calc);
        dlg.pack();
        dlg.setMinimumSize(new Dimension(420, 210));
        dlg.setLocationRelativeTo(null);
        dlg.setVisible(true);
    }

    // ---------------------------------------------------------------
    // FRA lookup
    // ---------------------------------------------------------------
    private static int[] getFullRetirementAge(int birthYear) {
        if (birthYear <= 1937) return new int[]{65,  0};
        if (birthYear == 1938) return new int[]{65,  2};
        if (birthYear == 1939) return new int[]{65,  4};
        if (birthYear == 1940) return new int[]{65,  6};
        if (birthYear == 1941) return new int[]{65,  8};
        if (birthYear == 1942) return new int[]{65, 10};
        if (birthYear <= 1954) return new int[]{66,  0};
        if (birthYear == 1955) return new int[]{66,  2};
        if (birthYear == 1956) return new int[]{66,  4};
        if (birthYear == 1957) return new int[]{66,  6};
        if (birthYear == 1958) return new int[]{66,  8};
        if (birthYear == 1959) return new int[]{66, 10};
        return new int[]{67, 0};
    }

    /**
     * Calculate the permanent monthly benefit if the person claims
     * when they have completed exactly `ageMonths` months of life.
     *
     * ageMonths = 0 means birth, 12 = first birthday, etc.
     * fraTotal  = FRA expressed in the same units.
     */
    private static double calculateBenefit(double pia, int fraTotal, int ageMonths) {
        int diff = ageMonths - fraTotal;  // negative = early, positive = delayed

        if (diff == 0) return pia;

        if (diff < 0) {
            int early = -diff;
            double reduction = early <= 36
                    ? early * (5.0 / 9.0  / 100.0)
                    : 36   * (5.0 / 9.0  / 100.0) + (early - 36) * (5.0 / 12.0 / 100.0);
            return pia * (1.0 - reduction);
        } else {
            // cap at 70 * 12 months of life
            int maxDelay = 70 * 12 - fraTotal;
            int delayed  = Math.min(diff, maxDelay);
            return pia * (1.0 + delayed * (2.0 / 3.0 / 100.0));
        }
    }

    // ---------------------------------------------------------------
    // Custom tree node
    // ---------------------------------------------------------------
    static class BenefitNode extends DefaultMutableTreeNode {
        final boolean isFra;
        final boolean isEarly;

        BenefitNode(String label, boolean isFra, boolean isEarly) {
            super(label);
            this.isFra   = isFra;
            this.isEarly = isEarly;
        }
    }

    // ---------------------------------------------------------------
    // Results window
    // ---------------------------------------------------------------
    private static void showResultsTree(double pia, LocalDate dob) {
        int birthYear  = dob.getYear();
        int birthMonth = dob.getMonthValue();   // 1-based (Sept = 9)
        int birthDay   = dob.getDayOfMonth();

        int[] fra      = getFullRetirementAge(birthYear);
        int fraYears   = fra[0];
        int fraMonths  = fra[1];
        // fraTotal = number of complete months of life at FRA
        int fraTotal   = fraYears * 12 + fraMonths;

        String fraLabel = fraMonths == 0
                ? String.valueOf(fraYears)
                : fraYears + " yrs " + fraMonths + " mo";

        // ---- Build tree ----
        DefaultMutableTreeNode root = new DefaultMutableTreeNode(
                "Social Security Benefits  (PIA: " + String.format("$%,.2f", pia) + ")");

        for (int age = 63; age <= 70; age++) {

            // The calendar date the person turns `age`
            LocalDate birthday = dob.plusYears(age);   // exact birthday for this age
            int calYearAtBirthday  = birthday.getYear();
            int calMonthAtBirthday = birthday.getMonthValue() - 1; // 0-based for array

            // Age in completed months on their birthday = age * 12 exactly
            int ageMonthsAtBirthday = age * 12;
            double birthdayBenefit  = calculateBenefit(pia, fraTotal, ageMonthsAtBirthday);

            int diffFromFra = ageMonthsAtBirthday - fraTotal;
            boolean ageIsFra   = (diffFromFra == 0);
            boolean ageIsEarly = (diffFromFra < 0);

            String diffStr;
            if (diffFromFra == 0)     diffStr = " ← FRA ★";
            else if (diffFromFra < 0) diffStr = "  [" + (-diffFromFra) + " mo early]";
            else                      diffStr = "  [+" + diffFromFra + " mo delayed]";

            String ageLabel = String.format(
                    "Age %d%s   |   Birthday month: $%,.2f/mo   (turns %d in %s %d)",
                    age, diffStr, birthdayBenefit,
                    age, MONTH_NAMES[calMonthAtBirthday], calYearAtBirthday);

            BenefitNode ageNode = new BenefitNode(ageLabel, ageIsFra, ageIsEarly);
            root.add(ageNode);

            // ---- 12 monthly children, starting from the birth month ----
            for (int mo = 0; mo < 12; mo++) {
                // Completed months of life when claiming in this slot
                int ageMonths = age * 12 + mo;

                // Corresponding calendar date: birthday + mo months
                LocalDate claimDate = birthday.plusMonths(mo);
                int calYear  = claimDate.getYear();
                int calMonth = claimDate.getMonthValue() - 1;  // 0-based

                double monthly = calculateBenefit(pia, fraTotal, ageMonths);
                double annual  = monthly * 12;

                int moDiff   = ageMonths - fraTotal;
                boolean moIsFra   = (moDiff == 0);
                boolean moIsEarly = (moDiff < 0);

                String pct    = String.format("%+.1f%%", (monthly / pia - 1.0) * 100.0);
                String fraTag = moIsFra ? "  ★ FRA" : "";

                // Show "Month YYYY" so the calendar year is visible
                String moLabel = String.format(
                        "%-12s %4d   $%,.2f/mo   ($%,.2f/yr)   %s%s",
                        MONTH_NAMES[calMonth], calYear,
                        monthly, annual,
                        pct, fraTag);

                ageNode.add(new BenefitNode(moLabel, moIsFra, moIsEarly));
            }
        }

        // ---- Tree widget ----
        JTree tree = new JTree(root);
        tree.setRootVisible(true);
        tree.setShowsRootHandles(true);
        tree.setFont(new Font("Monospaced", Font.PLAIN, 13));
        tree.setRowHeight(22);

        tree.setCellRenderer(new DefaultTreeCellRenderer() {
            @Override
            public Component getTreeCellRendererComponent(JTree t, Object value,
                                                          boolean sel, boolean expanded, boolean leaf, int row, boolean hasFocus) {
                super.getTreeCellRendererComponent(t, value, sel, expanded, leaf, row, hasFocus);
                setIcon(null);

                if (value instanceof BenefitNode) {
                    BenefitNode node = (BenefitNode) value;
                    if (!sel) {
                        if (node.isFra) {
                            setForeground(new Color(0, 128, 0));
                            setFont(getFont().deriveFont(Font.BOLD));
                        } else if (node.isEarly) {
                            setForeground(new Color(180, 0, 0));
                        } else {
                            setForeground(new Color(0, 70, 160));
                        }
                    }
                } else {
                    setFont(getFont().deriveFont(Font.BOLD, 14f));
                    setForeground(Color.BLACK);
                }
                return this;
            }
        });

        // Start collapsed — just root open
        tree.expandRow(0);

        // ---- Frame ----
        JFrame frame = new JFrame("Social Security Benefit Tree");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout(8, 8));

        // Info panel
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("MMMM d, yyyy");
        JPanel info = new JPanel(new GridLayout(0, 2, 6, 3));
        info.setBorder(BorderFactory.createTitledBorder("Your Information"));
        info.add(bold("Date of Birth:"));       info.add(new JLabel(dob.format(fmt)));
        info.add(bold("PIA:"));                  info.add(new JLabel(String.format("$%,.2f / month", pia)));
        info.add(bold("Full Retirement Age:")); info.add(new JLabel(fraLabel + "  (birth year " + birthYear + ")"));

        JPanel infoPad = new JPanel(new BorderLayout());
        infoPad.setBorder(BorderFactory.createEmptyBorder(10, 12, 2, 12));
        infoPad.add(info);
        frame.add(infoPad, BorderLayout.NORTH);

        // Toolbar
        JToolBar toolbar = new JToolBar();
        toolbar.setFloatable(false);
        JButton expandAll   = new JButton("Expand All");
        JButton collapseAll = new JButton("Collapse All");
        expandAll.addActionListener(e -> {
            for (int i = 0; i < tree.getRowCount(); i++) tree.expandRow(i);
        });
        collapseAll.addActionListener(e -> {
            for (int i = tree.getRowCount() - 1; i > 0; i--) tree.collapseRow(i);
        });
        toolbar.add(expandAll);
        toolbar.addSeparator();
        toolbar.add(collapseAll);
        toolbar.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));

        JPanel center = new JPanel(new BorderLayout());
        center.add(toolbar, BorderLayout.NORTH);
        center.add(new JScrollPane(tree), BorderLayout.CENTER);
        center.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 12));
        frame.add(center, BorderLayout.CENTER);

        // Legend
        JPanel legend = new JPanel(new FlowLayout(FlowLayout.LEFT, 16, 6));
        legend.add(legendLabel(new Color(180, 0, 0),  "⬤  Early (benefit reduced)"));
        legend.add(legendLabel(new Color(0, 128, 0),  "⬤  ★ FRA (full PIA)"));
        legend.add(legendLabel(new Color(0, 70, 160), "⬤  Delayed (benefit increased)"));
        legend.setBorder(BorderFactory.createEmptyBorder(0, 10, 4, 10));
        frame.add(legend, BorderLayout.SOUTH);

        frame.setPreferredSize(new Dimension(820, 600));
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    // ---------------------------------------------------------------
    // UI helpers
    // ---------------------------------------------------------------
    private static JLabel bold(String text) {
        JLabel l = new JLabel("  " + text);
        l.setFont(l.getFont().deriveFont(Font.BOLD));
        return l;
    }

    private static JLabel legendLabel(Color color, String text) {
        JLabel l = new JLabel(text);
        l.setForeground(color);
        l.setFont(new Font("SansSerif", Font.PLAIN, 12));
        return l;
    }
}
