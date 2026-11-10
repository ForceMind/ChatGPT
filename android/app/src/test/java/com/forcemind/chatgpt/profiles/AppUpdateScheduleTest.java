package com.forcemind.chatgpt.profiles;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import org.junit.Test;
import static org.junit.Assert.*;

public class AppUpdateScheduleTest {
    @Test public void checksAtShanghaiNoonRegardlessOfDisplayedZone() {
        Instant before = Instant.parse("2026-11-10T03:59:59Z");
        Instant noon = Instant.parse("2026-11-10T04:00:00Z");
        assertFalse(AppUpdateSchedule.due(before, ""));
        assertTrue(AppUpdateSchedule.due(noon, ""));
        assertEquals(noon, AppUpdateSchedule.nextNoon(before));
        ZonedDateTime overseas = noon.atZone(ZoneOffset.ofHours(-8));
        assertEquals("2026-11-10", AppUpdateSchedule.day(overseas.toInstant()).toString());
    }

    @Test public void onlyCompletingTheShanghaiDayConsumesItsSlot() {
        Instant afternoon = Instant.parse("2026-11-10T09:00:00Z");
        assertTrue(AppUpdateSchedule.due(afternoon, "2026-11-09"));
        assertFalse(AppUpdateSchedule.due(afternoon, "2026-11-10"));
        Instant next = Instant.parse("2026-11-11T04:00:00Z");
        assertEquals(next, AppUpdateSchedule.nextNoon(afternoon));
        assertTrue(AppUpdateSchedule.due(next, "2026-11-10"));
    }
}
