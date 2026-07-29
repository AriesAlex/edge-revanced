(() => {
  const locale = new URLSearchParams(location.search).get('locale');
  if (locale === 'ru' || locale === 'en-US') {
    localStorage.setItem('language', JSON.stringify(locale));
  }
  localStorage.setItem('disable-locale-info-bar', 'true');
  localStorage.setItem('screencast-enabled', 'false');

  if (globalThis.__edgeRevancedMobileDevTools) return;
  globalThis.__edgeRevancedMobileDevTools = true;

  const baseCss = `
    html, body {
      overscroll-behavior: none !important;
      -webkit-text-size-adjust: 100% !important;
    }
    .root-view {
      top: var(--edge-devtools-offset-top, 0) !important;
      bottom: auto !important;
      height: var(--edge-devtools-height, 100vh) !important;
    }
    button,
    [role="button"],
    .soft-context-menu-item {
      min-height: 40px !important;
      touch-action: manipulation !important;
    }
    .toolbar-button {
      min-width: 40px !important;
      min-height: 40px !important;
    }
    input,
    textarea,
    select,
    [contenteditable="true"] {
      min-height: 36px !important;
      font-size: 14px !important;
    }
    .tree-outline li {
      min-height: 30px !important;
    }
    ::-webkit-scrollbar {
      width: 6px !important;
      height: 6px !important;
    }
  `;

  const mainTabsCss = `
    .tabbed-pane-shadow {
      display: flex !important;
      flex-direction: column !important;
    }
    .tabbed-pane-header {
      order: 2 !important;
      flex: 0 0 calc(48px + env(safe-area-inset-bottom)) !important;
      min-height: 48px !important;
      padding-bottom: env(safe-area-inset-bottom) !important;
      border-top: 1px solid var(--sys-color-divider) !important;
      border-bottom: 0 !important;
    }
    .tabbed-pane-content {
      order: 1 !important;
      min-height: 0 !important;
    }
    .tabbed-pane-header-contents {
      display: flex !important;
      flex: 1 1 auto !important;
      min-width: 0 !important;
      overflow: hidden !important;
    }
    .tabbed-pane-header-tabs {
      flex: 1 1 auto !important;
      min-width: 0 !important;
      overflow: hidden !important;
      touch-action: manipulation !important;
    }
    .tabbed-pane-header-tab {
      height: 48px !important;
      min-height: 48px !important;
      padding: 0 14px !important;
      font-size: 13px !important;
    }
    .tabbed-pane-header-tabs-drop-down-container {
      flex: 0 0 48px !important;
      min-width: 48px !important;
      justify-content: center !important;
    }
    .tabbed-pane-left-toolbar,
    .tabbed-pane-right-toolbar {
      flex: none !important;
    }
  `;

  const styledRoots = new WeakSet();

  const installStyle = root => {
    if (!root || styledRoots.has(root)) return;
    styledRoots.add(root);

    const host = root.host;
    let css = baseCss;
    if (host?.classList?.contains('main-tabbed-pane')) {
      css += mainTabsCss;
    }
    if (host?.localName === 'devtools-toolbar') {
      css += `
        :host {
          --toolbar-height: 40px !important;
          min-height: 40px !important;
          overflow-x: auto !important;
        }
      `;
    }
    if (host?.localName === 'devtools-button') {
      css += `
        :host {
          min-width: 40px !important;
          min-height: 40px !important;
        }
        button {
          min-width: 40px !important;
          min-height: 40px !important;
        }
      `;
    }

    try {
      const sheet = new CSSStyleSheet();
      sheet.replaceSync(css);
      root.adoptedStyleSheets = [...root.adoptedStyleSheets, sheet];
    } catch {
      const style = document.createElement('style');
      style.dataset.edgeRevancedMobileDevTools = 'true';
      style.textContent = css;
      if (root instanceof Document) {
        (root.head || root.documentElement).appendChild(style);
      } else {
        root.appendChild(style);
      }
    }
  };

  const observedRoots = new WeakSet();
  const scanNode = node => {
    if (node?.shadowRoot) scanRoot(node.shadowRoot);
    node?.querySelectorAll?.('*').forEach(element => {
      if (element.shadowRoot) scanRoot(element.shadowRoot);
    });
  };
  const scanRoot = root => {
    if (!root || observedRoots.has(root)) return;
    observedRoots.add(root);
    installStyle(root);
    scanNode(root);
    new MutationObserver(records => {
      for (const record of records) {
        record.addedNodes.forEach(scanNode);
      }
    }).observe(root, {childList: true, subtree: true});
  };

  const originalAttachShadow = Element.prototype.attachShadow;
  if (!originalAttachShadow.__edgeRevancedMobileDevTools) {
    const attachShadow = function(init) {
      const root = originalAttachShadow.call(this, init);
      queueMicrotask(() => scanRoot(root));
      return root;
    };
    Object.defineProperty(attachShadow, '__edgeRevancedMobileDevTools', {value: true});
    Element.prototype.attachShadow = attachShadow;
  }

  const updateViewport = () => {
    const viewport = globalThis.visualViewport;
    document.documentElement.style.setProperty(
      '--edge-devtools-height',
      `${Math.round(viewport?.height || innerHeight)}px`
    );
    document.documentElement.style.setProperty(
      '--edge-devtools-offset-top',
      `${Math.round(viewport?.offsetTop || 0)}px`
    );
  };

  globalThis.visualViewport?.addEventListener('resize', updateViewport);
  globalThis.visualViewport?.addEventListener('scroll', updateViewport);
  globalThis.addEventListener('orientationchange', updateViewport);
  updateViewport();
  scanRoot(document);
})();
