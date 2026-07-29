package app.revanced.extension.edge.extensions;

import app.revanced.extension.edge.EdgeContext;
import app.revanced.extension.edge.WebContentsJavaScript;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ChromeWebStore {
    private static final String WEB_STORE_HOST = "chromewebstore.google.com";
    private static final String UPDATE_URL_PREFIX =
        "https://clients2.google.com/service/update2/crx" +
        "?response=redirect" +
        "&prodversion=";
    private static final String UPDATE_URL_EXTENSION_PREFIX =
        "&acceptformat=crx2,crx3" +
        "&x=id%3D";
    private static final String UPDATE_URL_SUFFIX =
        "%26installsource%3Dondemand%26uc";
    private static final String INSTALL_FRAGMENT_PREFIX = "edge-revanced-install=";
    private static final String LOG_TAG = "EdgeRevancedCWS";
    private static final String CHROME_TABBED_ACTIVITY_CLASS =
        "org.chromium.chrome.browser.ChromeTabbedActivity";
    private static final String INSTALL_ACTION =
        "com.microsoft.edge.extensions.ACTION_INSTALL_EXTENSION_FOR_DEV_MODE";
    private static final String CRX_EXTRA =
        "com.microsoft.edge.extensions.EXTENSION_CRX";
    private static final int MAX_CRX_SIZE_BYTES = 256 * 1024 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 60_000;
    private static final byte[] CRX_MAGIC = {'C', 'r', '2', '4'};
    private static final Pattern VERSION_NAME = Pattern.compile("^(\\d+)\\.");
    private static final Pattern DETAIL_PATH = Pattern.compile(
        "^/detail/[^/]+/([a-p]{32})(?:/.*)?$"
    );
    private static final long[] INJECTION_DELAYS_MS = {
        0,
        250,
        750,
        1500,
        3000,
        6000
    };
    private static final Handler MAIN_HANDLER =
        new Handler(Looper.getMainLooper());
    private static final Set<String> INSTALLING_EXTENSION_IDS =
        ConcurrentHashMap.newKeySet();
    private static final String INSTALL_SCRIPT = """
        (() => {
          if (globalThis.__edgeRevancedInstallHook) return;
          globalThis.__edgeRevancedInstallHook = true;

          const labels = new Set([
            'Add to Chrome',
            'Add to Desktop',
            'Добавить в Chrome',
            'Установить',
            'Установить на компьютер'
          ]);
          const extensionId = () => {
            const parts = location.pathname.split('/');
            const detailIndex = parts.indexOf('detail');
            const id = parts[detailIndex + 2];
            return /^[a-p]{32}$/.test(id || '') ? id : null;
          };
          const installButton = () => [...document.querySelectorAll('button')]
            .find(button => labels.has(
              (button.querySelector('[jsname="V67aGc"]')?.textContent ||
                button.textContent ||
                '').trim()
            ));
          const enableInstallButton = () => {
            const button = installButton();
            if (!button) return;
            button.disabled = false;
            button.removeAttribute('disabled');
            button.removeAttribute('aria-disabled');
          };

          new MutationObserver(enableInstallButton).observe(document, {
            childList: true,
            subtree: true,
            attributes: true,
            attributeFilter: ['disabled', 'aria-disabled']
          });
          enableInstallButton();

          document.addEventListener('click', event => {
            const id = extensionId();
            const button = event.target?.closest?.('button');
            if (!id || !button || button !== installButton()) return;

            event.preventDefault();
            event.stopImmediatePropagation();
            location.hash = 'edge-revanced-install=' + id;
            location.reload();
          }, true);
        })();
        """;
    private static final String CLEANUP_SCRIPT = """
        (() => {
          if (!location.hash.startsWith('#edge-revanced-install=')) return;
          history.replaceState(null, '', location.pathname + location.search);
        })();
        """;

    private ChromeWebStore() {
    }

    public static void onUrlUpdated(Object tab, String url) {
        if (url == null) {
            return;
        }

        try {
            URI uri = URI.create(url);
            if (
                !"https".equalsIgnoreCase(uri.getScheme()) ||
                uri.getHost() == null ||
                !WEB_STORE_HOST.equals(uri.getHost().toLowerCase(Locale.ROOT))
            ) {
                return;
            }

            Matcher detailPage = DETAIL_PATH.matcher(uri.getPath());
            if (!detailPage.matches()) {
                return;
            }

            String extensionId = detailPage.group(1);
            String fragment = uri.getRawFragment();
            if (fragment != null && fragment.startsWith(INSTALL_FRAGMENT_PREFIX)) {
                String requestedExtensionId = fragment.substring(
                    INSTALL_FRAGMENT_PREFIX.length()
                );
                if (!extensionId.equals(requestedExtensionId)) {
                    return;
                }

                WebContentsJavaScript.inject(
                    tab,
                    CLEANUP_SCRIPT,
                    INJECTION_DELAYS_MS
                );
                startInstall(tab, extensionId);
                return;
            }

            WebContentsJavaScript.inject(
                tab,
                INSTALL_SCRIPT,
                INJECTION_DELAYS_MS
            );
        } catch (IllegalArgumentException ignored) {
            // Ignore malformed URLs reported by an in-progress navigation.
        }
    }

    private static void startInstall(Object tab, String extensionId) {
        if (!INSTALLING_EXTENSION_IDS.add(extensionId)) {
            return;
        }

        Context tabContext = EdgeContext.fromTab(tab);
        if (tabContext == null) {
            INSTALLING_EXTENSION_IDS.remove(extensionId);
            Log.e(LOG_TAG, "Could not obtain the current Edge context");
            return;
        }
        Context applicationContext = tabContext.getApplicationContext();
        Context context = applicationContext != null
            ? applicationContext
            : tabContext;

        showMessage(
            context,
            "Скачиваю расширение…",
            "Downloading extension…"
        );
        new Thread(
            () -> downloadAndInstall(context, extensionId),
            "EdgeRevancedCWS"
        ).start();
    }

    private static void downloadAndInstall(
        Context context,
        String extensionId
    ) {
        File temporaryFile = null;
        try {
            File extensionDirectory = context.getExternalFilesDir("extensions");
            if (
                extensionDirectory == null ||
                (!extensionDirectory.isDirectory() &&
                    !extensionDirectory.mkdirs())
            ) {
                throw new IOException("Could not create the extension directory");
            }

            File crxFile = new File(extensionDirectory, extensionId + ".crx");
            temporaryFile = new File(
                extensionDirectory,
                extensionId + ".crx.download"
            );
            downloadCrx(context, extensionId, temporaryFile);
            replaceFile(temporaryFile, crxFile);

            File downloadedCrx = crxFile;
            MAIN_HANDLER.post(
                () -> installCrx(context, extensionId, downloadedCrx)
            );
        } catch (Exception exception) {
            if (temporaryFile != null && temporaryFile.exists()) {
                temporaryFile.delete();
            }
            INSTALLING_EXTENSION_IDS.remove(extensionId);
            Log.e(LOG_TAG, "Chrome Web Store installation failed", exception);
            showMessage(
                context,
                "Не удалось скачать расширение",
                "Could not download the extension"
            );
        }
    }

    private static void downloadCrx(
        Context context,
        String extensionId,
        File destination
    ) throws IOException {
        String productVersion = browserProductVersion(context);
        HttpURLConnection connection = (HttpURLConnection) new URL(
            UPDATE_URL_PREFIX +
                productVersion +
                UPDATE_URL_EXTENSION_PREFIX +
                extensionId +
                UPDATE_URL_SUFFIX
        ).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/" +
                productVersion +
                " Mobile Safari/537.36"
        );

        try {
            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new IOException("CRX server returned HTTP " + responseCode);
            }

            int contentLength = connection.getContentLength();
            if (contentLength > MAX_CRX_SIZE_BYTES) {
                throw new IOException("CRX file is too large");
            }

            int written = 0;
            try (
                BufferedInputStream input = new BufferedInputStream(
                    connection.getInputStream()
                );
                BufferedOutputStream output = new BufferedOutputStream(
                    new FileOutputStream(destination)
                )
            ) {
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    written += read;
                    if (written > MAX_CRX_SIZE_BYTES) {
                        throw new IOException("CRX file is too large");
                    }
                    output.write(buffer, 0, read);
                }
            }

            if (written < CRX_MAGIC.length || !hasCrxMagic(destination)) {
                throw new IOException("Downloaded file is not a CRX package");
            }
        } finally {
            connection.disconnect();
        }
    }

    private static String browserProductVersion(Context context)
        throws IOException {
        try {
            String versionName = context
                .getPackageManager()
                .getPackageInfo(context.getPackageName(), 0)
                .versionName;
            Matcher version = VERSION_NAME.matcher(
                versionName == null ? "" : versionName
            );
            if (!version.find()) {
                throw new IOException(
                    "Could not determine Edge's product version"
                );
            }
            return version.group(1) + ".0.0.0";
        } catch (PackageManager.NameNotFoundException exception) {
            throw new IOException(
                "Could not read Edge's package version",
                exception
            );
        }
    }

    private static boolean hasCrxMagic(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file)) {
            for (byte expected : CRX_MAGIC) {
                if (input.read() != (expected & 0xff)) {
                    return false;
                }
            }
            return true;
        }
    }

    private static void replaceFile(File source, File destination)
        throws IOException {
        if (destination.exists() && !destination.delete()) {
            throw new IOException("Could not replace the previous CRX file");
        }
        if (!source.renameTo(destination)) {
            throw new IOException("Could not finish the CRX download");
        }
    }

    private static void installCrx(
        Context context,
        String extensionId,
        File crxFile
    ) {
        try {
            Intent intent = new Intent(INSTALL_ACTION);
            intent.setClassName(context, CHROME_TABBED_ACTIVITY_CLASS);
            intent.putExtra(CRX_EXTRA, crxFile.getAbsolutePath());
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (RuntimeException exception) {
            Log.e(LOG_TAG, "Could not start Edge's CRX installer", exception);
            showMessage(
                context,
                "Не удалось запустить установку расширения",
                "Could not start extension installation"
            );
        } finally {
            INSTALLING_EXTENSION_IDS.remove(extensionId);
        }
    }

    private static void showMessage(
        Context context,
        String russian,
        String english
    ) {
        String message = "ru".equals(Locale.getDefault().getLanguage())
            ? russian
            : english;
        MAIN_HANDLER.post(
            () -> Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        );
    }
}
