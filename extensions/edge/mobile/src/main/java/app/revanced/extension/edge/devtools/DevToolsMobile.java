package app.revanced.extension.edge.devtools;

import app.revanced.extension.edge.EdgeContext;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.Browser;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Locale;

public final class DevToolsMobile {
    private static final String LOG_TAG = "EdgeDevTools";
    private static final String INTENT_DISPATCHER =
        "com.google.android.apps.chrome.IntentDispatcher";
    private static final Object SERVER_LOCK = new Object();
    private static DevToolsServer server;

    private DevToolsMobile() {
    }

    public static void open(Object tab) {
        Context context = EdgeContext.fromTab(tab);
        if (context == null) {
            return;
        }

        Context applicationContext = context.getApplicationContext();
        Context safeContext = applicationContext != null ? applicationContext : context;
        int targetId = currentId(tab);
        String targetUrl = currentUrl(tab);
        Thread loader = new Thread(
            () -> openDevTools(safeContext, targetId, targetUrl),
            "EdgeDevToolsLoader"
        );
        loader.setDaemon(true);
        loader.start();
    }

    private static void openDevTools(
        Context context,
        int expectedId,
        String expectedUrl
    ) {
        try {
            DevToolsServer currentServer = getServer(context);
            JSONObject target = findTarget(
                currentServer,
                expectedId,
                expectedUrl
            );
            String locale = Locale.getDefault().getLanguage().equals("ru")
                ? "ru"
                : "en-US";
            String frontendUrl =
                "http://127.0.0.1:" + currentServer.getPort() +
                "/frontend/inspector.html" +
                "?ws=127.0.0.1:" + currentServer.getPort() +
                "/devtools/page/" + Uri.encode(target.getString("id")) +
                "&locale=" + locale;

            new Handler(Looper.getMainLooper()).post(
                () -> openFrontendTab(context, frontendUrl)
            );
        } catch (IOException | JSONException exception) {
            Log.e(LOG_TAG, "Could not open the Edge DevTools frontend", exception);
            showError(context);
        }
    }

    private static DevToolsServer getServer(Context context) throws IOException {
        synchronized (SERVER_LOCK) {
            if (server == null || server.isClosed()) {
                server = new DevToolsServer(context.getAssets(), Process.myPid());
            }
            return server;
        }
    }

    private static JSONObject findTarget(
        DevToolsServer devToolsServer,
        int expectedId,
        String expectedUrl
    ) throws IOException, JSONException {
        IOException lastFailure = null;

        for (int attempt = 0; attempt < 12; attempt++) {
            try {
                JSONObject target = selectTarget(
                    new JSONArray(devToolsServer.getTargets()),
                    expectedId,
                    expectedUrl
                );
                if (target != null) {
                    return target;
                }
            } catch (IOException exception) {
                lastFailure = exception;
            }

            try {
                Thread.sleep(250);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("DevTools loading was interrupted", exception);
            }
        }

        if (lastFailure != null) {
            throw lastFailure;
        }
        throw new IOException("No debuggable Edge tab found");
    }

    private static JSONObject selectTarget(
        JSONArray targets,
        int expectedId,
        String expectedUrl
    )
        throws JSONException {
        JSONObject firstPage = null;
        JSONObject urlMatch = null;

        for (int index = 0; index < targets.length(); index++) {
            JSONObject target = targets.getJSONObject(index);
            if (!"page".equals(target.optString("type"))) {
                continue;
            }

            if (firstPage == null) {
                firstPage = target;
            }
            if (
                expectedId >= 0 &&
                Integer.toString(expectedId).equals(target.optString("id"))
            ) {
                return target;
            }
            if (
                urlMatch == null &&
                expectedUrl != null &&
                !expectedUrl.isEmpty() &&
                expectedUrl.equals(target.optString("url"))
            ) {
                urlMatch = target;
            }
        }

        if (urlMatch != null) {
            return urlMatch;
        }
        return expectedId < 0 && (expectedUrl == null || expectedUrl.isEmpty())
            ? firstPage
            : null;
    }

    private static void openFrontendTab(Context context, String frontendUrl) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(frontendUrl))
                .addCategory(Intent.CATEGORY_BROWSABLE)
                .setClassName(context.getPackageName(), INTENT_DISPATCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(Browser.EXTRA_CREATE_NEW_TAB, true)
                .putExtra(Browser.EXTRA_APPLICATION_ID, context.getPackageName());
            context.startActivity(intent);
        } catch (RuntimeException exception) {
            Log.e(LOG_TAG, "Could not launch the Edge DevTools tab", exception);
            showError(context);
        }
    }

    private static void showError(Context context) {
        new Handler(Looper.getMainLooper()).post(() ->
            Toast.makeText(
                context,
                Locale.getDefault().getLanguage().equals("ru")
                    ? "Не удалось открыть DevTools. Повторите попытку."
                    : "Could not open DevTools. Try again.",
                Toast.LENGTH_LONG
            ).show()
        );
    }

    private static String currentUrl(Object tab) {
        if (tab == null) {
            return "";
        }

        try {
            Object url = tab.getClass().getMethod("getUrl").invoke(tab);
            if (url == null) {
                return "";
            }

            try {
                Object spec = url.getClass().getMethod("getSpec").invoke(url);
                if (spec instanceof String value) {
                    return value;
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Edge obfuscates GURL methods, while its toString remains useful.
            }

            String value = url.toString();
            if (value.startsWith("GURL(") && value.endsWith(")")) {
                return value.substring(5, value.length() - 1);
            }
            return value;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return "";
        }
    }

    private static int currentId(Object tab) {
        if (tab == null) {
            return -1;
        }

        try {
            Object value = tab.getClass().getMethod("getId").invoke(tab);
            return value instanceof Number number ? number.intValue() : -1;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return -1;
        }
    }
}
