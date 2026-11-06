# Engineering notes

## Foreground-service compatibility investigation

The reported Android 14 runtime issue first failed with the original error **“foreground-service type none”**: the worker was started without a foreground-service type required by the platform and target configuration.

An attempted follow-up using `mediaProcessing` produced a second error on API 34: the type was unsupported or unknown on that API level. `mediaProcessing` is only appropriate on API 35 and later for this project.

The final compatibility mapping in `VideoWorker` is:

| Android API | Foreground-service type |
|---|---|
| 29–34 | `DATA_SYNC` |
| 35+ | `MEDIA_PROCESSING` |

The manifest declares both types and their permissions. Keep the runtime selection and manifest declarations aligned when changing supported Android versions.

## Debugging approach

The issue was investigated through Logcat runtime errors rather than relying only on compilation. The worker logs the running work ID, attempt, SDK, target SDK, and selected type to make API-specific behavior observable. Do not include private Logcat dumps or device identifiers in public project documentation.

**A `BUILD SUCCESSFUL` result alone did not prove the runtime fix.** Compilation validates API references and packaging, but does not exercise Android's foreground-service type enforcement on a particular OS version. Device/runtime testing on API 34 and API 35+ is needed to validate both branches.
