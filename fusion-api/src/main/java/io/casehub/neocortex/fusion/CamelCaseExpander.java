package io.casehub.neocortex.fusion;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CamelCaseExpander {

    private static final Pattern TOKEN = Pattern.compile("\\S+");
    private static final Pattern CAMEL_BOUNDARY =
        Pattern.compile("(?<=[a-z])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])|(?<=[a-zA-Z])(?=[0-9])|(?<=[0-9])(?=[a-zA-Z])");

    private CamelCaseExpander() {}

    public static String expand(String text) {
        if (text == null || text.isEmpty()) return "";

        StringBuilder result = new StringBuilder(text.length() * 2);
        Matcher tokenMatcher = TOKEN.matcher(text);
        int lastEnd = 0;

        while (tokenMatcher.find()) {
            result.append(text, lastEnd, tokenMatcher.start());
            String token = tokenMatcher.group();
            result.append(token);

            String[] parts = CAMEL_BOUNDARY.split(token);
            if (parts.length > 1) {
                for (String part : parts) {
                    result.append(' ').append(part);
                }
            }
            lastEnd = tokenMatcher.end();
        }
        result.append(text, lastEnd, text.length());

        return result.toString();
    }
}
