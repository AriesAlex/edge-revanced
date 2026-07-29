import { mkdir, rename, rm } from "node:fs/promises";
import { dirname, resolve } from "node:path";

const PACKAGE_NAME = "com.microsoft.emmx.canary";
const ARCHITECTURE = "arm64-v8a";
// Public APKPure protocol used by EFF's MIT-licensed apkeep downloader.
const APKPURE_VERSIONS_URL =
    "https://api.pureapk.com/m/v3/cms/app_version" +
    `?hl=en-US&package_name=${PACKAGE_NAME}`;
const APKPURE_HEADERS = {
    "x-cv": "3172501",
    "x-sv": "29",
    "x-abis": ARCHITECTURE,
    "x-gp": "1",
};
const APK_DOWNLOAD_PATTERN =
    /(X?APKJ)..(https?:\/\/[-a-zA-Z0-9@:%._+~#=]{1,256}\.[a-zA-Z0-9()]{1,6}\b[-a-zA-Z0-9()@:%_+.~#?&/=]*)/s;

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

function getOption(name: string): string | undefined {
    const index = Bun.argv.indexOf(name);
    return index >= 0 ? Bun.argv[index + 1] : undefined;
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

async function fetchVersions(): Promise<string> {
    const response = await fetch(APKPURE_VERSIONS_URL, {
        headers: APKPURE_HEADERS,
    });
    if (!response.ok) {
        throw new Error(
            `APKPure version lookup failed with HTTP ${response.status}`,
        );
    }

    return new TextDecoder().decode(await response.arrayBuffer());
}

function metadata(version: string): Metadata {
    return {
        packageName: PACKAGE_NAME,
        version,
        architecture: ARCHITECTURE,
        source: "APKPure",
    };
}

function latestVersion(response: string): string {
    const versions = Array.from(
        new Set(response.match(/\b\d+(?:\.\d+){3}\b/g) ?? []),
    ).sort(compareVersions);
    const version = versions.at(-1);
    if (!version) {
        throw new Error("APKPure did not return an Edge Canary version");
    }
    return version;
}

function downloadUrl(response: string, version: string): string {
    if (!/^\d+(?:\.\d+){3}$/.test(version)) {
        throw new Error(`Invalid Edge Canary version: ${version}`);
    }

    const escapedVersion = version.replaceAll(".", "\\.");
    const versionRecord = response.match(
        new RegExp(`[^\\d]${escapedVersion}:(.+)`, "s"),
    )?.[1];
    if (!versionRecord) {
        throw new Error(`Edge Canary ${version} is not available from APKPure`);
    }

    const match = versionRecord.match(APK_DOWNLOAD_PATTERN);
    if (!match || match[1] !== "APKJ") {
        throw new Error(
            `APKPure did not return a monolithic APK for Edge Canary ${version}`,
        );
    }
    return match[2];
}

async function download(
    outputPath: string,
    requestedVersion?: string,
): Promise<Metadata> {
    const versionsResponse = await fetchVersions();
    const version = requestedVersion ?? latestVersion(versionsResponse);
    const url = downloadUrl(versionsResponse, version);
    const destination = resolve(outputPath);
    const temporaryPath = `${destination}.download`;
    await mkdir(dirname(destination), { recursive: true });
    await rm(temporaryPath, { force: true });

    try {
        console.error(
            `Downloading ${PACKAGE_NAME} ${version} (${ARCHITECTURE})...`,
        );
        const response = await fetch(url);
        if (!response.ok) {
            throw new Error(
                `Edge Canary download failed with HTTP ${response.status}`,
            );
        }
        await Bun.write(temporaryPath, response);
        await rm(destination, { force: true });
        await rename(temporaryPath, destination);
        return metadata(version);
    } finally {
        await rm(temporaryPath, { force: true });
    }
}

const command = Bun.argv[2];
if (command === "metadata") {
    const response = await fetchVersions();
    console.log(JSON.stringify(metadata(latestVersion(response))));
} else if (command === "download") {
    const output = getOption("--output") ?? usage();
    console.log(JSON.stringify(await download(output, getOption("--version"))));
} else {
    usage();
}
