package com.liubai.diary.reminder;

import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReminderJobTest {
    @Test
    void usesTheAccountWeekdayAndLocalTime() {
        var settings = new ReminderService.Settings(true, false, "08:30", "WEEKDAYS", List.of());
        assertFalse(ReminderJob.isDue(settings, time("2026-11-09T08:29+08:00[Asia/Shanghai]")));
        assertTrue(ReminderJob.isDue(settings, time("2026-11-09T08:30+08:00[Asia/Shanghai]")));
        assertFalse(ReminderJob.isDue(settings, time("2026-11-09T09:00+08:00[Asia/Shanghai]")));
        assertFalse(ReminderJob.isDue(settings, time("2026-11-08T08:30+08:00[Asia/Shanghai]")));
    }

    @Test
    void selectsCustomWeekdaysAndHandlesDaylightSaving() {
        var settings = new ReminderService.Settings(false, true, "02:30", "CUSTOM", List.of(7));
        assertTrue(ReminderJob.isDue(settings, time("2026-03-08T03:30-04:00[America/New_York]")));
        assertFalse(ReminderJob.isDue(settings, time("2026-03-09T02:30-04:00[America/New_York]")));
    }

    private static ZonedDateTime time(String value) { return ZonedDateTime.parse(value); }
}
