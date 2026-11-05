#!/usr/bin/env python3
"""Generates ios/ResProxy.xcodeproj from the folders (no XcodeGen needed). Re-run after adding or removing files:

    python3 ios/scripts/gen-project.py

Targets: ResProxy (app, ios/App) and ResProxyTunnel (packet tunnel extension, ios/Tunnel), both using the local
Swift package ios/ResProxyCore. The tunnel links the native libs built by ios/scripts/build-native.sh (run as a
build phase). IDs are derived from names, so the output is stable and diffs stay small.
"""
import hashlib
import os
import subprocess

IOS = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ROOT = os.path.dirname(IOS)
TEAM = os.environ.get("RP_TEAM", "DK366LFCDT")
BUNDLE = os.environ.get("RP_BUNDLE", "dev.resproxy")
GROUP = "group." + BUNDLE
MIN_IOS = "16.0"

try:
    COUNT = subprocess.check_output(["git", "-C", ROOT, "rev-list", "--count", "HEAD"], text=True).strip()
except Exception:
    COUNT = "1"


def uid(*parts):
    return hashlib.md5("/".join(parts).encode()).hexdigest()[:24].upper()


def q(s):
    s = str(s)
    if s and all(c.isalnum() or c in "._/$-" for c in s) and not s.startswith("$("):
        return s
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"') + '"'


def files(folder, exts):
    out = []
    for name in sorted(os.listdir(os.path.join(IOS, folder))):
        if os.path.splitext(name)[1] in exts:
            out.append(name)
    return out


objects = {}


def obj(i, isa, **kv):
    objects[i] = (isa, kv)
    return i


def ftype(name):
    ext = os.path.splitext(name)[1]
    return {
        ".swift": "sourcecode.swift", ".c": "sourcecode.c.c", ".h": "sourcecode.c.h", ".plist": "text.plist.xml",
        ".entitlements": "text.plist.entitlements", ".xcassets": "folder.assetcatalog",
    }.get(ext, "text")


# ---------- groups and file references ----------
groups = {}
for folder in ["App", "Tunnel"]:
    children = []
    for name in sorted(os.listdir(os.path.join(IOS, folder))):
        if name.startswith("."):
            continue
        fr = obj(uid("file", folder, name), "PBXFileReference", lastKnownFileType=ftype(name), path=name, sourceTree="<group>")
        children.append(fr)
    groups[folder] = obj(uid("group", folder), "PBXGroup", children=children, path=folder, sourceTree="<group>")

pkg_ref = obj(uid("pkgref"), "XCLocalSwiftPackageReference", relativePath="ResProxyCore")
core_folder = obj(uid("file", "ResProxyCore"), "PBXFileReference", lastKnownFileType="wrapper", path="ResProxyCore", sourceTree="<group>")
app_product = obj(uid("product", "app"), "PBXFileReference", explicitFileType="wrapper.application", includeInIndex=0, path="ResProxy.app", sourceTree="BUILT_PRODUCTS_DIR")
ext_product = obj(uid("product", "ext"), "PBXFileReference", explicitFileType="wrapper.app-extension", includeInIndex=0, path="ResProxyTunnel.appex", sourceTree="BUILT_PRODUCTS_DIR")
products = obj(uid("group", "Products"), "PBXGroup", children=[app_product, ext_product], name="Products", sourceTree="<group>")
fw = []
for lib in ["NetworkExtension.framework"]:
    fw.append(obj(uid("fw", lib), "PBXFileReference", lastKnownFileType="wrapper.framework", name=lib, path="System/Library/Frameworks/" + lib, sourceTree="SDKROOT"))
frameworks = obj(uid("group", "Frameworks"), "PBXGroup", children=fw, name="Frameworks", sourceTree="<group>")
main_group = obj(uid("group", "main"), "PBXGroup", children=[core_folder, groups["App"], groups["Tunnel"], frameworks, products], sourceTree="<group>")


def build_files(target, folder, exts):
    out = []
    for name in files(folder, exts):
        out.append(obj(uid("bf", target, name), "PBXBuildFile", fileRef=uid("file", folder, name)))
    return out


def core_dep(target):
    dep = obj(uid("pkgdep", target), "XCSwiftPackageProductDependency", package=pkg_ref, productName="ResProxyCore")
    bf = obj(uid("bf", target, "core"), "PBXBuildFile", productRef=dep)
    return dep, bf


# ---------- build settings ----------
def config_list(name, common, debug_extra=None, release_extra=None):
    d = dict(common); d.update(debug_extra or {})
    r = dict(common); r.update(release_extra or {})
    c1 = obj(uid("cfg", name, "Debug"), "XCBuildConfiguration", buildSettings=d, name="Debug")
    c2 = obj(uid("cfg", name, "Release"), "XCBuildConfiguration", buildSettings=r, name="Release")
    return obj(uid("cfglist", name), "XCConfigurationList", buildConfigurations=[c1, c2], defaultConfigurationIsVisible=0, defaultConfigurationName="Release")


project_settings = {
    "ALWAYS_SEARCH_USER_PATHS": "NO", "CLANG_ENABLE_MODULES": "YES", "CLANG_ENABLE_OBJC_ARC": "YES",
    "IPHONEOS_DEPLOYMENT_TARGET": MIN_IOS, "SDKROOT": "iphoneos", "SWIFT_VERSION": "5.0", "TARGETED_DEVICE_FAMILY": "1,2",
    "DEVELOPMENT_TEAM": TEAM, "CODE_SIGN_STYLE": "Automatic", "MARKETING_VERSION": "0.1." + COUNT,
    "CURRENT_PROJECT_VERSION": COUNT, "ENABLE_USER_SCRIPT_SANDBOXING": "NO", "GCC_C_LANGUAGE_STANDARD": "gnu11",
}
proj_debug = {"ONLY_ACTIVE_ARCH": "YES", "SWIFT_OPTIMIZATION_LEVEL": "-Onone", "DEBUG_INFORMATION_FORMAT": "dwarf", "SWIFT_ACTIVE_COMPILATION_CONDITIONS": "DEBUG"}
proj_release = {"SWIFT_OPTIMIZATION_LEVEL": "-O", "SWIFT_COMPILATION_MODE": "wholemodule", "DEBUG_INFORMATION_FORMAT": "dwarf-with-dsym"}

app_settings = {
    "PRODUCT_NAME": "ResProxy", "PRODUCT_BUNDLE_IDENTIFIER": BUNDLE, "INFOPLIST_FILE": "App/Info.plist",
    "CODE_SIGN_ENTITLEMENTS": "App/ResProxy.entitlements", "ASSETCATALOG_COMPILER_APPICON_NAME": "AppIcon", "ASSETCATALOG_COMPILER_GLOBAL_ACCENT_COLOR_NAME": "AccentColor",
    "LD_RUNPATH_SEARCH_PATHS": "$(inherited) @executable_path/Frameworks", "RP_APP_GROUP": GROUP,
}
ext_settings = {
    "PRODUCT_NAME": "ResProxyTunnel", "PRODUCT_BUNDLE_IDENTIFIER": BUNDLE + ".tunnel", "INFOPLIST_FILE": "Tunnel/Info.plist",
    "CODE_SIGN_ENTITLEMENTS": "Tunnel/ResProxyTunnel.entitlements", "SKIP_INSTALL": "YES", "APPLICATION_EXTENSION_API_ONLY": "YES",
    "LD_RUNPATH_SEARCH_PATHS": "$(inherited) @executable_path/Frameworks @executable_path/../../Frameworks",
    "SWIFT_OBJC_BRIDGING_HEADER": "Tunnel/Tunnel-Bridging-Header.h",
    "HEADER_SEARCH_PATHS": "$(inherited) $(SRCROOT)/build/native/include $(SRCROOT)/Tunnel",
    "LIBRARY_SEARCH_PATHS[sdk=iphoneos*]": "$(inherited) $(SRCROOT)/build/native/iphoneos",
    "LIBRARY_SEARCH_PATHS[sdk=iphonesimulator*]": "$(inherited) $(SRCROOT)/build/native/iphonesimulator",
    "OTHER_LDFLAGS": "$(inherited) -lhev -lresproxy -lresolv", "RP_APP_GROUP": GROUP,
    # The Go archive is built without bitcode/dead-strip metadata Xcode expects for some checks.
    "DEAD_CODE_STRIPPING": "YES", "ENABLE_BITCODE": "NO",
}

# ---------- tunnel target ----------
ext_dep, ext_core_bf = core_dep("ext")
ext_sources = obj(uid("phase", "ext", "sources"), "PBXSourcesBuildPhase", buildActionMask=2147483647, files=build_files("ext", "Tunnel", {".swift", ".c"}), runOnlyForDeploymentPostprocessing=0)
ne_bf_ext = obj(uid("bf", "ext", "NE"), "PBXBuildFile", fileRef=fw[0])
ext_frameworks = obj(uid("phase", "ext", "frameworks"), "PBXFrameworksBuildPhase", buildActionMask=2147483647, files=[ext_core_bf, ne_bf_ext], runOnlyForDeploymentPostprocessing=0)
native_script = obj(uid("phase", "ext", "native"), "PBXShellScriptBuildPhase", buildActionMask=2147483647, files=[], inputPaths=[],
                    name="Build native libs (hev + Go bridge)", outputPaths=[], runOnlyForDeploymentPostprocessing=0,
                    shellPath="/bin/bash", shellScript='"$SRCROOT/scripts/build-native.sh" "$PLATFORM_NAME"\n', alwaysOutOfDate=1)
ext_target = obj(uid("target", "ext"), "PBXNativeTarget", buildConfigurationList=config_list("ext", ext_settings),
                 buildPhases=[native_script, ext_sources, ext_frameworks], buildRules=[], dependencies=[], name="ResProxyTunnel",
                 packageProductDependencies=[ext_dep], productName="ResProxyTunnel", productReference=ext_product,
                 productType="com.apple.product-type.app-extension")

# ---------- app target ----------
app_dep, app_core_bf = core_dep("app")
app_sources = obj(uid("phase", "app", "sources"), "PBXSourcesBuildPhase", buildActionMask=2147483647, files=build_files("app", "App", {".swift"}), runOnlyForDeploymentPostprocessing=0)
app_resources = obj(uid("phase", "app", "resources"), "PBXResourcesBuildPhase", buildActionMask=2147483647, files=build_files("app", "App", {".xcassets"}), runOnlyForDeploymentPostprocessing=0)
ne_bf_app = obj(uid("bf", "app", "NE"), "PBXBuildFile", fileRef=fw[0])
app_frameworks = obj(uid("phase", "app", "frameworks"), "PBXFrameworksBuildPhase", buildActionMask=2147483647, files=[app_core_bf, ne_bf_app], runOnlyForDeploymentPostprocessing=0)
embed_bf = obj(uid("bf", "app", "embed"), "PBXBuildFile", fileRef=ext_product, settings={"ATTRIBUTES": ["RemoveHeadersOnCopy"]})
embed = obj(uid("phase", "app", "embed"), "PBXCopyFilesBuildPhase", buildActionMask=2147483647, dstPath="", dstSubfolderSpec=13,
            files=[embed_bf], name="Embed App Extensions", runOnlyForDeploymentPostprocessing=0)
proxy = obj(uid("proxy", "ext"), "PBXContainerItemProxy", containerPortal=uid("project"), proxyType=1, remoteGlobalIDString=ext_target, remoteInfo="ResProxyTunnel")
dep = obj(uid("dep", "ext"), "PBXTargetDependency", target=ext_target, targetProxy=proxy)
app_target = obj(uid("target", "app"), "PBXNativeTarget", buildConfigurationList=config_list("app", app_settings),
                 buildPhases=[app_sources, app_frameworks, app_resources, embed], buildRules=[], dependencies=[dep], name="ResProxy",
                 packageProductDependencies=[app_dep], productName="ResProxy", productReference=app_product,
                 productType="com.apple.product-type.application")

obj(uid("project"), "PBXProject",
    attributes={"BuildIndependentTargetsInParallel": 1, "LastSwiftUpdateCheck": 1600, "LastUpgradeCheck": 1600,
                "TargetAttributes": {app_target: {"CreatedOnToolsVersion": "16.0"}, ext_target: {"CreatedOnToolsVersion": "16.0"}}},
    buildConfigurationList=config_list("project", project_settings, proj_debug, proj_release),
    compatibilityVersion="Xcode 14.0", developmentRegion="en", hasScannedForEncodings=0, knownRegions=["en", "Base"],
    mainGroup=main_group, packageReferences=[pkg_ref], productRefGroup=products, projectDirPath="", projectRoot="",
    targets=[app_target, ext_target])


# ---------- serialize ----------
def val(v, ind):
    pad = "\t" * ind
    if isinstance(v, dict):
        s = "{\n"
        for k in sorted(v):
            s += pad + "\t" + q(k) + " = " + val(v[k], ind + 1) + ";\n"
        return s + pad + "}"
    if isinstance(v, list):
        s = "(\n"
        for x in v:
            s += pad + "\t" + val(x, ind + 1) + ",\n"
        return s + pad + ")"
    return q(v)


out = "// !$*UTF8*$!\n{\n\tarchiveVersion = 1;\n\tclasses = {\n\t};\n\tobjectVersion = 60;\n\tobjects = {\n"
by_isa = {}
for i, (isa, kv) in objects.items():
    by_isa.setdefault(isa, []).append(i)
for isa in sorted(by_isa):
    out += "\n/* Begin %s section */\n" % isa
    for i in sorted(by_isa[isa]):
        kv = dict(objects[i][1]); kv["isa"] = isa
        out += "\t\t" + i + " = " + val(kv, 2) + ";\n"
    out += "/* End %s section */\n" % isa
out += "\t};\n\trootObject = %s;\n}\n" % uid("project")

proj = os.path.join(IOS, "ResProxy.xcodeproj")
os.makedirs(proj, exist_ok=True)
with open(os.path.join(proj, "project.pbxproj"), "w") as f:
    f.write(out)
print("wrote", os.path.join(proj, "project.pbxproj"))
