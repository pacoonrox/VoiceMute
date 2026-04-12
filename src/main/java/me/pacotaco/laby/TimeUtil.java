package me.pacotaco.laby;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TimeUtil {

    private TimeUtil() {}

    /** Sentinel used for permanent mutes — far-future datetime stored in the DB. */
    public static final Instant PERMANENT_EXPIRY = Instant.parse("3000-01-01T00:00:00Z");

    private static final Pattern FULL_PATTERN = Pattern.compile("^(\\d+(s|mo|m|h|d|w|y))+$");
    private static final Pattern PART_PATTERN = Pattern.compile("(\\d+)(s|mo|m|h|d|w|y)");

    /**
     * Parses a duration string (e.g. "7d", "1h30m", "2mo") and returns the resulting
     * expiry {@link Instant}. Months and years use calendar-aware addition. Returns
     * {@code null} if the input is blank or does not match the expected format.
     */
    public static Instant parseExpiry(String input) {
        if (input == null || input.isEmpty()) return null;
        String lower = input.toLowerCase();
        if (!FULL_PATTERN.matcher(lower).matches()) return null;

        Matcher matcher = PART_PATTERN.matcher(lower);
        ZonedDateTime result = ZonedDateTime.now(ZoneOffset.UTC);
        while (matcher.find()) {
            long value;
            try {
                value = Long.parseLong(matcher.group(1));
            } catch (NumberFormatException e) {
                return null;
            }
            result = switch (matcher.group(2)) {
                case "s"  -> result.plusSeconds(value);
                case "m"  -> result.plusMinutes(value);
                case "h"  -> result.plusHours(value);
                case "d"  -> result.plusDays(value);
                case "w"  -> result.plusWeeks(value);
                case "mo" -> result.plusMonths(value);
                case "y"  -> result.plusYears(value);
                default   -> result;
            };
        }
        Instant expiry = result.toInstant();
        return expiry.isAfter(Instant.now()) ? expiry : null;
    }

    /**
     * Formats the duration between two instants as a human-readable string,
     * e.g. {@code "7d 3h 45m"}. Returns {@code "0m"} if the duration is zero or negative.
     */
    public static String formatDuration(Instant from, Instant to) {
        Duration d = Duration.between(from, to);
        if (d.isNegative() || d.isZero()) return "0m";
        long days    = d.toDays();
        long hours   = d.toHoursPart();
        long minutes = d.toMinutesPart();
        StringBuilder sb = new StringBuilder();
        if (days > 0)    sb.append(days).append("d ");
        if (hours > 0)   sb.append(hours).append("h ");
        if (minutes > 0) sb.append(minutes).append("m");
        String result = sb.toString().trim();
        return result.isEmpty() ? "0m" : result;
    }

    public static int tryParseInt(String s) {
        try { return Integer.parseInt(s); } catch (Exception e) { return 1; }
    }
}
