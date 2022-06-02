package tunnel.util;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.pattern.CompositeConverter;
import ch.qos.logback.core.pattern.color.ANSIConstants;
import ch.qos.logback.core.pattern.color.ForegroundCompositeConverterBase;
import com.google.common.base.Strings;
import org.apache.commons.lang3.StringUtils;
import org.davidmoten.text.utils.WordWrap;

import java.util.Locale;
import java.util.stream.Collectors;

public class MultiColumnLogbackLayout extends PatternLayout {

    private static final String SPLIT_TEXT = "##split##";

    {
        DEFAULT_CONVERTER_MAP.put("rs", RelativeSecondsConverter.class.getName());
        DEFAULT_CONVERTER_MAP.put("highlightex", HighlightingCompositeConverterEx.class.getName());
        DEFAULT_CONVERTER_MAP.put("wrap", WrapConverter.class.getName());
    }

    private static int width = 200;
    private static int columnCount = 1;
    private static int currentColumn = 0;
    private static String columnSep = "|";

    private static boolean enabled = true;

    public static void disable() {
        enabled = false;
    }

    public static void setLineWidth(int width) {
        MultiColumnLogbackLayout.width = width;
    }

    public static void setCurrentColumn(int column, int maxColumn) {
        currentColumn = column;
        columnCount = maxColumn;
    }

    // based ch.qos.logback.classic.pattern.RelativeTimeConverter
    public static class RelativeSecondsConverter extends ClassicConverter {

        long lastTimestamp = -1;
        String timesmapCache = null;

        public String convert(ILoggingEvent event) {
            long now = event.getTimeStamp();

            synchronized (this) {
                // update timesmapStrCache only if now != lastTimestamp
                if (now != lastTimestamp) {
                    lastTimestamp = now;
                    timesmapCache = String.format(Locale.US, "%.3f", (now - event.getLoggerContextVO().getBirthTime()) / 1000d);
                }
                return timesmapCache;
            }
        }
    }

    // source: https://github.com/shuwada/logback-custom-color
    public static class HighlightingCompositeConverterEx extends ForegroundCompositeConverterBase<ILoggingEvent> {

        @Override
        protected String getForegroundColorCode(ILoggingEvent event) {
            Level level = event.getLevel();
            switch (level.toInt()) {
                case Level.ERROR_INT:
                    return ANSIConstants.BOLD + ANSIConstants.RED_FG; // same as default color scheme
                case Level.WARN_INT:
                    return ANSIConstants.RED_FG;// same as default color scheme
                case Level.INFO_INT:
                    return ANSIConstants.CYAN_FG; // use CYAN instead of BLUE
                default:
                    return ANSIConstants.DEFAULT_FG;
            }
        }
    }

    public static class WrapConverter<E> extends CompositeConverter<E> {

        @Override
        protected String transform(E event, String in) {
            if (!enabled) {
                return in.replace(SPLIT_TEXT, "");
            }
            var arr = in.split(SPLIT_TEXT);
            if (arr.length != 2) {
                throw new AssertionError(String.format("wrap requires a '%s' between prefix and " +
                        "message", SPLIT_TEXT));
            }
            return process(arr[0], arr[1].trim()) + "\n";
        }

        private String process(String prefix, String message) {
            int prefixWidth = prefix.length();
            int columnWidth = (width - prefixWidth) / columnCount - columnSep.length();
            int columnPadding = columnWidth * currentColumn;
            String prefixPaddingStr = Strings.repeat(" ", prefixWidth);
            String columnPaddingStr = Strings.repeat(" ", columnWidth);
            String pre = Strings.repeat(columnPaddingStr + columnSep, currentColumn);
            String post = Strings.repeat(columnSep + columnPaddingStr, columnCount - currentColumn - 1);
            String paddedAndWrappedMessage = WordWrap.from(message).maxWidth(columnWidth)
                    .wrapToList().stream().filter(s -> !s.isBlank())
                    .map(s -> StringUtils.rightPad(s, columnWidth, " "))
                    .map(s -> s + post)
                    .collect(Collectors.joining("\n" + prefixPaddingStr + pre));

            return prefix + pre + paddedAndWrappedMessage;
        }
    }
}
