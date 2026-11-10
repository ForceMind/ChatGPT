package com.forcemind.chatgpt.profiles;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

/** The daily slot follows Shanghai wall time, independent of profile/device zones. */
final class AppUpdateSchedule {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    static LocalDate day(Instant now) { return now.atZone(SHANGHAI).toLocalDate(); }

    static Instant noon(LocalDate day) {
        return day.atTime(LocalTime.NOON).atZone(SHANGHAI).toInstant();
    }

    static boolean due(Instant now, String completedDay) {
        LocalDate day = day(now);
        return !now.isBefore(noon(day)) && !day.toString().equals(completedDay);
    }

    static Instant nextNoon(Instant now) {
        LocalDate day = day(now);
        Instant noon = noon(day);
        return now.isBefore(noon) ? noon : noon(day.plusDays(1));
    }
}
