package app.revanced.extension.edge;

import android.content.Context;

import java.lang.reflect.Method;

public final class EdgeContext {
    private EdgeContext() {
    }

    public static Context fromTab(Object tab) {
        if (tab == null) {
            return null;
        }

        try {
            Method getContext = tab.getClass().getMethod("getContext");
            Object context = getContext.invoke(tab);
            return context instanceof Context ? (Context) context : null;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }
}
