package com.hiflite.utils;

import org.apache.commons.lang3.builder.StandardToStringStyle;

public class CsvStyles {
    // Style for the Header Row
    public static final StandardToStringStyle CSV_HEADER = new StandardToStringStyle();
    // Style for the Data Row
    public static final StandardToStringStyle CSV_DATA = new StandardToStringStyle();

    static {
        // Configure Header Style
        CSV_HEADER.setUseClassName(false);
        CSV_HEADER.setUseIdentityHashCode(false);
        CSV_HEADER.setFieldSeparator(",");
        CSV_HEADER.setContentStart("");
        CSV_HEADER.setContentEnd("");
        CSV_HEADER.setUseFieldNames(true); // This shows the variable names

        // Configure Data Style
        CSV_DATA.setUseClassName(false);
        CSV_DATA.setUseIdentityHashCode(false);
        CSV_DATA.setFieldSeparator(",");
        CSV_DATA.setContentStart("");
        CSV_DATA.setContentEnd("");
        CSV_DATA.setUseFieldNames(false); // Only the values
    }
}