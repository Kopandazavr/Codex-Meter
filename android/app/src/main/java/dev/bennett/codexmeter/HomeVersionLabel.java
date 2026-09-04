package dev.bennett.codexmeter;

import android.app.Activity;
import android.graphics.Typeface;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/** Adds a build-derived secondary version label to the home toolbar without hardcoding releases. */
final class HomeVersionLabel {
    private static final String HOME_TITLE = "Codex Meter";

    private HomeVersionLabel() {
    }

    static void apply(Activity activity) {
        if (!(activity instanceof MainActivity)) return;
        View toolbar = activity.findViewById(R.id.toolbar_layout);
        if (toolbar == null) return;
        TextView title = findHomeTitle(toolbar);
        if (title == null) return;
        String version = Ui.versionName(activity);
        if (version == null || version.trim().isEmpty()) return;
        String suffix = "  " + version.trim();
        SpannableString text = new SpannableString(HOME_TITLE + suffix);
        int start = HOME_TITLE.length();
        text.setSpan(new RelativeSizeSpan(0.56f), start, text.length(),
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new ForegroundColorSpan(Ui.secondaryText(Ui.isDark(activity))),
                start, text.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new StyleSpan(Typeface.NORMAL), start, text.length(),
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        title.setText(text);
    }

    private static TextView findHomeTitle(View view) {
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            CharSequence current = text.getText();
            if (current != null && HOME_TITLE.contentEquals(current)) return text;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView match = findHomeTitle(group.getChildAt(i));
                if (match != null) return match;
            }
        }
        return null;
    }
}
