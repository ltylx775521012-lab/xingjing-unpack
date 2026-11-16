package io.dafeiyu.unpack;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

/** 配色调色板 + 明暗模式偏好（不用 AppCompat，自己管） */
public final class Theme {

    public static final int FOLLOW = 0;
    public static final int LIGHT = 1;
    public static final int DARK = 2;

    private static final String PREF = "xingjing";
    private static final String KEY_MODE = "theme_mode";

    public final boolean dark;
    public final int bg, surface, surface2, text, text2, text3, divider,
            accent, accentSoft, track, onAccent, rippleColor;

    public Theme(boolean dark) {
        this.dark = dark;
        if (dark) {
            bg = 0xFF000000;
            surface = 0xFF16161A;
            surface2 = 0xFF1E1E22;
            text = 0xFFF3F4F6;
            text2 = 0xFF989BA2;
            text3 = 0xFF6B6E76;
            divider = 0xFF25252A;
            accent = 0xFF4C8DFF;
            accentSoft = 0xFF16233C;
            track = 0xFF242428;
            onAccent = 0xFFFFFFFF;
            rippleColor = 0x33FFFFFF;
        } else {
            bg = 0xFFF4F5F7;
            surface = 0xFFFFFFFF;
            surface2 = 0xFFEDEFF3;
            text = 0xFF0B0B0F;
            text2 = 0xFF787C85;
            text3 = 0xFFA8ACB4;
            divider = 0xFFECEEF2;
            accent = 0xFF2E7BFF;
            accentSoft = 0xFFE9F1FF;
            track = 0xFFE6E9EF;
            onAccent = 0xFFFFFFFF;
            rippleColor = 0x1A000000;
        }
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static int mode(Context c) {
        return sp(c).getInt(KEY_MODE, FOLLOW);
    }

    public static void setMode(Context c, int mode) {
        sp(c).edit().putInt(KEY_MODE, mode).apply();
    }

    public static boolean systemDark(Context c) {
        int ui = c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return ui == Configuration.UI_MODE_NIGHT_YES;
    }

    public static boolean resolveDark(Context c) {
        int m = mode(c);
        if (m == DARK) return true;
        if (m == LIGHT) return false;
        return systemDark(c);
    }

    public static Theme current(Context c) {
        return new Theme(resolveDark(c));
    }
}
