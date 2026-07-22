package me.bechberger.condensed;

public class Constants {

    public static final String START_STRING = "CondensedData";
    // v2: Universe carries the source recording's gmtOffset so the timezone survives
    // condense->inflate (older v1 files remain readable; the new field defaults to unset).
    public static final int VERSION = 2;

    /** Reserved message-type ID used as the footer magic sentinel. */
    public static final int FOOTER_TYPE_ID = CJFRFooter.FOOTER_TYPE_ID;
}
