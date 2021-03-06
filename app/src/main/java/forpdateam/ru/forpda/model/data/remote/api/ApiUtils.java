package forpdateam.ru.forpda.model.data.remote.api;

import android.text.Spanned;
import android.text.TextUtils;

import org.json.JSONObject;

import forpdateam.ru.forpda.common.Html;

/**
 * Created by radiationx on 26.03.17.
 */

public class ApiUtils {
    public static Spanned coloredFromHtml(String s) {
        if (s == null) return null;
        return Html.fromHtml(s, Html.FROM_HTML_OPTION_USE_CSS_COLORS);
    }

    public static Spanned spannedFromHtml(String s) {
        if (s == null) return null;
        return Html.fromHtml(s);
    }

    public static String fromHtml(String s) {
        if (s == null) return null;
        return spannedFromHtml(s).toString();
    }

    public static String htmlEncode(String s) {
        if (s == null) return null;
        return TextUtils.htmlEncode(s);
    }

    public static String escapeNewLine(String s) {
        StringBuilder sb = new StringBuilder();
        char c;
        final int length = s.length();
        for (int i = 0; i < length; i++) {
            c = s.charAt(i);
            if (c == '\n') {
                sb.append("<br>");
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    public static String escapeQuotes(String s) {
        String escaped = JSONObject.quote(s);
        escaped = escaped.substring(1, escaped.length() - 1);
        return escaped;
    }
}
