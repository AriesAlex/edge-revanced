package app.revanced.patches.edge

import app.revanced.patcher.accessFlags
import app.revanced.patcher.custom
import app.revanced.patcher.definingClass
import app.revanced.patcher.extensions.ExternalLabel
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.addInstructionsWithLabels
import app.revanced.patcher.extensions.fieldReference
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.extensions.instructions
import app.revanced.patcher.extensions.methodReference
import app.revanced.patcher.extensions.replaceInstruction
import app.revanced.patcher.extensions.stringReference
import app.revanced.patcher.firstMethodDeclaratively
import app.revanced.patcher.immutableClassDef
import app.revanced.patcher.name
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.patch.resourcePatch
import app.revanced.patcher.patch.stringOption
import app.revanced.patcher.returnType
import app.revanced.patcher.strings
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.util.zip.ZipInputStream

private const val EDGE_CANARY_PACKAGE = "com.microsoft.emmx.canary"
private const val EDGE_CANARY_SIDE_BY_SIDE_PACKAGE = "$EDGE_CANARY_PACKAGE.revanced"
private const val EDGE_REVANCED_NAME = "Edge ReVanced"
private const val EDGE_CANARY_ICON = "@mipmap/edge_app_icon_canary"
private const val EDGE_STABLE_ICON = "@mipmap/edge_app_icon"
private const val EDGE_TABBED_ACTIVITY =
    "org.chromium.chrome.browser.ChromeTabbedActivity"
private const val EDGE_CANARY_SPLASH_ICON =
    "@mipmap/edge_app_icon_foreground_canary"
private const val EDGE_SPLASH_ARTWORK = "edge-revanced-splash.png"
private const val EDGE_SYSTEM_SPLASH_ARTWORK =
    "edge-revanced-system-splash.png"
private const val EDGE_SPLASH_DRAWABLE = "edge_revanced_splash.png"
private const val EDGE_SYSTEM_SPLASH_DRAWABLE =
    "edge_revanced_system_splash.png"
private const val ANDROID_FRAMEWORK_APK_ENV = "EDGE_REVANCED_ANDROID_FRAMEWORK_APK"
private const val ANDROID_FRAMEWORK_DIRECTORY_ENV = "EDGE_REVANCED_ANDROID_FRAMEWORK_DIRECTORY"
private const val DEVTOOLS_OVERFLOW_ID = 42
private const val DEVTOOLS_EXTENSION_CLASS =
    "Lapp/revanced/extension/edge/devtools/DevToolsMobile;"
private const val DEVTOOLS_FRONTEND_ARCHIVE = "edge-devtools-frontend.zip"
private const val DEVTOOLS_FRONTEND_ASSET_DIRECTORY = "assets/edge_devtools"
private const val EDGE_EXTENSION_INSTALL_ACTION =
    "com.microsoft.edge.extensions.ACTION_INSTALL_EXTENSION_FOR_DEV_MODE"
private const val EDGE_EXTENSION_CRX_EXTRA =
    "com.microsoft.edge.extensions.EXTENSION_CRX"
private const val CHROME_WEB_STORE_EXTENSION_CLASS =
    "Lapp/revanced/extension/edge/extensions/ChromeWebStore;"
private const val WEB_CONTENTS_CLASS =
    "Lorg/chromium/content_public/browser/WebContents;"
private const val WEB_CONTENTS_JAVASCRIPT_EXTENSION_CLASS =
    "Lapp/revanced/extension/edge/WebContentsJavaScript;"
private const val EVALUATE_JAVASCRIPT_METHOD_PLACEHOLDER =
    "__EDGE_EVALUATE_JAVASCRIPT_METHOD__"
private const val MICROSOFT_ACCOUNT_NOTICE_EXTENSION_CLASS =
    "Lapp/revanced/extension/edge/account/MicrosoftAccountNotice;"
private const val TAB_SWITCHER_EXTENSION_CLASS =
    "Lapp/revanced/extension/edge/tabs/TabSwitcherMobile;"
private val patchClassLoader = object {}.javaClass.classLoader

private fun String.toSmaliString(): String = buildString(length) {
    this@toSmaliString.forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(character)
        }
    }
}

private fun isWebUrl(value: String?): Boolean {
    if (value == null) return false

    return runCatching {
        val uri = URI(value)
        (uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrBlank()
    }.getOrDefault(false)
}

private fun Element.hasDimension(name: String, expected: Float): Boolean {
    val value = getAttribute(name)
        .removeSuffix("dip")
        .removeSuffix("dp")
        .toFloatOrNull()
    return value == expected
}

private fun Document.isEdgeSplashDrawable(): Boolean {
    if (documentElement.tagName != "layer-list") return false

    val bitmaps = getElementsByTagName("bitmap")
    if (bitmaps.length != 2) return false

    val bitmapElements = (0 until bitmaps.length).map {
        bitmaps.item(it) as Element
    }
    val centeredLogo = bitmapElements.singleOrNull {
        it.getAttribute("android:gravity") == "center" &&
            it.hasDimension("android:width", 142f) &&
            it.hasDimension("android:height", 142f)
    }
    val microsoftLogo = bitmapElements.singleOrNull {
        it.getAttribute("android:gravity") == "bottom" &&
            it.hasDimension("android:width", 101f) &&
            it.hasDimension("android:height", 23f)
    }
    if (centeredLogo == null || microsoftLogo == null) return false

    val microsoftItem = microsoftLogo.parentNode as? Element ?: return false
    return microsoftItem.tagName == "item" &&
        microsoftItem.hasDimension("android:bottom", 70f)
}

private val androidFrameworkPatch = resourcePatch {
    compatibleWith(EDGE_CANARY_PACKAGE)

    apply {
        val androidFrameworkApk = System.getenv(ANDROID_FRAMEWORK_APK_ENV)
            ?.let(::File)
            ?.takeIf(File::isFile)
            ?: error("Android SDK 37 framework APK is required")
        val frameworkDirectory = System.getenv(ANDROID_FRAMEWORK_DIRECTORY_ENV)
            ?.let(::File)
            ?: error("ReVanced framework directory is not configured")

        check(frameworkDirectory.isDirectory || frameworkDirectory.mkdirs()) {
            "Could not create the ReVanced framework directory"
        }
        val installedFrameworkApk = frameworkDirectory.resolve("1.apk")
        if (
            !installedFrameworkApk.isFile ||
            Files.mismatch(
                androidFrameworkApk.toPath(),
                installedFrameworkApk.toPath(),
            ) != -1L
        ) {
            androidFrameworkApk.copyTo(installedFrameworkApk, overwrite = true)
        }
    }
}

@Suppress("unused")
val edgeRevancedBrandingPatch = resourcePatch(
    name = "Edge ReVanced branding",
    description = "Переименовывает приложение, заменяет Canary-иконку и устанавливает фирменный splash Edge ReVanced.",
) {
    compatibleWith(EDGE_CANARY_PACKAGE)
    dependsOn(androidFrameworkPatch)

    apply {
        lateinit var tabbedActivityTheme: String
        document("AndroidManifest.xml").use { document ->
            val applications = document.getElementsByTagName("application")
            check(applications.length == 1) {
                "Expected exactly one application element"
            }
            val application = applications.item(0) as Element
            val sourceIcon = application.getAttribute("android:icon")
            check(sourceIcon == EDGE_CANARY_ICON) {
                "Unexpected Edge Canary application icon: $sourceIcon"
            }

            application.setAttribute("android:label", EDGE_REVANCED_NAME)
            application.setAttribute("android:icon", EDGE_STABLE_ICON)

            val tabbedActivities = document.getElementsByTagName("activity")
                .let { activities ->
                    (0 until activities.length)
                        .map { activities.item(it) as Element }
                        .filter {
                            it.getAttribute("android:name") == EDGE_TABBED_ACTIVITY
                        }
                }
            check(tabbedActivities.size == 1) {
                "Expected exactly one Edge tabbed activity"
            }
            tabbedActivityTheme = tabbedActivities.single()
                .getAttribute("android:theme")
                .removePrefix("@style/")
                .also {
                    check(it.isNotBlank()) {
                        "The Edge tabbed activity has no theme"
                    }
                }
        }

        val splashArtwork = this["res/drawable-nodpi/$EDGE_SPLASH_DRAWABLE", false]
        check(splashArtwork.parentFile.mkdirs() || splashArtwork.parentFile.isDirectory) {
            "Could not create the Edge ReVanced splash resource directory"
        }
        patchClassLoader.getResourceAsStream(EDGE_SPLASH_ARTWORK).use { artwork ->
            checkNotNull(artwork) {
                "Missing $EDGE_SPLASH_ARTWORK"
            }
            splashArtwork.outputStream().use(artwork::copyTo)
        }
        val systemSplashArtwork =
            this["res/drawable-nodpi/$EDGE_SYSTEM_SPLASH_DRAWABLE", false]
        patchClassLoader.getResourceAsStream(EDGE_SYSTEM_SPLASH_ARTWORK)
            .use { artwork ->
                checkNotNull(artwork) {
                    "Missing $EDGE_SYSTEM_SPLASH_ARTWORK"
                }
                systemSplashArtwork.outputStream().use(artwork::copyTo)
            }

        val drawableDirectory = this["res/drawable", false]
        val edgeSplashDrawables = drawableDirectory
            .listFiles { file -> file.isFile && file.extension == "xml" }
            .orEmpty()
            .filter { file ->
                document("res/drawable/${file.name}").use(Document::isEdgeSplashDrawable)
            }
        check(edgeSplashDrawables.size == 1) {
            "Expected exactly one Edge splash drawable, found ${edgeSplashDrawables.size}"
        }
        val splashBackground = edgeSplashDrawables.single()
        document("res/drawable/${splashBackground.name}").use { document ->
            val root = document.documentElement
            val backgroundItems = (0 until root.childNodes.length)
                .mapNotNull { root.childNodes.item(it) as? Element }
                .filter {
                    it.tagName == "item" &&
                        it.hasAttribute("android:drawable")
                }
            check(backgroundItems.size == 1) {
                "Expected exactly one Edge splash background item"
            }
            backgroundItems.single().setAttribute(
                "android:drawable",
                "@android:color/black",
            )

            val bitmaps = document.getElementsByTagName("bitmap")
            val oldItems = (0 until bitmaps.length).map {
                bitmaps.item(it).parentNode
            }
            oldItems.forEach(root::removeChild)

            val artworkItem = document.createElement("item")
            val artworkBitmap = document.createElement("bitmap").apply {
                setAttribute("android:gravity", "bottom|center_horizontal")
                setAttribute("android:src", "@drawable/edge_revanced_splash")
            }
            artworkItem.appendChild(artworkBitmap)
            root.appendChild(artworkItem)
        }

        document("res/values/styles.xml").use { document ->
            val matchingStyles = document.getElementsByTagName("style")
                .let { styles ->
                    (0 until styles.length)
                        .map { styles.item(it) as Element }
                        .filter {
                            it.getAttribute("name") == tabbedActivityTheme
                        }
                }
            check(matchingStyles.size == 1) {
                "Could not uniquely identify the Edge tabbed activity theme"
            }
            val themeItems = matchingStyles.single()
                .getElementsByTagName("item")
                .let { items ->
                    (0 until items.length).map { items.item(it) as Element }
                }
            val systemSplashIcon = themeItems.filter {
                it.textContent.trim() == EDGE_CANARY_SPLASH_ICON
            }
            check(systemSplashIcon.size == 1) {
                "Could not uniquely identify the Android system splash icon"
            }
            systemSplashIcon.single().textContent =
                "@drawable/edge_revanced_system_splash"

            val systemSplashBackground = themeItems.filter {
                it.textContent.trim().startsWith("@color/")
            }
            check(systemSplashBackground.size == 1) {
                "Could not uniquely identify the Android system splash background"
            }
            systemSplashBackground.single().textContent = "@android:color/black"
        }

        val splashBackgroundReference =
            "@drawable/${splashBackground.nameWithoutExtension}"
        val layoutDirectories = this["res", false]
            .listFiles { file ->
                file.isDirectory && file.name.startsWith("layout")
            }
            .orEmpty()
        var patchedSplashLayouts = 0
        layoutDirectories.forEach { directory ->
            directory
                .listFiles { file -> file.isFile && file.extension == "xml" }
                .orEmpty()
                .filter {
                    it.readText().contains("@id/edge_splash_screen_view")
                }
                .forEach { file ->
                    val relativePath = "res/${directory.name}/${file.name}"
                    document(relativePath).use { document ->
                        val elements = document.getElementsByTagName("*")
                            .let { nodes ->
                                (0 until nodes.length)
                                    .map { nodes.item(it) as Element }
                            }
                        val splashContainers = elements.filter {
                            it.getAttribute("android:id") ==
                                "@id/edge_splash_screen_view"
                        }
                        if (splashContainers.isEmpty()) {
                            return@use
                        }
                        check(splashContainers.size == 1) {
                            "Expected one splash container in $relativePath"
                        }
                        val splashIcons = elements.filter {
                            it.getAttribute("android:id") in setOf(
                                "@id/splash_edge_icon",
                                "@id/splash_bottom_microsoft_logo",
                            )
                        }
                        check(splashIcons.size == 2) {
                            "Expected both legacy splash icons in $relativePath"
                        }

                        splashContainers.single().setAttribute(
                            "android:background",
                            splashBackgroundReference,
                        )
                        splashIcons.forEach {
                            it.setAttribute("android:visibility", "invisible")
                        }
                        patchedSplashLayouts++
                    }
                }
        }
        check(patchedSplashLayouts > 0) {
            "Could not find any Edge splash layouts"
        }
    }
}

@Suppress("unused")
val sideBySideInstallPatch = resourcePatch(
    name = "Side-by-side test installation",
    description = "Устанавливает мод рядом с официальным Edge Canary, не удаляя его данные.",
    use = false,
) {
    compatibleWith(EDGE_CANARY_PACKAGE)
    dependsOn(androidFrameworkPatch)

    apply {
        document("AndroidManifest.xml").use { document ->
            val manifest = document.documentElement
            check(manifest.getAttribute("package") == EDGE_CANARY_PACKAGE) {
                "Unexpected Edge package name"
            }
            manifest.setAttribute("package", EDGE_CANARY_SIDE_BY_SIDE_PACKAGE)

            val elements = manifest.getElementsByTagName("*")
            for (elementIndex in 0 until elements.length) {
                val element = elements.item(elementIndex) as Element
                val attributes = element.attributes

                for (attributeIndex in 0 until attributes.length) {
                    val attribute = attributes.item(attributeIndex)
                    if (attribute.nodeValue.contains(EDGE_CANARY_PACKAGE)) {
                        attribute.nodeValue = attribute.nodeValue.replace(
                            EDGE_CANARY_PACKAGE,
                            EDGE_CANARY_SIDE_BY_SIDE_PACKAGE,
                        )
                    }
                }
            }
        }
    }
}

private val edgeMobileExtensionPatch = bytecodePatch {
    compatibleWith(EDGE_CANARY_PACKAGE)
    extendWith("extensions/edge/mobile.rve")
}

private val webContentsJavaScriptPatch = bytecodePatch {
    compatibleWith(EDGE_CANARY_PACKAGE)
    dependsOn(edgeMobileExtensionPatch)

    apply {
        val evaluateJavaScript = firstMethodDeclaratively {
            definingClass(WEB_CONTENTS_CLASS)
            returnType("V")
            custom {
                parameters.size == 2 &&
                    parameters[0].type == "Ljava/lang/String;" &&
                    immutableClassDef.methods.count { method ->
                        method.returnType == "V" &&
                            method.parameters.size == 2 &&
                            method.parameters[0].type == "Ljava/lang/String;"
                    } == 1
            }
        }
        val evaluateBridge = firstMethodDeclaratively {
            definingClass(WEB_CONTENTS_JAVASCRIPT_EXTENSION_CLASS)
            name("evaluate")
            returnType("V")
            parameterTypes("Ljava/lang/Object;", "Ljava/lang/String;")
            strings(EVALUATE_JAVASCRIPT_METHOD_PLACEHOLDER)
        }
        val placeholderIndices = evaluateBridge.instructions
            .mapIndexedNotNull { index, instruction ->
                index.takeIf {
                    instruction.stringReference?.string ==
                        EVALUATE_JAVASCRIPT_METHOD_PLACEHOLDER
                }
            }
        check(placeholderIndices.size == 1) {
            "Could not locate the JavaScript bridge method placeholder"
        }
        val placeholderIndex = placeholderIndices.single()
        val placeholderRegister = (
            evaluateBridge.getInstruction(placeholderIndex) as OneRegisterInstruction
        ).registerA

        evaluateBridge.replaceInstruction(
            placeholderIndex,
            """const-string v$placeholderRegister, "${evaluateJavaScript.name.toSmaliString()}"""",
        )
    }
}

private val devToolsFrontendPatch = resourcePatch {
    compatibleWith(EDGE_CANARY_PACKAGE)
    dependsOn(androidFrameworkPatch)

    apply {
        val frontendDirectory = this[DEVTOOLS_FRONTEND_ASSET_DIRECTORY, false]
        frontendDirectory.deleteRecursively()
        check(frontendDirectory.mkdirs()) {
            "Could not create the DevTools frontend asset directory"
        }

        val frontendRoot = frontendDirectory.canonicalFile.toPath()
        val requiredFiles = mutableSetOf(
            "inspector.html",
            "edge_mobile.js",
            "entrypoints/inspector/inspector.js",
            "core/i18n/locales/en-US.json",
            "core/i18n/locales/ru.json",
        )
        var extractedFiles = 0
        val frontendArchive = patchClassLoader
            .getResourceAsStream(DEVTOOLS_FRONTEND_ARCHIVE)
            ?: error(
                "Missing $DEVTOOLS_FRONTEND_ARCHIVE; run scripts/bootstrap.ps1",
            )
        ZipInputStream(frontendArchive).use { archive ->
            while (true) {
                val entry = archive.nextEntry ?: break
                val path = entry.name
                    .replace('\\', '/')
                    .removePrefix("./")
                if (path.isEmpty()) {
                    archive.closeEntry()
                    continue
                }

                val output = frontendDirectory.resolve(path).canonicalFile
                check(output.toPath().startsWith(frontendRoot)) {
                    "Unsafe DevTools frontend archive entry: ${entry.name}"
                }
                if (entry.isDirectory) {
                    check(output.mkdirs() || output.isDirectory) {
                        "Could not create DevTools frontend directory: $path"
                    }
                } else {
                    check(output.parentFile.mkdirs() || output.parentFile.isDirectory) {
                        "Could not create the parent directory for: $path"
                    }
                    output.outputStream().use(archive::copyTo)
                    requiredFiles.remove(path)
                    extractedFiles++
                }
                archive.closeEntry()
            }
        }
        check(extractedFiles >= 250 && requiredFiles.isEmpty()) {
            "Incomplete DevTools frontend archive: " +
                "$extractedFiles files, missing ${requiredFiles.joinToString()}"
        }

    }
}

@Suppress("unused")
val customNewTabPatch = bytecodePatch(
    name = "Custom new tab",
    description = "Открывает выбранную веб-страницу вместо встроенной новой вкладки Edge.",
) {
    compatibleWith(EDGE_CANARY_PACKAGE)

    val newTabUrl by stringOption(
        name = "New tab URL",
        description = "Полный HTTP- или HTTPS-адрес страницы.",
        default = "http://tabpage.ariex.ru",
        required = true,
        validator = { value -> isWebUrl(value) },
    )

    apply {
        val newTabUrlSetter = firstMethodDeclaratively {
            accessFlags(AccessFlags.PUBLIC, AccessFlags.STATIC)
            returnType("V")
            parameterTypes("Ljava/lang/String;")
            strings("chrome-native://newtab/")
        }
        val escapedUrl = newTabUrl!!.toSmaliString()

        newTabUrlSetter.addInstructions(
            0,
            """
                const-string p0, "$escapedUrl"
            """,
        )
    }
}

@Suppress("unused")
val devToolsMenuPatch = bytecodePatch(
    name = "Mobile DevTools",
    description = "Добавляет мобильный DevTools с локальным подключением к текущей вкладке Edge.",
) {
    compatibleWith(EDGE_CANARY_PACKAGE)
    dependsOn(edgeMobileExtensionPatch, devToolsFrontendPatch)

    apply {
        val overflowItemFactory = firstMethodDeclaratively {
            parameterTypes("I")
            custom {
                implementation?.instructions?.any { instruction ->
                    instruction.methodReference?.let { reference ->
                        reference.definingClass ==
                            "Lorg/chromium/chrome/browser/edge_pdf/EdgePdfUtils;" &&
                            reference.name == "isCurrentTabIsPdf" &&
                            reference.parameterTypes.isEmpty() &&
                            reference.returnType == "Z"
                    } == true
                } == true
            }
        }
        val overflowItemViewDataClass = overflowItemFactory.returnType
        check(
            overflowItemFactory.instructions.any { instruction ->
                instruction.methodReference?.let { reference ->
                    reference.definingClass == overflowItemViewDataClass &&
                        reference.name == "<init>" &&
                        reference.parameterTypes == listOf("I", "I", "I") &&
                        reference.returnType == "V"
                } == true
            },
        ) {
            "Could not identify the overflow item view-data constructor"
        }
        val iconResourceClass = overflowItemFactory.instructions
            .asSequence()
            .mapNotNull { it.fieldReference }
            .firstOrNull { reference ->
                reference.name.startsWith("ic_fluent_") && reference.type == "I"
            }
            ?.definingClass
            ?: error("Could not identify the overflow icon resource class")
        val devToolsTitleSource = firstMethodDeclaratively {
            custom {
                implementation?.instructions?.any { instruction ->
                    instruction.fieldReference?.let { reference ->
                        reference.name == "menu_dev_tools" && reference.type == "I"
                    } == true
                } == true
            }
        }
        val devToolsTitle = devToolsTitleSource.instructions
            .asSequence()
            .mapNotNull { it.fieldReference }
            .first { reference ->
                reference.name == "menu_dev_tools" && reference.type == "I"
            }

        overflowItemFactory.addInstructionsWithLabels(
            0,
            """
                const/16 v0, $DEVTOOLS_OVERFLOW_ID
                if-ne p0, v0, :edge_devtools_overflow_original
                new-instance v0, $overflowItemViewDataClass
                sget v1, ${devToolsTitle.definingClass}->${devToolsTitle.name}:${devToolsTitle.type}
                sget v2, $iconResourceClass->ic_fluent_code_24_regular:I
                invoke-direct { v0, p0, v1, v2 }, $overflowItemViewDataClass-><init>(III)V
                return-object v0
            """,
            ExternalLabel(
                "edge_devtools_overflow_original",
                overflowItemFactory.getInstruction(0),
            ),
        )

        val overflowPreferencesMethod = firstMethodDeclaratively {
            returnType("V")
            parameterTypes(
                "I",
                "Ljava/util/ArrayList;",
            )
            strings("Edge.OverflowMenu.OrderList")
        }
        val overflowItems = firstMethodDeclaratively {
            definingClass(overflowPreferencesMethod.definingClass)
            returnType("Ljava/util/ArrayList;")
            parameterTypes()
            custom {
                val candidateInstructions =
                    implementation?.instructions ?: return@custom false
                candidateInstructions.count { instruction ->
                    instruction.opcode == Opcode.INVOKE_DIRECT &&
                        instruction.methodReference?.let { reference ->
                            reference.definingClass == "Ljava/util/ArrayList;" &&
                                reference.name == "<init>" &&
                                reference.parameterTypes.isNotEmpty() &&
                                reference.returnType == "V"
                        } == true
                } == 1 &&
                    candidateInstructions.none { instruction ->
                        instruction.methodReference?.let { reference ->
                            reference.definingClass == "Ljava/util/ArrayList;" &&
                                reference.name == "addAll" &&
                                reference.returnType == "Z"
                        } == true
                    }
            }
        }
        val overflowItemsConstructorIndex = overflowItems.instructions
            .withIndex()
            .firstOrNull { (_, instruction) ->
                instruction.opcode == Opcode.INVOKE_DIRECT &&
                    instruction.methodReference?.let { reference ->
                        reference.definingClass == "Ljava/util/ArrayList;" &&
                            reference.name == "<init>" &&
                            reference.parameterTypes == listOf("Ljava/util/Collection;") &&
                            reference.returnType == "V"
                    } == true
            }
            ?.index
            ?: error("Could not find the mobile overflow item list constructor")
        val overflowItemsConstructor =
            overflowItems.getInstruction<FiveRegisterInstruction>(overflowItemsConstructorIndex)
        check(overflowItemsConstructor.registerCount == 2) {
            "Unexpected mobile overflow item list constructor argument count"
        }
        overflowItems.addInstructions(
            overflowItemsConstructorIndex + 1,
            """
                const/16 v1, $DEVTOOLS_OVERFLOW_ID
                invoke-static { v1 }, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;
                move-result-object v1
                invoke-virtual { v${overflowItemsConstructor.registerC}, v1 }, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z
            """,
        )

        val overflowTelemetry = firstMethodDeclaratively {
            returnType("I")
            parameterTypes("I")
            strings("Untracked overflow id in telemetry found: ")
        }
        overflowTelemetry.addInstructionsWithLabels(
            0,
            """
                const/16 v0, $DEVTOOLS_OVERFLOW_ID
                if-ne p0, v0, :edge_devtools_telemetry_original
                const/4 p0, -0x1
                return p0
            """,
            ExternalLabel(
                "edge_devtools_telemetry_original",
                overflowTelemetry.getInstruction(0),
            ),
        )

        val mobileMenuClickHandler = firstMethodDeclaratively {
            returnType("V")
            parameterTypes("I")
            strings("Microsoft.Mobile.Overflow.ClickFavorites")
        }
        check(mobileMenuClickHandler.implementation!!.registerCount > 4) {
            "Mobile menu click handler has no safe temporary register"
        }
        val currentTabIndex = mobileMenuClickHandler.instructions
            .withIndex()
            .firstOrNull { (_, instruction) ->
                instruction.opcode == Opcode.INVOKE_VIRTUAL &&
                    instruction.methodReference?.let { reference ->
                        reference.parameterTypes.isEmpty() &&
                            reference.returnType == "Lorg/chromium/chrome/browser/tab/Tab;"
                    } == true
            }
            ?.index
            ?: error("Could not find the current tab in the mobile menu handler")
        val currentTabResultIndex = currentTabIndex + 1
        check(mobileMenuClickHandler.instructions[currentTabResultIndex].opcode == Opcode.MOVE_RESULT_OBJECT) {
            "Unexpected current tab result instruction"
        }
        val currentTabRegister =
            mobileMenuClickHandler.getInstruction<OneRegisterInstruction>(currentTabResultIndex).registerA
        mobileMenuClickHandler.addInstructionsWithLabels(
            currentTabResultIndex + 1,
            """
                const/16 v3, $DEVTOOLS_OVERFLOW_ID
                if-ne p1, v3, :edge_devtools_mobile_original
                if-eqz v$currentTabRegister, :edge_devtools_mobile_return
                invoke-static { v$currentTabRegister }, $DEVTOOLS_EXTENSION_CLASS->open(Ljava/lang/Object;)V

                :edge_devtools_mobile_return
                return-void
            """,
            ExternalLabel(
                "edge_devtools_mobile_original",
                mobileMenuClickHandler.getInstruction(currentTabResultIndex + 1),
            ),
        )
    }
}

@Suppress("unused")
val tabSwitcherThumbReachPatch = bytecodePatch(
    name = "Thumb-reach tab switcher",
    description = "Размещает старые вкладки снизу справа, а новые добавляет вверх для управления большим пальцем.",
) {
    compatibleWith(EDGE_CANARY_PACKAGE)
    dependsOn(edgeMobileExtensionPatch)

    apply {
        val linearLayoutConstructor = firstMethodDeclaratively {
            definingClass("Landroidx/recyclerview/widget/LinearLayoutManager;")
            name("<init>")
            returnType("V")
            parameterTypes("Landroid/content/Context;", "I", "Z")
        }
        val reverseLayoutParameterRegister =
            linearLayoutConstructor.implementation!!.registerCount - 1
        val reverseLayoutFields = linearLayoutConstructor.instructions.mapNotNull { instruction ->
            val registers = instruction as? TwoRegisterInstruction
            val field = instruction.fieldReference
            field?.takeIf {
                instruction.opcode == Opcode.IPUT_BOOLEAN &&
                    registers?.registerA == reverseLayoutParameterRegister &&
                    it.definingClass == "Landroidx/recyclerview/widget/LinearLayoutManager;" &&
                    it.type == "Z"
            }
        }
        check(reverseLayoutFields.size == 1) {
            "Could not uniquely identify the LinearLayoutManager reverse-layout field"
        }
        val reverseLayoutField = reverseLayoutFields.single()

        val gridTabLayoutConstructor = firstMethodDeclaratively {
            name("<init>")
            returnType("V")
            custom {
                immutableClassDef.superclass ==
                    "Landroidx/recyclerview/widget/GridLayoutManager;" &&
                    parameterTypes.lastOrNull() == "Landroid/app/Activity;" &&
                    instructions.any { instruction ->
                        instruction.opcode == Opcode.INVOKE_DIRECT &&
                            instruction.methodReference?.let { reference ->
                                reference.definingClass ==
                                    "Landroidx/recyclerview/widget/GridLayoutManager;" &&
                                    reference.name == "<init>" &&
                                    reference.parameterTypes ==
                                        listOf("Landroid/content/Context;", "I") &&
                                    reference.returnType == "V"
                            } == true
                    }
            }
        }
        val gridLayoutSuperIndex = gridTabLayoutConstructor.instructions.indexOfFirst { instruction ->
            instruction.opcode == Opcode.INVOKE_DIRECT &&
                instruction.methodReference?.let { reference ->
                    reference.definingClass == "Landroidx/recyclerview/widget/GridLayoutManager;" &&
                        reference.name == "<init>" &&
                        reference.parameterTypes == listOf("Landroid/content/Context;", "I") &&
                        reference.returnType == "V"
                } == true
        }.also { index ->
            check(index >= 0) { "Could not find the tab grid layout constructor call" }
        }
        val gridLayoutSuper =
            gridTabLayoutConstructor.getInstruction<FiveRegisterInstruction>(
                gridLayoutSuperIndex,
            )
        check(gridLayoutSuper.registerCount == 3) {
            "Unexpected tab grid layout constructor argument count"
        }
        val temporaryRegister = gridLayoutSuper.registerE
        gridTabLayoutConstructor.addInstructions(
            gridLayoutSuperIndex + 1,
            """
                const/4 v$temporaryRegister, 0x1
                iput-boolean v$temporaryRegister, p0, ${reverseLayoutField.definingClass}->${reverseLayoutField.name}:${reverseLayoutField.type}
            """,
        )

        val gridLayoutComplete = firstMethodDeclaratively {
            definingClass(gridTabLayoutConstructor.definingClass)
            returnType("V")
            custom {
                val candidateInstructions =
                    implementation?.instructions ?: return@custom false
                candidateInstructions.any { instruction ->
                    instruction.opcode == Opcode.INVOKE_SUPER &&
                        instruction.methodReference?.definingClass ==
                        "Landroidx/recyclerview/widget/GridLayoutManager;"
                }
            }
        }
        val animationTargetCallback = gridLayoutComplete.instructions
            .mapNotNull { instruction ->
                instruction.methodReference?.takeIf { reference ->
                    instruction.opcode == Opcode.INVOKE_VIRTUAL &&
                        reference.definingClass !=
                        "Landroidx/recyclerview/widget/GridLayoutManager;" &&
                        reference.parameterTypes.isEmpty() &&
                        reference.returnType == "V"
                }
            }
            .singleOrNull()
            ?: error("Could not uniquely identify the tab animation target callback")
        val animationTargetCallbackMethod = firstMethodDeclaratively {
            definingClass(animationTargetCallback.definingClass)
            name(animationTargetCallback.name)
            returnType(animationTargetCallback.returnType)
            parameterTypes(
                *animationTargetCallback.parameterTypes
                    .map(CharSequence::toString)
                    .toTypedArray(),
            )
        }
        check(animationTargetCallbackMethod.implementation!!.registerCount > 1) {
            "Tab animation target callback has no free local register"
        }
        val tabListField = animationTargetCallbackMethod.instructions
            .mapNotNull { instruction ->
                instruction.fieldReference?.takeIf { reference ->
                    instruction.opcode == Opcode.IGET_OBJECT &&
                        reference.type ==
                        "Lorg/chromium/chrome/browser/tasks/tab_management/TabListRecyclerView;"
                }
            }
            .singleOrNull()
            ?: error("Could not uniquely identify the animation target tab list")
        animationTargetCallbackMethod.addInstructionsWithLabels(
            0,
            """
                iget-object v0, p0, ${tabListField.definingClass}->${tabListField.name}:${tabListField.type}
                invoke-static { v0 }, $TAB_SWITCHER_EXTENSION_CLASS->prepareAnimationTarget(Ljava/lang/Object;)Z
                move-result v0
                if-eqz v0, :edge_tabs_animation_ready
                return-void
            """,
            ExternalLabel(
                "edge_tabs_animation_ready",
                animationTargetCallbackMethod.getInstruction(0),
            ),
        )

        val tabSwitcherInitializer = firstMethodDeclaratively {
            returnType("V")
            custom {
                val candidateInstructions =
                    implementation?.instructions ?: return@custom false
                val resourceNames = candidateInstructions.mapNotNull {
                    it.fieldReference?.name
                }.toSet()
                "tab_switcher_pane_layout" in resourceNames &&
                    "tab_list_container" in resourceNames &&
                    "pane_hairline" in resourceNames
            }
        }
        val tabListContainerIndex = tabSwitcherInitializer.instructions.indexOfFirst { instruction ->
            instruction.opcode == Opcode.SGET &&
                instruction.fieldReference?.let { reference ->
                    reference.name == "tab_list_container" &&
                        reference.type == "I"
                } == true
        }.also { index ->
            check(index >= 0) { "Could not find the tab list container resource" }
        }
        val tabListAttachIndex = tabSwitcherInitializer.instructions
            .withIndex()
            .drop(tabListContainerIndex + 1)
            .firstOrNull { (_, instruction) ->
                instruction.opcode == Opcode.INVOKE_VIRTUAL &&
                    instruction.methodReference?.let { reference ->
                        reference.definingClass == "Landroid/view/ViewGroup;" &&
                            reference.name == "addView" &&
                            reference.parameterTypes == listOf("Landroid/view/View;") &&
                            reference.returnType == "V"
                    } == true &&
                    (instruction as? FiveRegisterInstruction)?.let { invoke ->
                        invoke.registerCount == 2
                    } == true
            }
            ?.index
            ?: error("Could not find the tab list container attachment")
        val tabListRegister =
            tabSwitcherInitializer.getInstruction<FiveRegisterInstruction>(
                tabListAttachIndex,
            ).registerD

        tabSwitcherInitializer.addInstructions(
            tabListAttachIndex + 1,
            """
                invoke-static { v$tabListRegister }, $TAB_SWITCHER_EXTENSION_CLASS->install(Ljava/lang/Object;)V
            """,
        )
    }
}

@Suppress("unused")
val swipeToTabSwitcherPatch = bytecodePatch(
    name = "Swipe up to tabs",
    description = "Открывает экран вкладок свайпом вверх по панели инструментов независимо от её положения.",
) {
    compatibleWith(EDGE_CANARY_PACKAGE)
    dependsOn(edgeMobileExtensionPatch)

    apply {
        val swipeGestureClassifier = firstMethodDeclaratively {
            strings("MobileToolbarSwipeOpenStackView")
        }
        val swipeAcceptance = firstMethodDeclaratively {
            definingClass(swipeGestureClassifier.definingClass)
            returnType("Z")
            parameterTypes("Landroid/view/MotionEvent;", "I")
        }
        val directionAcceptanceIndex = swipeAcceptance.instructions
            .indices
            .firstOrNull { index ->
                swipeAcceptance.instructions[index].opcode == Opcode.IF_EQ &&
                    swipeAcceptance.instructions.getOrNull(index + 1)?.opcode == Opcode.IF_EQ &&
                    swipeAcceptance.instructions.getOrNull(index + 2)?.opcode == Opcode.CONST_4 &&
                    swipeAcceptance.instructions.getOrNull(index + 3)?.opcode == Opcode.IF_NE &&
                    swipeAcceptance.instructions.getOrNull(index + 4)?.opcode == Opcode.RETURN
            }
            ?: error("Could not find the toolbar swipe direction check")

        swipeAcceptance.addInstructionsWithLabels(
            directionAcceptanceIndex,
            // yun.s uses direction 3 for an upward toolbar swipe.
            """
                const/4 p0, 0x3
                if-eq p2, p0, :edge_swipe_open_tabs
            """,
            ExternalLabel(
                "edge_swipe_open_tabs",
                swipeAcceptance.getInstruction(directionAcceptanceIndex + 4),
            ),
        )
    }
}

@Suppress("unused")
val chromeWebStorePatch = bytecodePatch(
    name = "Chrome Web Store extension installation",
    description = "Включает обычную установку с сайта Chrome Web Store и автоматически активирует установленные расширения.",
) {
    compatibleWith(EDGE_CANARY_PACKAGE)
    dependsOn(webContentsJavaScriptPatch)

    apply {
        val nativeCrxInstaller = firstMethodDeclaratively {
            returnType("V")
            strings(
                EDGE_EXTENSION_INSTALL_ACTION,
                EDGE_EXTENSION_CRX_EXTRA,
            )
        }
        check(nativeCrxInstaller.implementation != null) {
            "Edge's native CRX installation contract is unavailable"
        }

        val chromeWebStoreObserver = firstMethodDeclaratively {
            returnType("V")
            parameterTypes(
                "Lorg/chromium/chrome/browser/tab/Tab;",
                "Lorg/chromium/url/GURL;",
            )
            strings("OfflinePageTO")
            custom {
                immutableClassDef.methods.any { method ->
                    method.name == "G1" &&
                        method.returnType == "V" &&
                        method.parameters.map { parameter -> parameter.type } == listOf(
                            "Lorg/chromium/chrome/browser/tab/Tab;",
                            "Lorg/chromium/url/GURL;",
                        )
                }
            }
        }
        val urlUpdatedObserver = firstMethodDeclaratively {
            definingClass(chromeWebStoreObserver.definingClass)
            name("G1")
            returnType("V")
            parameterTypes(
                "Lorg/chromium/chrome/browser/tab/Tab;",
                "Lorg/chromium/url/GURL;",
            )
        }
        val originalImplementation = urlUpdatedObserver.implementation!!
        check(originalImplementation.registerCount == 3) {
            "Unexpected URL observer register count"
        }
        urlUpdatedObserver.setImplementation(
            MutableMethodImplementation(
                ImmutableMethodImplementation(
                    originalImplementation.registerCount + 1,
                    originalImplementation.instructions,
                    originalImplementation.tryBlocks,
                    originalImplementation.debugItems,
                ),
            ),
        )
        // Expanding the register file shifts the receiver from v0 to v1. Restore it
        // at the original entry point after the injected code has used local v0.
        urlUpdatedObserver.addInstructions(0, "move-object v0, p0")
        urlUpdatedObserver.addInstructionsWithLabels(
            0,
            """
                if-eqz p2, :edge_cws_original
                invoke-virtual { p2 }, Lorg/chromium/url/GURL;->j()Ljava/lang/String;
                move-result-object p2
                invoke-static { p1, p2 }, $CHROME_WEB_STORE_EXTENSION_CLASS->onUrlUpdated(Ljava/lang/Object;Ljava/lang/String;)V
            """,
            ExternalLabel("edge_cws_original", urlUpdatedObserver.getInstruction(0)),
        )

        val installResultHandler = firstMethodDeclaratively {
            name("onResult")
            returnType("V")
            parameterTypes(
                "Ljava/lang/String;",
                "I",
                "Z",
                "Ljava/lang/String;",
                "I",
                "I",
            )
            custom {
                implementation?.instructions?.any { instruction ->
                    instruction.opcode == Opcode.SGET_OBJECT &&
                        instruction.fieldReference?.let { reference ->
                            reference.definingClass ==
                                "Lcom/microsoft/edge/extensions/EdgeAndroidExtensionsAPI;" &&
                                reference.type == "Ljava/util/HashSet;"
                        } == true
                } == true
            }
        }
        val allowlistLoadIndex = installResultHandler.instructions.indexOfFirst { instruction ->
            instruction.opcode == Opcode.SGET_OBJECT &&
                instruction.fieldReference?.let { reference ->
                    reference.definingClass == "Lcom/microsoft/edge/extensions/EdgeAndroidExtensionsAPI;" &&
                        reference.type == "Ljava/util/HashSet;"
                } == true
        }.also { index ->
            check(index >= 0) { "Could not find extension auto-enable allowlist" }
        }
        val allowlistContainsIndex = installResultHandler.instructions
            .withIndex()
            .drop(allowlistLoadIndex + 1)
            .firstOrNull { (_, instruction) ->
                instruction.opcode == Opcode.INVOKE_VIRTUAL &&
                    instruction.methodReference?.let { reference ->
                        reference.definingClass == "Ljava/util/HashSet;" &&
                            reference.name == "contains" &&
                            reference.parameterTypes == listOf("Ljava/lang/Object;") &&
                            reference.returnType == "Z"
                    } == true
            }
            ?.index
            ?: error("Could not find extension auto-enable allowlist check")

        val allowlistResult = installResultHandler.getInstruction<OneRegisterInstruction>(
            allowlistContainsIndex + 1,
        )
        check(allowlistResult.opcode == Opcode.MOVE_RESULT) {
            "Unexpected extension auto-enable allowlist result"
        }
        installResultHandler.addInstructions(
            allowlistContainsIndex + 2,
            "const/4 v${allowlistResult.registerA}, 0x1",
        )
    }
}

@Suppress("unused")
val dismissMicrosoftAccountNoticePatch = bytecodePatch(
    name = "Dismiss Microsoft account notice",
    description = "Автоматически закрывает повторяющееся информационное окно Microsoft после входа, не отключая аккаунт и синхронизацию.",
) {
    compatibleWith(EDGE_CANARY_PACKAGE)
    dependsOn(webContentsJavaScriptPatch)

    apply {
        val urlUpdated = firstMethodDeclaratively {
            definingClass("Lorg/chromium/chrome/browser/tab/TabImpl;")
            name("n0")
            returnType("V")
            parameterTypes("Lorg/chromium/url/GURL;")
        }
        urlUpdated.addInstructionsWithLabels(
            0,
            """
                if-eqz p1, :edge_account_notice_original
                invoke-virtual { p1 }, Lorg/chromium/url/GURL;->j()Ljava/lang/String;
                move-result-object v0
                invoke-static { p0, v0 }, $MICROSOFT_ACCOUNT_NOTICE_EXTENSION_CLASS->onUrlUpdated(Ljava/lang/Object;Ljava/lang/String;)V
            """,
            ExternalLabel(
                "edge_account_notice_original",
                urlUpdated.getInstruction(0),
            ),
        )
    }
}
