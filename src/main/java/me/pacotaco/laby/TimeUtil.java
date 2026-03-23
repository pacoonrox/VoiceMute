package me.pacotaco.laby;

import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TimeUtil {

    private TimeUtil() {}

    public static long parseTimeStrict(String input) {
        if (input == null || input.isEmpty()) return -1;
        Pattern fullPattern = Pattern.compile("^(\\d+(s|m|h|d|w|mo|y))+$");
        if (!fullPattern.matcher(input.toLowerCase()).matches()) return -1;
        Pattern partPattern = Pattern.compile("(\\d+)(s|m|h|d|w|mo|y)");
        Matcher matcher = partPattern.matcher(input.toLowerCase());
        long total = 0;
        while (matcher.find()) {
            long value;
            try {
                value = Long.parseLong(matcher.group(1));
            } catch (NumberFormatException e) {
                return -1;
            }
            String unit = matcher.group(2);
            long unitMs = switch (unit) {
                case "s"  -> 1_000L;
                case "m"  -> 60_000L;
                case "h"  -> 3_600_000L;
                case "d"  -> 86_400_000L;
                case "w"  -> 604_800_000L;
                case "mo" -> 2_592_000_000L;
                case "y"  -> 31_536_000_000L;
                default   -> 0L;
            };
            if (unitMs > 0 && value > (Long.MAX_VALUE - total) / unitMs) return -1; // overflow guard
            total += value * unitMs;
        }
        return total > 0 ? total : -1;
    }

    public static String formatLongTime(long millis) {
        long d = TimeUnit.MILLISECONDS.toDays(millis);
        long h = TimeUnit.MILLISECONDS.toHours(millis) % 24;
        long m = TimeUnit.MILLISECONDS.toMinutes(millis) % 60;
        StringBuilder sb = new StringBuilder();
        if (d > 0) sb.append(d).append("d ");
        if (h > 0) sb.append(h).append("h ");
        if (m > 0) sb.append(m).append("m");
        String result = sb.toString().trim();
        return result.isEmpty() ? "0m" : result;
    }

    public static int tryParseInt(String s) {
        try { return Integer.parseInt(s); } catch (Exception e) { return 1; }
    }
}
