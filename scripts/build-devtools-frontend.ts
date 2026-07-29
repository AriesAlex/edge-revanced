import { createHash } from "node:crypto";
import { createReadStream } from "node:fs";
import {
    access,
    copyFile,
    mkdir,
    mkdtemp,
    readFile,
    rename,
    rm,
    writeFile,
} from "node:fs/promises";
import { createServer } from "node:net";
import { tmpdir } from "node:os";
import { dirname, join, relative, resolve, sep } from "node:path";
import { pathToFileURL } from "node:url";

const chromiumVersion = "152.0.7966.0";
const devToolsRevision = "859710d4ad2fa4c309dafc1fcf1c2a78b2d94499";
const chromeArchiveUrl =
    `https://storage.googleapis.com/chrome-for-testing-public/` +
    `${chromiumVersion}/win64/chrome-headless-shell-win64.zip`;
const expectedChromeArchiveBytes = 119_410_885;
const expectedChromeArchiveSha256 =
    "9ed80c81260184e6f2a8d8c7d2ea5c95fa318518177014b320a98945d1c0b654";
const expectedChromeExecutableSha256 =
    "0864d0ae21e1a102ec63b159519316bc386c9672e49572b23cbb715afe6aee50";
const russianLocaleUrl =
    `https://chrome-devtools-frontend.appspot.com/serve_file/` +
    `@${devToolsRevision}/core/i18n/locales/ru.json`;
const expectedRussianLocaleSha256 =
    "5a108065f2d36aa766dea673ee2bd2b7d83d3b8df340fdab221c74fdd732eca8";
const minimumFrontendFiles = 250;
const fetchConcurrency = 24;
const acornFrontendPath = "third_party/acorn/acorn.js";

const projectRoot = resolve(import.meta.dir, "..");
const frontendBuilderPath = resolve(
    import.meta.dir,
    "build-devtools-frontend.ts",
);
const mobileFrontendScriptPath = join(
    projectRoot,
    "scripts",
    "devtools-mobile.js",
);
const localDirectory = join(projectRoot, "local");
const chromeArchivePath = join(
    localDirectory,
    `chrome-headless-shell-${chromiumVersion}-win64.zip`,
);
const chromeDirectory = join(
    localDirectory,
    `chrome-headless-shell-${chromiumVersion}-extracted`,
);
const chromeExecutable = join(chromeDirectory, "chrome-headless-shell.exe");
const russianLocalePath = join(
    localDirectory,
    `chrome-devtools-frontend-${devToolsRevision}-ru.json`,
);
const stagingDirectory = join(
    localDirectory,
    `chrome-devtools-frontend-${chromiumVersion}`,
);
const archivePath = join(
    projectRoot,
    "patches",
    "src",
    "main",
    "resources",
    "edge-devtools-frontend.zip",
);
let parseJavaScript:
    | ((source: string, options: Record<string, unknown>) => unknown)
    | undefined;

function assertInside(child: string, parent: string): void {
    const relativePath = relative(resolve(parent), resolve(child));
    if (
        relativePath === "" ||
        relativePath === ".." ||
        relativePath.startsWith(`..${sep}`) ||
        resolve(relativePath) === relativePath
    ) {
        throw new Error(`Unsafe generated path: ${child}`);
    }
}

async function exists(path: string): Promise<boolean> {
    try {
        await access(path);
        return true;
    } catch {
        return false;
    }
}

async function sha256(path: string): Promise<string> {
    const hash = createHash("sha256");
    for await (const chunk of createReadStream(path)) {
        hash.update(chunk);
    }
    return hash.digest("hex");
}

async function run(
    command: string[],
    options: {
        cwd?: string;
        stdout?: "inherit" | "ignore" | "pipe";
        stderr?: "inherit" | "ignore" | "pipe";
    } = {},
): Promise<string> {
    const process = Bun.spawn(command, {
        cwd: options.cwd,
        stdout: options.stdout ?? "inherit",
        stderr: options.stderr ?? "inherit",
        windowsHide: true,
    });
    const output =
        process.stdout instanceof ReadableStream
            ? new Response(process.stdout).text()
            : Promise.resolve("");
    const error =
        process.stderr instanceof ReadableStream
            ? new Response(process.stderr).text()
            : Promise.resolve("");
    const [exitCode, outputText, errorText] = await Promise.all([
        process.exited,
        output,
        error,
    ]);
    if (exitCode !== 0) {
        throw new Error(
            `${command[0]} exited with ${exitCode}: ${errorText.trim()}`,
        );
    }
    return outputText;
}

async function verifyFile(
    path: string,
    expectedSha256: string,
    expectedBytes?: number,
): Promise<boolean> {
    if (!(await exists(path))) {
        return false;
    }
    if (
        expectedBytes !== undefined &&
        (await Bun.file(path).size) !== expectedBytes
    ) {
        return false;
    }
    return (await sha256(path)) === expectedSha256;
}

async function ensureChromeArchive(): Promise<void> {
    if (
        await verifyFile(
            chromeArchivePath,
            expectedChromeArchiveSha256,
            expectedChromeArchiveBytes,
        )
    ) {
        return;
    }

    await mkdir(localDirectory, { recursive: true });
    const downloadPath = `${chromeArchivePath}.download`;
    assertInside(downloadPath, localDirectory);
    await rm(downloadPath, { force: true });

    try {
        await run([
            "curl.exe",
            "--fail",
            "--location",
            "--show-error",
            "--output",
            downloadPath,
            chromeArchiveUrl,
        ]);
        if (
            !(await verifyFile(
                downloadPath,
                expectedChromeArchiveSha256,
                expectedChromeArchiveBytes,
            ))
        ) {
            throw new Error(
                `Unexpected SHA-256 or size for ${chromeArchiveUrl}`,
            );
        }
        await rm(chromeArchivePath, { force: true });
        await rename(downloadPath, chromeArchivePath);
    } finally {
        await rm(downloadPath, { force: true });
    }
}

async function ensureChrome(): Promise<void> {
    await ensureChromeArchive();
    if (
        await verifyFile(
            chromeExecutable,
            expectedChromeExecutableSha256,
        )
    ) {
        return;
    }

    assertInside(chromeDirectory, localDirectory);
    await rm(chromeDirectory, { recursive: true, force: true });
    await mkdir(chromeDirectory, { recursive: true });
    await run([
        "tar",
        "-xf",
        chromeArchivePath,
        "-C",
        chromeDirectory,
        "--strip-components=1",
    ]);

    if (
        !(await verifyFile(
            chromeExecutable,
            expectedChromeExecutableSha256,
        ))
    ) {
        throw new Error(`Unexpected Chrome executable: ${chromeExecutable}`);
    }
}

async function ensureRussianLocale(): Promise<void> {
    if (
        await verifyFile(
            russianLocalePath,
            expectedRussianLocaleSha256,
        )
    ) {
        return;
    }

    const downloadPath = `${russianLocalePath}.download`;
    assertInside(downloadPath, localDirectory);
    await rm(downloadPath, { force: true });
    try {
        await run([
            "curl.exe",
            "--fail",
            "--location",
            "--show-error",
            "--output",
            downloadPath,
            russianLocaleUrl,
        ]);
        if (
            !(await verifyFile(
                downloadPath,
                expectedRussianLocaleSha256,
            ))
        ) {
            throw new Error(
                `Unexpected SHA-256 for ${russianLocaleUrl}`,
            );
        }
        await rm(russianLocalePath, { force: true });
        await rename(downloadPath, russianLocalePath);
    } finally {
        await rm(downloadPath, { force: true });
    }
}

async function findFreePort(): Promise<number> {
    return new Promise((resolvePort, reject) => {
        const server = createServer();
        server.once("error", reject);
        server.listen(0, "127.0.0.1", () => {
            const address = server.address();
            if (address === null || typeof address === "string") {
                server.close();
                reject(new Error("Could not reserve a loopback port"));
                return;
            }
            const { port } = address;
            server.close((error) => {
                if (error) {
                    reject(error);
                } else {
                    resolvePort(port);
                }
            });
        });
    });
}

async function waitForDevToolsServer(baseUrl: string): Promise<void> {
    let lastError: unknown;
    for (let attempt = 0; attempt < 100; attempt++) {
        try {
            const response = await fetch(`${baseUrl}json/version`);
            if (response.ok) {
                const version = await response.json() as { Browser?: string };
                if (
                    version.Browser ===
                    `HeadlessChrome/${chromiumVersion}`
                ) {
                    return;
                }
                throw new Error(
                    `Unexpected browser version: ${version.Browser ?? ""}`,
                );
            }
        } catch (error) {
            lastError = error;
        }
        await Bun.sleep(100);
    }
    throw new Error(
        `Chrome DevTools server did not start: ${String(lastError)}`,
    );
}

function normalizeFrontendPath(
    value: string,
    basePath: string,
    frontendBaseUrl: string,
): string | null {
    if (
        value === "" ||
        value.startsWith("#") ||
        value.startsWith("data:") ||
        value.startsWith("blob:") ||
        value.startsWith("devtools:")
    ) {
        return null;
    }

    let resolvedUrl: URL;
    try {
        const normalizedValue = value.replace(/\\+/g, "/");
        resolvedUrl = new URL(
            normalizedValue,
            new URL(basePath, frontendBaseUrl),
        );
    } catch {
        return null;
    }
    if (resolvedUrl.origin !== new URL(frontendBaseUrl).origin) {
        return null;
    }

    const basePathname = new URL(frontendBaseUrl).pathname;
    if (!resolvedUrl.pathname.startsWith(basePathname)) {
        return null;
    }
    const path = decodeURIComponent(
        resolvedUrl.pathname.slice(basePathname.length),
    ).replaceAll("\\", "/");
    if (
        path === "" ||
        path.split("/").includes("..") ||
        !/^[A-Za-z0-9_@./-]+$/.test(path)
    ) {
        return null;
    }
    return path;
}

function objectValue(value: unknown): Record<string, unknown> | null {
    return typeof value === "object" && value !== null
        ? value as Record<string, unknown>
        : null;
}

function stringLiteral(node: unknown): string | null {
    const value = objectValue(node);
    return value?.type === "Literal" && typeof value.value === "string"
        ? value.value
        : null;
}

function isImportMetaUrl(node: unknown): boolean {
    const member = objectValue(node);
    const object = objectValue(member?.object);
    const property = objectValue(member?.property);
    const meta = objectValue(object?.meta);
    const imported = objectValue(object?.property);
    return (
        member?.type === "MemberExpression" &&
        object?.type === "MetaProperty" &&
        meta?.name === "import" &&
        imported?.name === "meta" &&
        property?.name === "url"
    );
}

function discoverJavaScriptDependencies(text: string, path: string): Set<string> {
    if (parseJavaScript === undefined) {
        throw new Error("The DevTools JavaScript parser is not initialized");
    }

    let program: unknown;
    try {
        program = parseJavaScript(text, {
            allowHashBang: true,
            ecmaVersion: "latest",
            sourceType: "module",
        });
    } catch (error) {
        throw new Error(`Could not parse DevTools module ${path}: ${String(error)}`);
    }

    const dependencies = new Set<string>();
    const addModuleDependency = (source: string): void => {
        if (
            source.startsWith("./") ||
            source.startsWith("../") ||
            source.startsWith("/")
        ) {
            dependencies.add(source);
        }
    };
    const resourceCalls = new Set([
        "fetch",
        "loadResourceIntoCache",
        "loadResourcePromise",
    ]);
    const pending: unknown[] = [program];
    const visited = new WeakSet<object>();

    while (pending.length > 0) {
        const current = pending.pop();
        const node = objectValue(current);
        if (node === null || visited.has(node)) {
            continue;
        }
        visited.add(node);

        if (
            node.type === "ImportDeclaration" ||
            node.type === "ExportAllDeclaration" ||
            node.type === "ExportNamedDeclaration"
        ) {
            const source = stringLiteral(node.source);
            if (source !== null) {
                addModuleDependency(source);
            }
        } else if (node.type === "ImportExpression") {
            const source = stringLiteral(node.source);
            if (source !== null) {
                addModuleDependency(source);
            }
        } else if (node.type === "NewExpression") {
            const callee = objectValue(node.callee);
            const argumentsList = Array.isArray(node.arguments)
                ? node.arguments
                : [];
            if (
                callee?.type === "Identifier" &&
                callee.name === "URL" &&
                argumentsList.length >= 2 &&
                isImportMetaUrl(argumentsList[1])
            ) {
                const source = stringLiteral(argumentsList[0]);
                if (source !== null) {
                    dependencies.add(source);
                }
            }
        } else if (node.type === "CallExpression") {
            const callee = objectValue(node.callee);
            const argumentsList = Array.isArray(node.arguments)
                ? node.arguments
                : [];
            if (
                callee?.type === "Identifier" &&
                typeof callee.name === "string" &&
                resourceCalls.has(callee.name) &&
                argumentsList.length > 0
            ) {
                const source = stringLiteral(argumentsList[0]);
                if (source !== null) {
                    dependencies.add(source);
                }
            }
        }

        for (const value of Object.values(node)) {
            if (Array.isArray(value)) {
                pending.push(...value);
            } else if (typeof value === "object" && value !== null) {
                pending.push(value);
            }
        }
    }

    return dependencies;
}

function discoverDependencies(
    path: string,
    bytes: Uint8Array,
    frontendBaseUrl: string,
): Set<string> {
    if (!/\.(?:css|html|js|svg)$/.test(path)) {
        return new Set();
    }

    const text = new TextDecoder().decode(bytes);
    const rawDependencies = path.endsWith(".js")
        ? discoverJavaScriptDependencies(text, path)
        : new Set<string>();
    const expressions: RegExp[] = [];
    if (path.endsWith(".html") || path.endsWith(".svg")) {
        expressions.push(/(?:src|href)=["']([^"']+)["']/g);
    }
    if (path.endsWith(".css")) {
        expressions.push(
            /@import\s+(?:url\()?["']?([^"')\s]+)["']?\)?/g,
            /url\(\s*["']?([^"')]+)["']?\s*\)/g,
        );
    }
    for (const expression of expressions) {
        for (const match of text.matchAll(expression)) {
            rawDependencies.add(match[1]);
        }
    }

    const dependencies = new Set<string>();
    for (const rawDependency of rawDependencies) {
        const dependency = normalizeFrontendPath(
            rawDependency,
            path,
            frontendBaseUrl,
        );
        if (dependency !== null) {
            dependencies.add(dependency);
        }
    }
    return dependencies;
}

async function mapConcurrent<T, R>(
    values: T[],
    mapper: (value: T) => Promise<R>,
): Promise<R[]> {
    const results = new Array<R>(values.length);
    let nextIndex = 0;
    await Promise.all(
        Array.from(
            { length: Math.min(fetchConcurrency, values.length) },
            async () => {
                while (nextIndex < values.length) {
                    const index = nextIndex++;
                    results[index] = await mapper(values[index]);
                }
            },
        ),
    );
    return results;
}

async function fetchAsset(
    path: string,
    frontendBaseUrl: string,
): Promise<{
    path: string;
    bytes: Uint8Array;
    dependencies: Set<string>;
}> {
    const response = await fetch(new URL(path, frontendBaseUrl));
    if (!response.ok) {
        throw new Error(`DevTools frontend returned ${response.status} for ${path}`);
    }

    const bytes = new Uint8Array(await response.arrayBuffer());
    // Chrome's DevTools data source returns an empty 200 response for an
    // unknown resource, so an empty body is equivalent to a missing file.
    if (bytes.length === 0) {
        throw new Error(`DevTools frontend returned an empty body for ${path}`);
    }
    return {
        path,
        bytes,
        dependencies: discoverDependencies(
            path,
            bytes,
            frontendBaseUrl,
        ),
    };
}

async function initializeJavaScriptParser(
    frontendBaseUrl: string,
): Promise<void> {
    const response = await fetch(new URL(acornFrontendPath, frontendBaseUrl));
    if (!response.ok) {
        throw new Error(
            `DevTools frontend returned ${response.status} for ${acornFrontendPath}`,
        );
    }
    const bytes = new Uint8Array(await response.arrayBuffer());
    if (bytes.length === 0) {
        throw new Error(
            `DevTools frontend returned an empty body for ${acornFrontendPath}`,
        );
    }

    const outputPath = join(
        stagingDirectory,
        ...acornFrontendPath.split("/"),
    );
    assertInside(outputPath, stagingDirectory);
    await mkdir(dirname(outputPath), { recursive: true });
    await writeFile(outputPath, bytes);

    const acorn = await import(`${pathToFileURL(outputPath).href}?edge-revanced`);
    if (typeof acorn.parse !== "function") {
        throw new Error("The bundled DevTools Acorn parser has no parse export");
    }
    parseJavaScript = acorn.parse;
}

async function buildFrontend(frontendBaseUrl: string): Promise<number> {
    assertInside(stagingDirectory, localDirectory);
    await rm(stagingDirectory, { recursive: true, force: true });
    await mkdir(stagingDirectory, { recursive: true });
    await initializeJavaScriptParser(frontendBaseUrl);

    const queued = new Set([
        "inspector.html",
        "core/i18n/locales/en-US.json",
    ]);
    const processed = new Set([acornFrontendPath]);
    let files = 1;
    let round = 0;

    while (true) {
        const pending = [...queued].filter((path) => !processed.has(path));
        if (pending.length === 0) {
            break;
        }
        pending.forEach((path) => processed.add(path));
        console.log(
            `DevTools frontend round ${++round}: ${pending.length} files`,
        );

        await mapConcurrent(pending, async (path) => {
            const asset = await fetchAsset(path, frontendBaseUrl);
            const outputPath = join(
                stagingDirectory,
                ...asset.path.split("/"),
            );
            assertInside(outputPath, stagingDirectory);
            await mkdir(dirname(outputPath), { recursive: true });
            await writeFile(outputPath, asset.bytes);
            files++;

            for (const dependency of asset.dependencies) {
                queued.add(dependency);
            }
        });
    }

    const localeOutputPath = join(
        stagingDirectory,
        "core",
        "i18n",
        "locales",
        "ru.json",
    );
    assertInside(localeOutputPath, stagingDirectory);
    await mkdir(dirname(localeOutputPath), { recursive: true });
    await copyFile(russianLocalePath, localeOutputPath);
    files++;

    const inspectorPath = join(stagingDirectory, "inspector.html");
    const inspectorEntry =
        '<script type="module" src="./entrypoints/inspector/inspector.js"></script>';
    const inspectorHtml = await readFile(inspectorPath, "utf8");
    if (!inspectorHtml.includes(inspectorEntry)) {
        throw new Error("Could not find the DevTools inspector entrypoint");
    }
    await writeFile(
        inspectorPath,
        inspectorHtml.replace(
            inspectorEntry,
            '<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">\n' +
                '<script type="module" src="./edge_mobile.js"></script>\n' +
                inspectorEntry,
        ),
    );

    await copyFile(
        mobileFrontendScriptPath,
        join(stagingDirectory, "edge_mobile.js"),
    );
    files++;

    const licenseOutputPath = join(stagingDirectory, "LICENSE");
    await copyFile(
        join(chromeDirectory, "LICENSE.headless_shell"),
        licenseOutputPath,
    );
    files++;

    const manifest = {
        chromiumVersion,
        devToolsRevision,
        chromeArchiveSha256: expectedChromeArchiveSha256,
        frontendBuilderSha256: await sha256(frontendBuilderPath),
        mobileFrontendSha256: await sha256(mobileFrontendScriptPath),
        files: files + 1,
        locales: ["en-US", "ru"],
    };
    await writeFile(
        join(stagingDirectory, "edge-revanced-manifest.json"),
        `${JSON.stringify(manifest, null, 2)}\n`,
    );
    files++;

    if (files < minimumFrontendFiles) {
        throw new Error(
            `Only ${files} DevTools files were discovered; refusing to create an incomplete archive`,
        );
    }
    return files;
}

async function archiveIsComplete(): Promise<boolean> {
    if (!(await exists(archivePath))) {
        return false;
    }

    try {
        const listing = await run(
            ["tar", "-tf", archivePath],
            { stdout: "pipe", stderr: "pipe" },
        );
        const files = listing
            .split(/\r?\n/)
            .filter((path) => path !== "" && !path.endsWith("/"));
        const manifest = JSON.parse(
            await run(
                [
                    "tar",
                    "-xOf",
                    archivePath,
                    "./edge-revanced-manifest.json",
                ],
                { stdout: "pipe", stderr: "pipe" },
            ),
        );
        return (
            files.length >= minimumFrontendFiles &&
            files.some((path) => path.endsWith("/inspector.html")) &&
            files.some((path) => path.endsWith("/edge_mobile.js")) &&
            files.some((path) =>
                path.endsWith("/entrypoints/inspector/inspector.js")
            ) &&
            files.some((path) =>
                path.endsWith("/core/i18n/locales/en-US.json")
            ) &&
            files.some((path) =>
                path.endsWith("/core/i18n/locales/ru.json")
            ) &&
            files.some((path) =>
                path.endsWith("/edge-revanced-manifest.json")
            ) &&
            manifest.chromiumVersion === chromiumVersion &&
            manifest.devToolsRevision === devToolsRevision &&
            manifest.chromeArchiveSha256 === expectedChromeArchiveSha256 &&
            manifest.frontendBuilderSha256 ===
                await sha256(frontendBuilderPath) &&
            manifest.mobileFrontendSha256 ===
                await sha256(mobileFrontendScriptPath)
        );
    } catch {
        return false;
    }
}

async function createArchive(): Promise<void> {
    await mkdir(dirname(archivePath), { recursive: true });
    await rm(archivePath, { force: true });
    await run([
        "tar",
        "-a",
        "-cf",
        archivePath,
        "-C",
        stagingDirectory,
        ".",
    ]);
}

async function main(): Promise<void> {
    if (await archiveIsComplete()) {
        console.log(`DevTools frontend archive is already complete: ${archivePath}`);
        return;
    }

    await Promise.all([
        ensureChrome(),
        ensureRussianLocale(),
    ]);

    const port = await findFreePort();
    const profileDirectory = await mkdtemp(
        join(tmpdir(), "edge-revanced-devtools-"),
    );
    const chrome = Bun.spawn(
        [
            chromeExecutable,
            "--headless",
            `--remote-debugging-port=${port}`,
            "--remote-debugging-address=127.0.0.1",
            "--no-first-run",
            "--disable-gpu",
            `--user-data-dir=${profileDirectory}`,
            "about:blank",
        ],
        {
            stdout: "ignore",
            stderr: "ignore",
            windowsHide: true,
        },
    );

    try {
        await run(
            [
                "powershell.exe",
                "-NoProfile",
                "-Command",
                `(Get-Process -Id ${chrome.pid}).PriorityClass = 'BelowNormal'`,
            ],
            { stdout: "ignore", stderr: "pipe" },
        );
        const baseUrl = `http://127.0.0.1:${port}/`;
        await waitForDevToolsServer(baseUrl);
        const files = await buildFrontend(`${baseUrl}devtools/`);
        await createArchive();
        console.log(`Bundled ${files} DevTools files: ${archivePath}`);
    } finally {
        chrome.kill();
        await chrome.exited;
        assertInside(profileDirectory, tmpdir());
        await rm(profileDirectory, { recursive: true, force: true });
    }
}

await main();
