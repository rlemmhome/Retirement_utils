package com.hiflite.guyton_klinger_gemini;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.text.NumberFormat;
import java.util.Locale;

public class BoldinGuardrailsSim_gemini extends JFrame {

    // Input Fields
    private JTextField portfolioField, returnField, inflationField, withdrawalField;
    private JTextField upperGuardrailField, lowerGuardrailField, adjustmentField, yearsField;
    private JTextArea resultArea;

    public BoldinGuardrailsSim_gemini() {
        setTitle("Boldin (Guyton-Klinger) Guardrails Simulator");
        setSize(500, 700);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        JPanel inputPanel = new JPanel(new GridLayout(9, 2, 10, 10));
        inputPanel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

        // Adding inputs
        inputPanel.add(new JLabel("Initial Portfolio ($):"));
        portfolioField = new JTextField("1000000");
        inputPanel.add(portfolioField);

        inputPanel.add(new JLabel("Expected Annual Return (%):"));
        returnField = new JTextField("7.0");
        inputPanel.add(returnField);

        inputPanel.add(new JLabel("Expected Inflation (%):"));
        inflationField = new JTextField("3.0");
        inputPanel.add(inflationField);

        inputPanel.add(new JLabel("Initial Annual Withdrawal ($):"));
        withdrawalField = new JTextField("40000");
        inputPanel.add(withdrawalField);

        inputPanel.add(new JLabel("Upper Guardrail (% Increase):"));
        upperGuardrailField = new JTextField("20"); // e.g., 20% above initial rate
        inputPanel.add(upperGuardrailField);

        inputPanel.add(new JLabel("Lower Guardrail (% Decrease):"));
        lowerGuardrailField = new JTextField("20"); // e.g., 20% below initial rate
        inputPanel.add(lowerGuardrailField);

        inputPanel.add(new JLabel("Adjustment Amount (%):"));
        adjustmentField = new JTextField("10");
        inputPanel.add(adjustmentField);

        inputPanel.add(new JLabel("Simulation Years:"));
        yearsField = new JTextField("30");
        inputPanel.add(yearsField);

        JButton runButton = new JButton("Run Simulation");
        runButton.addActionListener(this::runSimulation);
        inputPanel.add(runButton);

        resultArea = new JTextArea();
        resultArea.setEditable(false);
        JScrollPane scrollPane = new JScrollPane(resultArea);

        add(inputPanel, BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);
    }

    private void runSimulation(ActionEvent e) {
        try {
            double portfolio = Double.parseDouble(portfolioField.getText());
            double annualReturn = Double.parseDouble(returnField.getText()) / 100;
            double inflation = Double.parseDouble(inflationField.getText()) / 100;
            double withdrawal = Double.parseDouble(withdrawalField.getText());
            double upperThreshold = Double.parseDouble(upperGuardrailField.getText()) / 100;
            double lowerThreshold = Double.parseDouble(lowerGuardrailField.getText()) / 100;
            double adjustmentFactor = Double.parseDouble(adjustmentField.getText()) / 100;
            int years = Integer.parseInt(yearsField.getText());

            double initialWithdrawalRate = withdrawal / portfolio;
            double currentWithdrawal = withdrawal;

            NumberFormat currency = NumberFormat.getCurrencyInstance(Locale.US);
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("Initial Rate: %.2f%%\n", initialWithdrawalRate * 100));
            sb.append("--------------------------------------------------\n");
            sb.append(String.format("%-5s | %-15s | %-12s | %-10s\n", "Year", "Portfolio", "Withdrawal", "Rate"));

            for (int i = 1; i <= years; i++) {
                // Market Return
                portfolio = portfolio * (1 + annualReturn);

                // Inflation adjustment for the withdrawal base
                currentWithdrawal = currentWithdrawal * (1 + inflation);

                // Calculate current withdrawal rate
                double currentRate = currentWithdrawal / portfolio;

                // GUARDRAIL LOGIC
                // Capital Preservation Rule (Upper Guardrail)
                if (currentRate > initialWithdrawalRate * (1 + upperThreshold)) {
                    currentWithdrawal = currentWithdrawal * (1 - adjustmentFactor);
                    sb.append(" [!] Upper Guardrail Hit: Reducing Withdrawal\n");
                }
                // Prosperity Rule (Lower Guardrail)
                else if (currentRate < initialWithdrawalRate * (1 - lowerThreshold)) {
                    currentWithdrawal = currentWithdrawal * (1 + adjustmentFactor);
                    sb.append(" [*] Lower Guardrail Hit: Increasing Withdrawal\n");
                }

                portfolio -= currentWithdrawal;

                sb.append(String.format("%-5d | %-15s | %-12s | %.2f%%\n",
                        i, currency.format(portfolio), currency.format(currentWithdrawal), currentRate * 100));

                if (portfolio <= 0) {
                    sb.append("\n!!! PORTFOLIO DEPLETED IN YEAR " + i + " !!!");
                    break;
                }
            }

            resultArea.setText(sb.toString());

        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(this, "Please enter valid numeric values.");
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            new BoldinGuardrailsSim_gemini().setVisible(true);
        });
    }
}