package app.revanced.extension.edge;

import android.os.Handler;
import android.os.Looper;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;

public final class WebContentsJavaScript {
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private WebContentsJavaScript() {
    }

    public static void inject(Object tab, String script, long... delaysMs) {
        Object webContents = getWebContents(tab);
        if (webContents == null) {
            return;
        }

        for (long delayMs : delaysMs) {
            MAIN_HANDLER.postDelayed(
                new ScriptInjection(webContents, script),
                delayMs
            );
        }
    }

    private static Object getWebContents(Object tab) {
        if (tab == null) {
            return null;
        }

        try {
            Method method = tab.getClass().getMethod("getWebContents");
            return method.invoke(tab);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static void evaluate(Object webContents, String script) {
        for (Method method : webContents.getClass().getMethods()) {
            Class<?>[] parameterTypes = method.getParameterTypes();
            if (
                // Replaced with Edge's current obfuscated method name by the RVP.
                method.getName().equals(
                    "__EDGE_EVALUATE_JAVASCRIPT_METHOD__"
                ) &&
                parameterTypes.length == 2 &&
                parameterTypes[0] == String.class
            ) {
                try {
                    method.invoke(webContents, script, null);
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // A retry may already be scheduled for a later lifecycle stage.
                }
                return;
            }
        }
    }

    private static final class ScriptInjection implements Runnable {
        private final WeakReference<Object> webContents;
        private final String script;

        private ScriptInjection(Object webContents, String script) {
            this.webContents = new WeakReference<>(webContents);
            this.script = script;
        }

        @Override
        public void run() {
            Object currentWebContents = webContents.get();
            if (currentWebContents != null) {
                evaluate(currentWebContents, script);
            }
        }
    }
}
