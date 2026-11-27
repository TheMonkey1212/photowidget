# Third-party licenses

Photo Widget itself is licensed under the **GNU General Public License, version 3**
(see [LICENSE](LICENSE)). It is fully offline and contains no advertising, analytics,
account or payment SDKs.

The app uses the following libraries. All runtime dependencies are licensed under
the Apache License 2.0, which is compatible with the GPLv3. None of them require
network access at runtime. Test-only dependencies are listed separately and are not
shipped in the APK.

## Runtime dependencies

| Library | Version | License |
| --- | --- | --- |
| Kotlin standard library | 2.0.21 | Apache License 2.0 |
| kotlinx-coroutines (core, android) | 1.8.1 | Apache License 2.0 |
| kotlinx-serialization-json | 1.7.3 | Apache License 2.0 |
| AndroidX Core KTX | 1.13.1 | Apache License 2.0 |
| AndroidX AppCompat | 1.7.0 | Apache License 2.0 |
| AndroidX Activity | 1.9.3 | Apache License 2.0 |
| AndroidX Fragment | 1.8.5 | Apache License 2.0 |
| AndroidX Lifecycle (runtime, viewmodel) | 2.8.7 | Apache License 2.0 |
| AndroidX RecyclerView | 1.3.2 | Apache License 2.0 |
| AndroidX ConstraintLayout | 2.1.4 | Apache License 2.0 |
| AndroidX DocumentFile | 1.0.1 | Apache License 2.0 |
| AndroidX ExifInterface | 1.3.7 | Apache License 2.0 |
| AndroidX Room (runtime, ktx) | 2.6.1 | Apache License 2.0 |
| AndroidX WorkManager (runtime-ktx) | 2.9.1 | Apache License 2.0 |
| Material Components for Android | 1.12.0 | Apache License 2.0 |

Apache License 2.0: <https://www.apache.org/licenses/LICENSE-2.0>

## Build and test dependencies (not shipped)

| Library | Version | License |
| --- | --- | --- |
| JUnit 4 | 4.13.2 | Eclipse Public License 1.0 |
| Robolectric | 4.14.1 | MIT License |
| AndroidX Test Core | 1.6.1 | Apache License 2.0 |
| kotlinx-coroutines-test | 1.8.1 | Apache License 2.0 |
| AndroidX Room testing | 2.6.1 | Apache License 2.0 |

Eclipse Public License 1.0: <https://www.eclipse.org/legal/epl-v10.html>  
Robolectric MIT license: <https://github.com/robolectric/robolectric/blob/master/LICENSE>

## Platform APIs

Android framework APIs (MediaStore, Storage Access Framework, AppWidget, AlarmManager,
WorkManager, Room) are part of the Android platform and are used under the Android
SDK terms. No proprietary or closed-source libraries are bundled.

## Verification

Dependency coordinates and versions are declared in the `build.gradle.kts` files of
each module. The project does not use dynamic versions, repositories other than
Google Maven and Maven Central, or dependencies with unclear licensing.
