package com.zinngar.savelogic.client;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/** Short, friendly text for times, sizes and computer names shown in menus. */
public final class CloudText {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM HH:mm");

    private CloudText() {
    }

    /** "today 14:10", "yesterday 21:30" or "12 Oct 14:10", in this computer's time zone. */
    public static String when(Instant instant) {
        ZonedDateTime time = instant.atZone(ZoneId.systemDefault());
        LocalDate today = LocalDate.now();
        if (time.toLocalDate().equals(today)) {
            return "today " + TIME.format(time);
        }
        if (time.toLocalDate().equals(today.minusDays(1))) {
            return "yesterday " + TIME.format(time);
        }
        return DATE.format(time);
    }

    public static String size(long bytes) {
        return bytes >= 1024L * 1024 * 1024
                ? String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024))
                : String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

    public static String device(String name) {
        return name == null || name.isBlank() ? "another computer" : name;
    }
}
