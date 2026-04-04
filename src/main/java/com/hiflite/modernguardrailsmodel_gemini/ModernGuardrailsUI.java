package com.hiflite.modernguardrailsmodel_gemini;

import javax.swing.*;
import java.awt.*;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;

public class ModernGuardrailsUI {
    private final Map<String, JTextField> fields = new LinkedHashMap<>();
    private final Object targetSource;

    public ModernGuardrailsUI(Object source) {
        this.targetSource = source;
    }

    public void showAndGather() {
        JPanel panel = new JPanel(new GridLayout(0, 2, 10, 10));

        // Use reflection to find public constants/fields in your simulation class
        for (Field field : targetSource.getClass().getFields()) {
            try {
                String name = field.getName();
                String value = String.valueOf(field.get(targetSource));

                JTextField textField = new JTextField(value, 15);
                panel.add(new JLabel(name + ":"));
                panel.add(textField);
                fields.put(name, textField);
            } catch (IllegalAccessException e) {
                // Skip private/protected fields
            }
        }

        int result = JOptionPane.showConfirmDialog(null, new JScrollPane(panel),
                "Modern Guardrails Input", JOptionPane.OK_CANCEL_OPTION);

        if (result == JOptionPane.OK_OPTION) {
            updateSourceValues();
        } else {
            System.exit(0); // User cancelled
        }
    }

    private void updateSourceValues() {
        for (Map.Entry<String, JTextField> entry : fields.entrySet()) {
            try {
                Field field = targetSource.getClass().getField(entry.getKey());
                Class<?> type = field.getType();
                String val = entry.getValue().getText();

                if (type == double.class || type == Double.class) field.set(targetSource, Double.parseDouble(val));
                else if (type == int.class || type == Integer.class) field.set(targetSource, Integer.parseInt(val));
                else if (type == float.class || type == Float.class) field.set(targetSource, Float.parseFloat(val));
                else if (type == boolean.class || type == Boolean.class) field.set(targetSource, Boolean.parseBoolean(val));
                else if (type == String.class) field.set(targetSource, val);

            } catch (Exception e) {
                System.err.println("Could not update field: " + entry.getKey());
            }
        }
    }
}