from pathlib import Path
import json, os, subprocess

# Builds an isolated package for N64 device tests without touching installed user data.
# Run with: adb shell am instrument -w -e n64RomPath <owned-ROM-path> \
#   com.sbro.emucored.uiqa.test/androidx.test.runner.AndroidJUnitRunner
root = Path(__file__).resolve().parent.parent
config = root / 'app/src/debug/google-services.json'
if config.exists():
    raise SystemExit('Refusing to overwrite existing debug configuration')
(root / 'build').mkdir(exist_ok=True)
initializer = root / 'build/codex-uiqa.init.gradle'
initializer.write_text('''gradle.beforeProject { project ->
 if (project.name == 'app') project.plugins.withId('com.android.application') {
  project.afterEvaluate { project.android.buildTypes.getByName('debug').applicationIdSuffix = '.uiqa' }
 }
}
gradle.projectsEvaluated {
 def app = gradle.rootProject.findProject(':app')
 app.tasks.named('compileDebugAndroidTestKotlin').get().setSource(app.fileTree('src/androidTest/java') {
  include '**/N64InputDeviceInstrumentedTest.kt'
  include '**/N64LayoutDeviceInstrumentedTest.kt'
 })
}
''', encoding='utf-8')
data = json.loads((root / 'app/google-services.json').read_text(encoding='utf-8'))
for client in data['client']:
    info = client['client_info']['android_client_info']
    if info['package_name'] == 'com.sbro.emucored':
        info['package_name'] = 'com.sbro.emucored.uiqa'
config.write_text(json.dumps(data), encoding='utf-8')
try:
    env = dict(os.environ)
    bundled_java = Path('C:/Program Files/Android/Android Studio/jbr')
    if not env.get('JAVA_HOME') and bundled_java.exists():
        env['JAVA_HOME'] = str(bundled_java)
    with (root / 'build/codex-uiqa-build.log').open('w', encoding='utf-8') as log:
        result = subprocess.run(['cmd', '/c', 'gradlew.bat', ':app:assembleDebug',
            ':app:assembleDebugAndroidTest', '-I', str(initializer), '--console=plain'],
            cwd=root, stdout=log, stderr=subprocess.STDOUT, env=env)
    if result.returncode:
        raise SystemExit(result.returncode)
finally:
    config.unlink(missing_ok=True)
