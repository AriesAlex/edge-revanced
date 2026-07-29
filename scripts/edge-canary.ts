import { createHash } from "node:crypto";
import { mkdir, readdir, rename, rm, stat } from "node:fs/promises";
import { dirname, join, resolve } from "node:path";

const PACKAGE_NAME = "com.microsoft.emmx.canary";
const ARCHITECTURE = "arm64-v8a";
const APKEEP_VERSION = "1.0.0";
const APKEEP_SHA256 =
    "9e321bab9fcc6bab6f6a779ae21d3611dfe6bf3bbecc13ffc9e57aa2db044e7f";
const APKEEP_URL =
    `https://github.com/EFForg/apkeep/releases/download/${APKEEP_VERSION}/` +
    "apkeep-x86_64-pc-windows-msvc.exe";
const projectRoot = resolve(import.meta.dir, "..");
const apkeepPath = join(projectRoot, "local", `apkeep-${APKEEP_VERSION}.exe`);

type Metadata = {
    packageName: string;
    version: string;
    architecture: string;
    source: string;
};

function usage(): never {
    throw new Error(
        "Usage: bun scripts/edge-canary.ts metadata | " +
            "download --output <path-to-apk> [--version <version>]",
    );
}

async function sha256(path: string): Promise<string> {
    const contents = await Bun.file(path).arrayBuffer();
    return createHash("sha256").update(new Uint8Array(contents)).digest("hex");
}

async function fileExists(path: string): Promise<boolean> {
    try {
        return (await stat(path)).isFile();
    } catch {
        return false;
    }
}

async function ensureApkeep(): Promise<void> {
    if (await fileExists(apkeepPath)) {
        if ((await sha256(apkeepPath)) === APKEEP_SHA256) {
            return;
        }
        await rm(apkeepPath, { force: true });
    }

    await mkdir(dirname(apkeepPath), { recursive: true });
    const temporaryPath = `${apkeepPath}.download`;
    await rm(temporaryPath, { force: true });

    try {
        console.error(`Downloading and verifying apkeep ${APKEEP_VERSION}...`);
        const response = await fetch(APKEEP_URL);
        if (!response.ok) {
            throw new Error(
                `apkeep download failed with HTTP ${response.status}`,
            );
        }
        await Bun.write(temporaryPath, await response.arrayBuffer());
        const actualSha256 = await sha256(temporaryPath);
        if (actualSha256 !== APKEEP_SHA256) {
            throw new Error(`Unexpected apkeep SHA-256: ${actualSha256}`);
        }
        await rename(temporaryPath, apkeepPath);
    } finally {
        await rm(temporaryPath, { force: true });
    }
}

async function runApkeep(args: string[]): Promise<string> {
    await ensureApkeep();
    const process = Bun.spawn([apkeepPath, ...args], {
        stdout: "pipe",
        stderr: "pipe",
    });
    const [stdout, stderr, exitCode] = await Promise.all([
        new Response(process.stdout).text(),
        new Response(process.stderr).text(),
        process.exited,
    ]);
    if (exitCode !== 0) {
        throw new Error(
            `apkeep exited with code ${exitCode}: ${stderr.trim() || stdout.trim()}`,
        );
    }
    return stdout;
}

function compareVersions(left: string, right: string): number {
    const leftParts = left.split(".").map(Number);
    const rightParts = right.split(".").map(Number);
    for (
        let index = 0;
        index < Math.max(leftParts.length, rightParts.length);
        index++
    ) {
        const difference = (leftParts[index] ?? 0) - (rightParts[index] ?? 0);
        if (difference !== 0) {
            return difference;
        }
    }
    return 0;
}

async function getMetadata(): Promise<Metadata> {
    const output = await runApkeep([
        "-l",
        "-a",
        PACKAGE_NAME,
        "-o",
        `arch=${ARCHITECTURE}`,
    ]);
    const versions = Array.from(
        new Set(output.match(/\b\d+(?:\.\d+){3}\b/g) ?? []),
    ).sort(compareVersions);
    const version = versions.at(-1);
    if (!version) {
        throw new Error("apkeep did not return an Edge Canary version");
    }

    return {
        packageName: PACKAGE_NAME,
        version,
        architecture: ARCHITECTURE,
        source: "APKPure via apkeep",
    };
}

function getOption(name: string): string | undefined {
    const index = Bun.argv.indexOf(name);
    return index >= 0 ? Bun.argv[index + 1] : undefined;
}

async function download(
    outputPath: string,
    requestedVersion?: string,
): Promise<Metadata> {
    if (
        requestedVersion !== undefined &&
        !/^\d+(?:\.\d+){3}$/.test(requestedVersion)
    ) {
        throw new Error(`Invalid Edge Canary version: ${requestedVersion}`);
    }
    const metadata = requestedVersion
        ? {
              packageName: PACKAGE_NAME,
              version: requestedVersion,
              architecture: ARCHITECTURE,
              source: "APKPure via apkeep",
          }
        : await getMetadata();
    const destination = resolve(outputPath);
    const temporaryDirectory = join(
        projectRoot,
        "local",
        `edge-canary-download-${crypto.randomUUID()}`,
    );
    await mkdir(temporaryDirectory, { recursive: true });

    try {
        console.error(
            `Downloading ${PACKAGE_NAME} ${metadata.version} (${ARCHITECTURE})...`,
        );
        await runApkeep([
            "-a",
            `${PACKAGE_NAME}@${metadata.version}`,
            "-o",
            `arch=${ARCHITECTURE}`,
            temporaryDirectory,
        ]);
        const apkFiles = (await readdir(temporaryDirectory)).filter((name) =>
            name.toLowerCase().endsWith(".apk"),
        );
        if (apkFiles.length !== 1) {
            throw new Error(
                `Expected one downloaded APK, found ${apkFiles.length}`,
            );
        }

        await mkdir(dirname(destination), { recursive: true });
        await rm(destination, { force: true });
        await rename(join(temporaryDirectory, apkFiles[0]), destination);
        return metadata;
    } finally {
        await rm(temporaryDirectory, { recursive: true, force: true });
    }
}

const command = Bun.argv[2];
if (command === "metadata") {
    console.log(JSON.stringify(await getMetadata()));
} else if (command === "download") {
    const output = getOption("--output") ?? usage();
    console.log(JSON.stringify(await download(output, getOption("--version"))));
} else {
    usage();
}
