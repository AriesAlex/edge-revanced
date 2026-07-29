package app.revanced.extension.edge.account;

import app.revanced.extension.edge.WebContentsJavaScript;

import java.net.URI;
import java.util.Locale;

public final class MicrosoftAccountNotice {
    private static final long[] INJECTION_DELAYS_MS = {0, 250, 750, 1500, 3000};
    private static final String DISMISS_SCRIPT = """
        (() => {
          if (globalThis.__edgeRevancedAccountNoticeHook) return;
          globalThis.__edgeRevancedAccountNoticeHook = true;

          const normalize = value => (value || '')
            .replace(/\\s+/g, ' ')
            .trim()
            .toLocaleLowerCase();
          const titles = new Set([
            'a quick note about your microsoft account',
            'a brief note about your microsoft account',
            'краткое примечание о вашей учетной записи майкрософт',
            'краткое примечание о вашей учётной записи майкрософт'
          ]);
          const acknowledgements = new Set(['ok', 'ок']);

          let observer;
          const dismiss = () => {
            const heading = [...document.querySelectorAll('h1, h2, h3, [role="heading"]')]
              .find(element => titles.has(normalize(element.textContent)));
            if (!heading) return false;

            const button = [
              ...document.querySelectorAll(
                'button, input[type="button"], input[type="submit"], [role="button"]'
              )
            ].find(element => acknowledgements.has(normalize(
              element.textContent ||
              element.value ||
              element.getAttribute('aria-label')
            )));
            if (!button) return false;

            button.click();
            observer?.disconnect();
            return true;
          };

          if (dismiss()) return;
          observer = new MutationObserver(dismiss);
          observer.observe(document, {childList: true, subtree: true});
          setTimeout(() => observer.disconnect(), 15000);
        })();
        """;

    private MicrosoftAccountNotice() {
    }

    public static void onUrlUpdated(Object tab, String url) {
        if (!isMicrosoftPage(url)) {
            return;
        }

        WebContentsJavaScript.inject(
            tab,
            DISMISS_SCRIPT,
            INJECTION_DELAYS_MS
        );
    }

    private static boolean isMicrosoftPage(String url) {
        if (url == null) {
            return false;
        }

        try {
            String host = URI.create(url).getHost();
            if (host == null) {
                return false;
            }

            String normalizedHost = host.toLowerCase(Locale.ROOT);
            return isDomain(normalizedHost, "live.com") ||
                isDomain(normalizedHost, "microsoft.com") ||
                isDomain(normalizedHost, "microsoftonline.com") ||
                isDomain(normalizedHost, "office.com") ||
                isDomain(normalizedHost, "outlook.com");
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static boolean isDomain(String host, String domain) {
        return host.equals(domain) || host.endsWith("." + domain);
    }
}
