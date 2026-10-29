#!/usr/bin/env python3
"""Generate an isolated XCUITest project from the app's current project.

Usage: run-settings-units-ui.py OUTPUT_DIR [SettingsUnits|MainMenu|MapCache]
Run on a dedicated Simulator. StartupRecovery accepts terms only in its disposable fixture.
"""
import json
import pathlib
import plistlib
import subprocess
import sys

apple = pathlib.Path(__file__).resolve().parents[1]
out = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else '/tmp/r2c-settings-ui')
suite = sys.argv[2] if len(sys.argv) > 2 else 'SettingsUnits'
if suite not in ('SettingsUnits', 'MainMenu', 'MapCache', 'StartupRecovery'):
    raise SystemExit('Supported suites: SettingsUnits, MainMenu, MapCache, StartupRecovery')
project = out / f'{suite}.xcodeproj'
project.mkdir(parents=True, exist_ok=True)
p = json.loads(subprocess.check_output(['plutil', '-convert', 'json', '-o', '-', str(apple / 'RID2CaltopoApple.xcodeproj/project.pbxproj')]))
o = p['objects']
def key(n): return f'EE000000000000000000{n:04X}'
target, source, product, build, sources, frameworks, configs, debug, release, dep, proxy = map(key, range(1, 12))
app_target = '100000000000000000000050'
o[source] = dict(isa='PBXFileReference', lastKnownFileType='sourcecode.swift', path=str(apple / f'Tests/{suite}UITests/{suite}UITests.swift'), sourceTree='<absolute>')
o[product] = dict(isa='PBXFileReference', explicitFileType='wrapper.cfbundle', path=f'{suite}UITests.xctest', sourceTree='BUILT_PRODUCTS_DIR')
o[build] = dict(isa='PBXBuildFile', fileRef=source)
o[sources] = dict(isa='PBXSourcesBuildPhase', buildActionMask=2147483647, files=[build], runOnlyForDeploymentPostprocessing=0)
o[frameworks] = dict(isa='PBXFrameworksBuildPhase', buildActionMask=2147483647, files=[], runOnlyForDeploymentPostprocessing=0)
o[proxy] = dict(isa='PBXContainerItemProxy', containerPortal=p['rootObject'], proxyType=1, remoteGlobalIDString=app_target, remoteInfo='RID2CaltopoApple')
o[dep] = dict(isa='PBXTargetDependency', target=app_target, targetProxy=proxy)
o[target] = dict(isa='PBXNativeTarget', buildConfigurationList=configs, buildPhases=[sources, frameworks], buildRules=[], dependencies=[dep], name=f'{suite}UITests', productName=f'{suite}UITests', productReference=product, productType='com.apple.product-type.bundle.ui-testing')
settings = dict(GENERATE_INFOPLIST_FILE='YES', PRODUCT_BUNDLE_IDENTIFIER=f'org.ncssar.RID2CaltopoApple.{suite}UITests', PRODUCT_NAME='$(TARGET_NAME)', SDKROOT='iphoneos', IPHONEOS_DEPLOYMENT_TARGET='17.0', TARGETED_DEVICE_FAMILY='1,2', SWIFT_VERSION='6.0', TEST_TARGET_NAME='RID2CaltopoApple', CODE_SIGNING_ALLOWED='NO')
o[debug] = dict(isa='XCBuildConfiguration', buildSettings=settings, name='Debug')
o[release] = dict(isa='XCBuildConfiguration', buildSettings=settings, name='Release')
o[configs] = dict(isa='XCConfigurationList', buildConfigurations=[debug, release], defaultConfigurationIsVisible=0, defaultConfigurationName='Debug')
root = o[p['rootObject']]
root['projectDirPath'] = str(apple)
root['targets'].append(target)
root['attributes']['TargetAttributes'][target] = dict(CreatedOnToolsVersion='27.0', TestTargetID=app_target)
o[root['mainGroup']]['children'].append(source)
o[root['productRefGroup']]['children'].append(product)
for obj in o.values():
    if obj.get('isa') == 'XCLocalSwiftPackageReference': obj['relativePath'] = str(apple)
(project / 'project.pbxproj').write_bytes(plistlib.dumps(p))
configuration = 'Release' if suite == 'StartupRecovery' else 'Debug'
scheme = project / 'xcshareddata/xcschemes'
scheme.mkdir(parents=True, exist_ok=True)
def reference(t, name, product_name):
    return f'<BuildableReference BuildableIdentifier="primary" BlueprintIdentifier="{t}" BuildableName="{product_name}" BlueprintName="{name}" ReferencedContainer="container:{project}"/>'
test_debugger = "" if suite == "StartupRecovery" else "Xcode.DebuggerFoundation.Debugger.LLDB"
test_launcher = "Xcode.IDEFoundation.Launcher.PosixSpawn" if suite == "StartupRecovery" else "Xcode.IDEFoundation.Launcher.LLDB"
app_ref = reference(app_target, 'RID2CaltopoApple', 'RID2CaltopoApple.app')
test_ref = reference(target, f'{suite}UITests', f'{suite}UITests.xctest')
(scheme / f'{suite}.xcscheme').write_text(f'''<?xml version="1.0" encoding="UTF-8"?>
<Scheme LastUpgradeVersion="2700" version="1.3">
<BuildAction parallelizeBuildables="NO" buildImplicitDependencies="YES"><BuildActionEntries>
<BuildActionEntry buildForTesting="YES" buildForRunning="YES" buildForProfiling="NO" buildForArchiving="NO" buildForAnalyzing="YES">{app_ref}</BuildActionEntry>
<BuildActionEntry buildForTesting="YES" buildForRunning="NO" buildForProfiling="NO" buildForArchiving="NO" buildForAnalyzing="NO">{test_ref}</BuildActionEntry>
</BuildActionEntries></BuildAction>
<TestAction buildConfiguration="{configuration}" selectedDebuggerIdentifier="{test_debugger}" selectedLauncherIdentifier="{test_launcher}" shouldUseLaunchSchemeArgsEnv="YES"><Testables><TestableReference skipped="NO" parallelizable="NO">{test_ref}</TestableReference></Testables></TestAction>
<LaunchAction buildConfiguration="Debug" selectedDebuggerIdentifier="Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier="Xcode.IDEFoundation.Launcher.LLDB"><BuildableProductRunnable runnableDebuggingMode="0">{app_ref}</BuildableProductRunnable></LaunchAction>
</Scheme>''')
print(project)
